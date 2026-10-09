package org.openmrs.module.medreport.api.impl;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.Concept;
import org.openmrs.Encounter;
import org.openmrs.EncounterType;
import org.openmrs.Obs;
import org.openmrs.Patient;
import org.openmrs.User;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.api.impl.BaseOpenmrsService;
import org.openmrs.module.medreport.MedreportConstants;
import org.openmrs.module.medreport.MedreportPrivileges;
import org.openmrs.module.medreport.api.ImageReportService;
import org.openmrs.module.medreport.api.MedreportAuditService;
import org.openmrs.module.medreport.api.dao.MedreportDao;
import org.openmrs.module.medreport.api.model.ImageReport;
import org.openmrs.module.medreport.api.model.ImageReportVersion;
import org.openmrs.module.medreport.api.model.ReportImageLink;
import org.openmrs.module.medreport.api.render.DocumentContext;
import org.openmrs.module.medreport.api.render.RenderException;
import org.openmrs.module.medreport.api.render.RenderedDocument;
import org.openmrs.module.medreport.api.render.ReportRenderClient;
import org.openmrs.obs.ComplexData;
import org.openmrs.util.PrivilegeConstants;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;

public class ImageReportServiceImpl extends BaseOpenmrsService implements ImageReportService {

    private static final Log log = LogFactory.getLog(ImageReportServiceImpl.class);

    private MedreportDao dao;

    private MedreportAuditService auditService;

    private ReportRenderClient renderClient;

    public void setDao(MedreportDao dao) {
        this.dao = dao;
    }

    public void setAuditService(MedreportAuditService auditService) {
        this.auditService = auditService;
    }

    public void setRenderClient(ReportRenderClient renderClient) {
        this.renderClient = renderClient;
    }

    // ==================================================================
    // Create (RP1)
    // ==================================================================

    public ImageReport createReport(Patient patient, String title, String observationText,
                                    List<ReportImageLink> images) {
        MedreportPrivileges.requireImagingManage();

        if (patient == null) {
            throw new APIException("A report must be attached to a patient.");
        }
        if (observationText == null || observationText.trim().isEmpty()) {
            throw new APIException("A report cannot be empty.");
        }
        if (images == null || images.isEmpty()) {
            throw new APIException("A report must reference at least one image or study.");
        }

        Date now = new Date();
        User author = Context.getAuthenticatedUser();

        ImageReport report = new ImageReport();
        report.setUuid(UUID.randomUUID().toString());
        report.setPatient(patient);
        report.setAuthor(author);
        report.setDateCreated(now);
        report.setVoided(false);
        report.setEncounter(resolveEncounter(patient, now));
        dao.saveReport(report);

        ImageReportVersion version = newVersion(report, MedreportConstants.CHANGE_CREATE, null, now);
        version.setVersionNumber(1);
        version.setTitle(trimToNull(title));
        version.setObservationText(observationText);
        attachImages(version, images);
        dao.saveVersion(version);

        storeRenderedDocument(report, version);

        report.setCurrentVersion(version);
        dao.saveReport(report);

        auditService.log(MedreportConstants.ACTION_CREATE, report, version,
                "Created over " + version.getImages().size() + " image(s): " + describeImages(version));
        return report;
    }

    // ==================================================================
    // Read (RP2)
    // ==================================================================

    public ImageReport getReport(String reportUuid) {
        MedreportPrivileges.requireImagingView();
        ImageReport report = dao.getReportByUuid(reportUuid);
        if (report == null) {
            return null;
        }
        // A soft-deleted report is invisible to everyone but an administrator, its own
        // author included (RP5).
        if (report.isVoided() && !MedreportPrivileges.isReportAdmin()) {
            return null;
        }
        return report;
    }

    public List<ImageReport> getReportsForStudy(String orthancStudyUid) {
        MedreportPrivileges.requireImagingView();
        return dao.getReportsForStudy(orthancStudyUid, MedreportPrivileges.isReportAdmin());
    }

    public List<ImageReport> getReportsForSeries(String orthancSeriesUid) {
        MedreportPrivileges.requireImagingView();
        return dao.getReportsForSeries(orthancSeriesUid, MedreportPrivileges.isReportAdmin());
    }

    public List<ImageReport> getReportsForPatient(Patient patient) {
        MedreportPrivileges.requireImagingView();
        return dao.getReportsForPatient(patient, MedreportPrivileges.isReportAdmin());
    }

    public ImageReportVersion getCurrentVersion(ImageReport report) {
        MedreportPrivileges.requireImagingView();
        if (report == null) {
            return null;
        }
        ImageReportVersion current = report.getCurrentVersion();
        return current != null ? current : dao.getCurrentVersion(report);
    }

    public byte[] getDocument(String versionUuid) {
        MedreportPrivileges.requireImagingView();

        ImageReportVersion version = dao.getVersionByUuid(versionUuid);
        if (version == null) {
            return null;
        }
        ImageReport report = version.getReport();

        if (report.isVoided() && !MedreportPrivileges.isReportAdmin()) {
            auditService.logFailure(MedreportConstants.ACTION_DENIED, report,
                    "Attempted to download the document of a removed report.");
            return null;
        }
        // Reaching a superseded version's document is a history read, which is
        // administrator-only (RP4) - otherwise the version chain would be readable by
        // anyone who guessed a version uuid.
        if (!version.isCurrent() && !MedreportPrivileges.isReportAdmin()) {
            auditService.logFailure(MedreportConstants.ACTION_DENIED, report,
                    "Attempted to download superseded version " + version.getVersionNumber()
                            + " without " + MedreportPrivileges.APP_ADMIN + ".");
            throw new APIAuthenticationException(
                    "Requires privilege: " + MedreportPrivileges.APP_ADMIN);
        }

        Obs obs = version.getDocumentObs();
        if (obs == null) {
            return null;
        }
        Obs complex = Context.getObsService().getComplexObs(obs.getObsId(), "RAW_VIEW");
        if (complex == null || complex.getComplexData() == null) {
            return null;
        }
        Object data = complex.getComplexData().getData();
        return data instanceof byte[] ? (byte[]) data : null;
    }

    // ==================================================================
    // Update (RP3, RP8)
    // ==================================================================

    public ImageReport updateReport(String reportUuid, String title, String observationText,
                                    List<ReportImageLink> images, String changeReason) {
        MedreportPrivileges.requireImagingManage();

        ImageReport report = dao.getReportByUuid(reportUuid);
        if (report == null) {
            throw new APIException("No report with uuid " + reportUuid);
        }
        if (report.isVoided()) {
            auditService.logFailure(MedreportConstants.ACTION_UPDATE, report,
                    "Attempted to edit a removed report.");
            throw new APIException("This report has been removed and cannot be edited. "
                    + "An administrator must restore it first.");
        }
        requireAuthorship(report, MedreportConstants.ACTION_UPDATE);

        if (observationText == null || observationText.trim().isEmpty()) {
            throw new APIException("A report cannot be empty.");
        }

        Date now = new Date();
        ImageReportVersion previous = dao.getCurrentVersion(report);

        // Exactly one version is ever current (RP8): clear the flag before setting the new one.
        dao.clearCurrentFlag(report);

        ImageReportVersion version = newVersion(report, MedreportConstants.CHANGE_UPDATE, previous, now);
        version.setVersionNumber(dao.getNextVersionNumber(report));
        version.setTitle(trimToNull(title));
        version.setObservationText(observationText);
        version.setChangeReason(trimToNull(changeReason));
        // A null image list means "leave coverage alone"; the previous version's set is
        // copied rather than shared, so the archived version keeps its own rows (RP8, RP9).
        attachImages(version, images != null && !images.isEmpty()
                ? images : copyOf(previous));
        dao.saveVersion(version);

        storeRenderedDocument(report, version);

        report.setCurrentVersion(version);
        dao.saveReport(report);

        auditService.log(MedreportConstants.ACTION_UPDATE, report, version,
                "Updated to version " + version.getVersionNumber()
                        + (previous != null ? " (previous: v" + previous.getVersionNumber() + ")" : "")
                        + ". Images: " + describeImages(version)
                        + (changeReason != null ? ". Reason: " + changeReason : ""));
        return report;
    }

    // ==================================================================
    // Remove (RP5)
    // ==================================================================

    public void removeReport(String reportUuid, String reason) {
        MedreportPrivileges.requireImagingManage();

        ImageReport report = dao.getReportByUuid(reportUuid);
        if (report == null) {
            throw new APIException("No report with uuid " + reportUuid);
        }
        if (report.isVoided()) {
            return; // already removed; removing twice is a no-op, not an error
        }
        requireAuthorship(report, MedreportConstants.ACTION_REMOVE);

        Date now = new Date();
        ImageReportVersion previous = dao.getCurrentVersion(report);

        // The removal is itself a version, so the chain records who removed it and when
        // (RP8). It carries the previous content forward so every version stays
        // self-describing, and so a later restore has something to restore to.
        dao.clearCurrentFlag(report);
        ImageReportVersion version = newVersion(report, MedreportConstants.CHANGE_REMOVE, previous, now);
        version.setVersionNumber(dao.getNextVersionNumber(report));
        version.setChangeReason(trimToNull(reason));
        if (previous != null) {
            version.setTitle(previous.getTitle());
            version.setObservationText(previous.getObservationText());
            version.setDocumentObs(previous.getDocumentObs());
            version.setDocumentFilename(previous.getDocumentFilename());
            attachImages(version, copyOf(previous));
        }
        dao.saveVersion(version);

        report.setVoided(true);
        report.setVoidedBy(Context.getAuthenticatedUser());
        report.setDateVoided(now);
        report.setVoidReason(trimToNull(reason));
        report.setCurrentVersion(version);
        dao.saveReport(report);

        // Void the stored document too, so a removed report's .docx also stops appearing
        // wherever else OpenMRS surfaces observations. Voided, never purged.
        voidDocument(previous, reason);

        auditService.log(MedreportConstants.ACTION_REMOVE, report, version,
                "Soft-deleted. The report and its " + version.getVersionNumber()
                        + " version(s) remain stored."
                        + (reason != null ? " Reason: " + reason : ""));
    }

    // ==================================================================
    // Administrator (RP4)
    // ==================================================================

    public List<ImageReportVersion> getVersionHistory(String reportUuid) {
        // Not requireImagingView: history is a strictly higher capability, and an
        // administrator browsing it is a distinct, audited event.
        ImageReport report = dao.getReportByUuid(reportUuid);
        if (!MedreportPrivileges.isReportAdmin()) {
            auditService.logFailure(MedreportConstants.ACTION_VIEW_HISTORY, report,
                    "Attempted to read the version history without " + MedreportPrivileges.APP_ADMIN + ".");
            throw new APIAuthenticationException(
                    "Requires privilege: " + MedreportPrivileges.APP_ADMIN);
        }
        if (report == null) {
            return Collections.emptyList();
        }
        List<ImageReportVersion> versions = dao.getVersions(report);
        auditService.log(MedreportConstants.ACTION_VIEW_HISTORY, report, null,
                "Read the full version history (" + versions.size() + " version(s)).");
        return versions;
    }

    public ImageReport restoreReport(String reportUuid, String reason) {
        ImageReport report = dao.getReportByUuid(reportUuid);
        if (!MedreportPrivileges.isReportAdmin()) {
            auditService.logFailure(MedreportConstants.ACTION_RESTORE, report,
                    "Attempted to restore a report without " + MedreportPrivileges.APP_ADMIN + ".");
            throw new APIAuthenticationException(
                    "Requires privilege: " + MedreportPrivileges.APP_ADMIN);
        }
        if (report == null) {
            throw new APIException("No report with uuid " + reportUuid);
        }
        if (!report.isVoided()) {
            return report;
        }

        Date now = new Date();
        ImageReportVersion previous = dao.getCurrentVersion(report);

        dao.clearCurrentFlag(report);
        ImageReportVersion version = newVersion(report, MedreportConstants.CHANGE_RESTORE, previous, now);
        version.setVersionNumber(dao.getNextVersionNumber(report));
        version.setChangeReason(trimToNull(reason));
        if (previous != null) {
            version.setTitle(previous.getTitle());
            version.setObservationText(previous.getObservationText());
            version.setDocumentObs(previous.getDocumentObs());
            version.setDocumentFilename(previous.getDocumentFilename());
            attachImages(version, copyOf(previous));
        }
        dao.saveVersion(version);

        report.setVoided(false);
        report.setVoidedBy(null);
        report.setDateVoided(null);
        report.setVoidReason(null);
        report.setCurrentVersion(version);
        dao.saveReport(report);

        unvoidDocument(version);

        auditService.log(MedreportConstants.ACTION_RESTORE, report, version,
                "Restored by an administrator." + (reason != null ? " Reason: " + reason : ""));
        return report;
    }

    // ==================================================================
    // UI helpers (RP6, presentation half)
    // ==================================================================

    public boolean canEdit(ImageReport report) {
        return report != null && !report.isVoided()
                && MedreportPrivileges.canManageImaging()
                && report.isAuthoredBy(Context.getAuthenticatedUser());
    }

    public boolean canRemove(ImageReport report) {
        return canEdit(report);
    }

    public boolean canViewHistory() {
        return MedreportPrivileges.isReportAdmin();
    }

    // ==================================================================
    // Internals
    // ==================================================================

    /**
     * The ownership half of RP3/RP5. Holding {@code medreport.imaging.manage} lets a user
     * write reports; it does not let them touch someone else's. Refusals are audited, since
     * an attempt to edit another clinician's report is exactly what the log is for.
     */
    private void requireAuthorship(ImageReport report, String action) {
        User current = Context.getAuthenticatedUser();
        if (!report.isAuthoredBy(current)) {
            String authorName = report.getAuthor() != null
                    ? report.getAuthor().getUsername() : "unknown";
            auditService.logFailure(action, report,
                    "Attempted to " + action.toLowerCase() + " a report authored by " + authorName + ".");
            throw new APIAuthenticationException(
                    "Only the author of a report may " + action.toLowerCase() + " it.");
        }
    }

    private ImageReportVersion newVersion(ImageReport report, String changeType,
                                          ImageReportVersion previous, Date now) {
        ImageReportVersion version = new ImageReportVersion();
        version.setUuid(UUID.randomUUID().toString());
        version.setReport(report);
        version.setChangeType(changeType);
        version.setCreatedBy(Context.getAuthenticatedUser());
        version.setDateCreated(now);
        version.setPreviousVersion(previous);
        version.setCurrent(true);
        return version;
    }

    private void attachImages(ImageReportVersion version, List<ReportImageLink> images) {
        if (images == null) {
            return;
        }
        int weight = 0;
        for (ReportImageLink source : images) {
            if (source == null || source.getOrthancStudyUid() == null
                    || source.getOrthancStudyUid().trim().isEmpty()) {
                continue;
            }
            ReportImageLink link = new ReportImageLink();
            link.setOrthancStudyUid(source.getOrthancStudyUid().trim());
            link.setOrthancSeriesUid(trimToNull(source.getOrthancSeriesUid()));
            link.setStudyInstanceUid(trimToNull(source.getStudyInstanceUid()));
            link.setModality(trimToNull(source.getModality()));
            link.setStudyDate(trimToNull(source.getStudyDate()));
            link.setStudyDescription(trimToNull(source.getStudyDescription()));
            link.setSortWeight(weight++);
            version.addImage(link);
        }
    }

    /** Fresh rows rather than a shared list, so each archived version keeps its own coverage. */
    private List<ReportImageLink> copyOf(ImageReportVersion previous) {
        if (previous == null) {
            return Collections.emptyList();
        }
        return new ArrayList<ReportImageLink>(previous.getImages());
    }

    private String describeImages(ImageReportVersion version) {
        StringBuilder builder = new StringBuilder();
        for (ReportImageLink link : version.getImages()) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(link.getOrthancSeriesUid() != null
                    ? link.getOrthancStudyUid() + "/" + link.getOrthancSeriesUid()
                    : link.getOrthancStudyUid());
        }
        return builder.length() > 0 ? builder.toString() : "none";
    }

    /**
     * Render the version's .docx and keep it as a Complex Obs.
     *
     * <p>Rendering is best-effort on purpose: the clinician's text is the clinical record and
     * is already committed by this point, so a rendering service that is down must not lose
     * it. The report is stored, the failure is logged and audited, and the document can be
     * regenerated by a later edit.
     */
    private void storeRenderedDocument(ImageReport report, ImageReportVersion version) {
        try {
            DocumentContext context = buildContext(report, version);
            String template = Context.getAdministrationService().getGlobalProperty(
                    MedreportConstants.GP_IMAGING_TEMPLATE,
                    MedreportConstants.IMAGING_TEMPLATE_DEFAULT);
            RenderedDocument rendered = renderClient.render(context, template, "docx", false,
                    "rapport_imagerie_v" + version.getVersionNumber());

            byte[] bytes = renderClient.download(rendered.getArtifactId());
            renderClient.discard(rendered.getArtifactId());

            Obs obs = saveComplexObs(report, version, rendered.getFilename(), bytes);
            version.setDocumentObs(obs);
            version.setDocumentFilename(rendered.getFilename());
            dao.saveVersion(version);
        } catch (RenderException e) {
            log.error("medreport: report " + report.getUuid() + " v" + version.getVersionNumber()
                    + " was saved but its document could not be rendered.", e);
            auditService.logFailure(MedreportConstants.ACTION_GENERATE, report,
                    "Report text saved, but document rendering failed: " + e.getMessage());
        } catch (Exception e) {
            log.error("medreport: unexpected failure storing the document for report "
                    + report.getUuid(), e);
            auditService.logFailure(MedreportConstants.ACTION_GENERATE, report,
                    "Report text saved, but the document could not be stored: " + e.getMessage());
        }
    }

    private DocumentContext buildContext(ImageReport report, ImageReportVersion version) {
        DocumentContext context = new DocumentContext();
        context.setLanguage(Context.getAdministrationService().getGlobalProperty(
                MedreportConstants.GP_DEFAULT_LANGUAGE, MedreportConstants.DEFAULT_LANGUAGE));
        context.setFacility(Context.getAdministrationService().getGlobalProperty(
                MedreportConstants.GP_FACILITY_NAME, MedreportConstants.FACILITY_NAME_DEFAULT));
        context.setDepartment(Context.getAdministrationService().getGlobalProperty(
                MedreportConstants.GP_DEPARTMENT_NAME, MedreportConstants.DEPARTMENT_NAME_DEFAULT));
        context.setTitle(version.getTitle() != null ? version.getTitle()
                : "Compte rendu d'imagerie");
        context.setReference(report.getUuid() + " · v" + version.getVersionNumber());
        context.setGeneratedAt(formatDateTime(version.getDateCreated()));

        Patient patient = report.getPatient();
        DocumentContext.PatientRef patientRef = new DocumentContext.PatientRef();
        if (patient != null) {
            patientRef.setFamilyName(nullSafe(patient.getFamilyName()));
            patientRef.setGivenName(nullSafe(patient.getGivenName()));
            patientRef.setGender(patient.getGender());
            patientRef.setBirthdate(formatDate(patient.getBirthdate()));
            patientRef.setAge(patient.getAge() != null ? String.valueOf(patient.getAge()) : null);
            if (patient.getPatientIdentifier() != null) {
                patientRef.setIdentifier(patient.getPatientIdentifier().getIdentifier());
            }
        }
        context.setPatient(patientRef);
        // RP2: the author is part of the document itself, not only of the on-screen list.
        context.setAuthor(new DocumentContext.AuthorRef(displayName(report.getAuthor())));

        List<DocumentContext.ImageObservation> observations =
                new ArrayList<DocumentContext.ImageObservation>();
        for (ReportImageLink link : version.getImages()) {
            DocumentContext.ImageObservation observation = new DocumentContext.ImageObservation();
            observation.setImageLabel(link.getDisplayLabel());
            observation.setStudyUid(link.getOrthancStudyUid());
            observation.setSeriesUid(link.getOrthancSeriesUid());
            observation.setModality(link.getModality());
            observation.setStudyDate(link.getStudyDate());
            observation.setAuthor(displayName(report.getAuthor()));
            observation.setObservedAt(formatDateTime(version.getDateCreated()));
            observation.setText(version.getObservationText());
            observations.add(observation);
        }
        context.setImageObservations(observations);
        return context;
    }

    /**
     * Persist the rendered document against the patient.
     *
     * <p>Runs with proxy privileges for the core obs/encounter privileges. The user has
     * already been checked for {@code medreport.imaging.manage}, which is this module's
     * actual authorization decision; requiring them to additionally hold core's "Add
     * Observations" would mean the module's own privilege did not really grant what it says.
     * The elevation is narrow and released in a finally.
     */
    private Obs saveComplexObs(ImageReport report, ImageReportVersion version,
                               String filename, byte[] content) {
        Context.addProxyPrivilege(PrivilegeConstants.ADD_OBS);
        Context.addProxyPrivilege(PrivilegeConstants.EDIT_OBS);
        Context.addProxyPrivilege(PrivilegeConstants.GET_OBS);
        try {
            Concept concept = resolveDocumentConcept();
            if (concept == null) {
                log.warn("medreport: the report-document concept is missing; "
                        + "the document will not be stored as a complex obs.");
                return null;
            }
            Obs obs = new Obs();
            obs.setPerson(report.getPatient());
            obs.setConcept(concept);
            obs.setObsDatetime(version.getDateCreated());
            obs.setEncounter(report.getEncounter());
            obs.setComment("medreport imaging report " + report.getUuid()
                    + " v" + version.getVersionNumber());
            obs.setComplexData(new ComplexData(filename, content));
            return Context.getObsService().saveObs(obs, null);
        } finally {
            Context.removeProxyPrivilege(PrivilegeConstants.ADD_OBS);
            Context.removeProxyPrivilege(PrivilegeConstants.EDIT_OBS);
            Context.removeProxyPrivilege(PrivilegeConstants.GET_OBS);
        }
    }

    private void voidDocument(ImageReportVersion version, String reason) {
        if (version == null || version.getDocumentObs() == null) {
            return;
        }
        Context.addProxyPrivilege(PrivilegeConstants.EDIT_OBS);
        try {
            Context.getObsService().voidObs(version.getDocumentObs(),
                    reason != null ? reason : "medreport: report removed by its author");
        } catch (Exception e) {
            log.warn("medreport: could not void the document obs of a removed report.", e);
        } finally {
            Context.removeProxyPrivilege(PrivilegeConstants.EDIT_OBS);
        }
    }

    private void unvoidDocument(ImageReportVersion version) {
        if (version == null || version.getDocumentObs() == null) {
            return;
        }
        Context.addProxyPrivilege(PrivilegeConstants.EDIT_OBS);
        try {
            Context.getObsService().unvoidObs(version.getDocumentObs());
        } catch (Exception e) {
            log.warn("medreport: could not unvoid the document obs of a restored report.", e);
        } finally {
            Context.removeProxyPrivilege(PrivilegeConstants.EDIT_OBS);
        }
    }

    private Concept resolveDocumentConcept() {
        String uuid = Context.getAdministrationService().getGlobalProperty(
                MedreportConstants.GP_IMAGE_REPORT_CONCEPT_UUID,
                MedreportConstants.IMAGE_REPORT_CONCEPT_UUID);
        Context.addProxyPrivilege(PrivilegeConstants.GET_CONCEPTS);
        try {
            return Context.getConceptService().getConceptByUuid(uuid);
        } finally {
            Context.removeProxyPrivilege(PrivilegeConstants.GET_CONCEPTS);
        }
    }

    /**
     * Best-effort encounter for the report (ADR-6). An install without the encounter type, a
     * default location, or the core privileges still gets a working report - the obs is
     * simply patient-level.
     */
    private Encounter resolveEncounter(Patient patient, Date when) {
        Context.addProxyPrivilege(PrivilegeConstants.ADD_ENCOUNTERS);
        Context.addProxyPrivilege(PrivilegeConstants.GET_ENCOUNTERS);
        Context.addProxyPrivilege(PrivilegeConstants.GET_LOCATIONS);
        try {
            String uuid = Context.getAdministrationService().getGlobalProperty(
                    MedreportConstants.GP_IMAGE_REPORT_ENCOUNTER_TYPE_UUID,
                    MedreportConstants.IMAGE_REPORT_ENCOUNTER_TYPE_UUID);
            EncounterType type = Context.getEncounterService().getEncounterTypeByUuid(uuid);
            if (type == null) {
                return null;
            }
            Encounter encounter = new Encounter();
            encounter.setPatient(patient);
            encounter.setEncounterType(type);
            encounter.setEncounterDatetime(when);
            encounter.setLocation(Context.getLocationService().getDefaultLocation());
            return Context.getEncounterService().saveEncounter(encounter);
        } catch (Exception e) {
            log.warn("medreport: could not create an encounter for the report; "
                    + "the document will be stored against the patient only.", e);
            return null;
        } finally {
            Context.removeProxyPrivilege(PrivilegeConstants.ADD_ENCOUNTERS);
            Context.removeProxyPrivilege(PrivilegeConstants.GET_ENCOUNTERS);
            Context.removeProxyPrivilege(PrivilegeConstants.GET_LOCATIONS);
        }
    }

    private static String displayName(User user) {
        if (user == null) {
            return "";
        }
        if (user.getPersonName() != null) {
            String name = user.getPersonName().getFullName();
            if (name != null && !name.trim().isEmpty()) {
                return name;
            }
        }
        return user.getUsername() != null ? user.getUsername() : user.getSystemId();
    }

    private static String formatDate(Date date) {
        return date == null ? null : new SimpleDateFormat("dd/MM/yyyy").format(date);
    }

    private static String formatDateTime(Date date) {
        return date == null ? null : new SimpleDateFormat("dd/MM/yyyy HH:mm").format(date);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
