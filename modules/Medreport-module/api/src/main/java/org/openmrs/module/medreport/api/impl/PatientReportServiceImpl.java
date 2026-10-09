package org.openmrs.module.medreport.api.impl;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.Patient;
import org.openmrs.User;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.api.impl.BaseOpenmrsService;
import org.openmrs.module.medreport.MedreportConstants;
import org.openmrs.module.medreport.MedreportPrivileges;
import org.openmrs.module.medreport.api.ImageReportService;
import org.openmrs.module.medreport.api.MedreportAuditService;
import org.openmrs.module.medreport.api.PatientReportService;
import org.openmrs.module.medreport.api.catalog.DataSourceRegistry;
import org.openmrs.module.medreport.api.dao.MedreportDao;
import org.openmrs.module.medreport.api.model.ImageReport;
import org.openmrs.module.medreport.api.model.ImageReportVersion;
import org.openmrs.module.medreport.api.model.ReportImageLink;
import org.openmrs.module.medreport.api.model.UserReportPreference;
import org.openmrs.module.medreport.api.render.DocumentContext;
import org.openmrs.module.medreport.api.render.RenderedDocument;
import org.openmrs.module.medreport.api.render.ReportRenderClient;
import org.openmrs.module.medreport.api.report.ReportRequest;
import org.openmrs.module.medreport.api.spi.DataSourceDescriptor;
import org.openmrs.module.medreport.api.spi.FieldDescriptor;
import org.openmrs.module.medreport.api.spi.SectionDescriptor;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class PatientReportServiceImpl extends BaseOpenmrsService implements PatientReportService {

    private static final Log log = LogFactory.getLog(PatientReportServiceImpl.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final List<String> SUPPORTED_LANGUAGES = Arrays.asList("fr", "en", "ar");

    private static final List<String> SUPPORTED_FORMATS = Arrays.asList("pdf", "docx", "html", "odt");

    private MedreportDao dao;

    private DataSourceRegistry registry;

    private ReportRenderClient renderClient;

    private MedreportAuditService auditService;

    private ImageReportService imageReportService;

    public void setDao(MedreportDao dao) {
        this.dao = dao;
    }

    public void setRegistry(DataSourceRegistry registry) {
        this.registry = registry;
    }

    public void setRenderClient(ReportRenderClient renderClient) {
        this.renderClient = renderClient;
    }

    public void setAuditService(MedreportAuditService auditService) {
        this.auditService = auditService;
    }

    public void setImageReportService(ImageReportService imageReportService) {
        this.imageReportService = imageReportService;
    }

    // ==================================================================
    // Catalogue
    // ==================================================================

    public List<Map<String, Object>> getCatalog(String language) {
        MedreportPrivileges.requireDashboardGenerate();
        String lang = normaliseLanguage(language);

        List<Map<String, Object>> catalogue = new ArrayList<Map<String, Object>>();
        for (DataSourceDescriptor descriptor : registry.getDescriptors()) {
            // A whole contribution the user cannot read is omitted rather than shown
            // disabled: a greyed-out "Oncologie" set would itself disclose something.
            if (!hasPrivilege(descriptor.getRequiredPrivilege())) {
                continue;
            }
            List<Map<String, Object>> sections = describeSections(descriptor.getSections(), lang);
            if (sections.isEmpty()) {
                continue;
            }
            Map<String, Object> group = new LinkedHashMap<String, Object>();
            group.put("id", descriptor.getId());
            group.put("module", descriptor.getModuleId());
            group.put("label", descriptor.getLabel(lang));
            group.put("sections", sections);
            catalogue.add(group);
        }

        // The imaging observations "set" is medreport's own, and follows the same rule: it
        // only appears for a user who may read imaging reports (RP2).
        if (MedreportPrivileges.canViewImaging()) {
            Map<String, Object> group = new LinkedHashMap<String, Object>();
            group.put("id", "medreport.imaging");
            group.put("module", "medreport");
            group.put("label", imagingLabel(lang));
            Map<String, Object> section = new LinkedHashMap<String, Object>();
            section.put("id", "medreport.imaging.observations");
            section.put("label", imagingLabel(lang));
            section.put("description", imagingDescription(lang));
            section.put("repeating", true);
            section.put("defaultSelected", true);
            section.put("fields", Collections.emptyList());
            section.put("subsections", Collections.emptyList());
            group.put("sections", Collections.singletonList(section));
            catalogue.add(group);
        }
        return catalogue;
    }

    private List<Map<String, Object>> describeSections(List<SectionDescriptor> sections, String lang) {
        List<Map<String, Object>> described = new ArrayList<Map<String, Object>>();
        for (SectionDescriptor section : sections) {
            if (!hasPrivilege(section.getRequiredPrivilege())) {
                continue;
            }
            List<Map<String, Object>> fields = new ArrayList<Map<String, Object>>();
            for (FieldDescriptor field : section.getFields()) {
                if (!hasPrivilege(field.getRequiredPrivilege())) {
                    continue;
                }
                Map<String, Object> described_field = new LinkedHashMap<String, Object>();
                described_field.put("id", field.getId());
                described_field.put("label", field.getLabel(lang));
                described_field.put("type", field.getType());
                described_field.put("unit", field.getUnit());
                described_field.put("defaultSelected", field.isDefaultSelected());
                fields.add(described_field);
            }
            List<Map<String, Object>> subsections = describeSections(section.getSubsections(), lang);
            if (fields.isEmpty() && subsections.isEmpty()) {
                continue;
            }
            Map<String, Object> described_section = new LinkedHashMap<String, Object>();
            described_section.put("id", section.getId());
            described_section.put("label", section.getLabel(lang));
            described_section.put("description", section.getDescription(lang));
            described_section.put("repeating", section.isRepeating());
            described_section.put("defaultSelected", section.isDefaultSelected());
            described_section.put("fields", fields);
            described_section.put("subsections", subsections);
            described.add(described_section);
        }
        return described;
    }

    public List<Map<String, Object>> getTemplates() {
        MedreportPrivileges.requireDashboardGenerate();
        try {
            return renderClient.listTemplates();
        } catch (Exception e) {
            log.warn("medreport: could not list templates from the rendering service.", e);
            return Collections.emptyList();
        }
    }

    public Map<String, Object> getRenderServiceHealth() {
        return renderClient.health();
    }

    // ==================================================================
    // Generation
    // ==================================================================

    public RenderedDocument generate(Patient patient, ReportRequest request) {
        MedreportPrivileges.requireDashboardGenerate();

        if (patient == null) {
            throw new APIException("A report needs a patient.");
        }
        if (request == null) {
            request = getPreferences();
        }

        String lang = normaliseLanguage(request.getLanguage());
        String format = normaliseFormat(request.getFormat());
        String template = request.getTemplate() != null && !request.getTemplate().trim().isEmpty()
                ? request.getTemplate().trim()
                : Context.getAdministrationService().getGlobalProperty(
                        MedreportConstants.GP_DEFAULT_TEMPLATE, MedreportConstants.DEFAULT_TEMPLATE);

        DocumentContext context = new DocumentContext();
        context.setLanguage(lang);
        context.setTitle(nonEmpty(request.getTitle(), defaultTitle(lang)));
        context.setSubtitle(nonEmpty(request.getSubtitle(), null));
        context.setFacility(Context.getAdministrationService().getGlobalProperty(
                MedreportConstants.GP_FACILITY_NAME, MedreportConstants.FACILITY_NAME_DEFAULT));
        context.setDepartment(Context.getAdministrationService().getGlobalProperty(
                MedreportConstants.GP_DEPARTMENT_NAME, MedreportConstants.DEPARTMENT_NAME_DEFAULT));
        context.setGeneratedAt(new SimpleDateFormat("dd/MM/yyyy HH:mm").format(new Date()));
        context.setPatient(patientRef(patient));
        context.setAuthor(new DocumentContext.AuthorRef(displayName(Context.getAuthenticatedUser())));

        DocumentContext.Options options = new DocumentContext.Options();
        options.setShowEmptyFields(request.isShowEmptyFields());
        options.setIncludeTableOfContents(request.isIncludeTableOfContents());
        options.setIncludeSignatureBlock(request.isIncludeSignatureBlock());
        options.setIncludePageNumbers(request.isIncludePageNumbers());
        options.setConfidentialityNotice(request.isConfidentialityNotice());
        context.setOptions(options);

        List<String> includedSections = new ArrayList<String>();
        List<String> refusedSections = new ArrayList<String>();

        for (DataSourceDescriptor descriptor : registry.getDescriptors()) {
            if (!hasPrivilege(descriptor.getRequiredPrivilege())) {
                // Silently skipped for the user, but recorded: a selection that had to be
                // dropped is worth being able to explain afterwards.
                if (touchesAny(request, descriptor)) {
                    refusedSections.add(descriptor.getId() + " (whole source)");
                }
                continue;
            }
            for (SectionDescriptor section : descriptor.getSections()) {
                DocumentContext.Section built = buildSection(
                        descriptor, section, patient, request, lang, refusedSections);
                if (built != null) {
                    context.getSections().add(built);
                    includedSections.add(section.getId());
                }
            }
        }

        if (request.isIncludeImageObservations()
                && request.isSectionSelected("medreport.imaging.observations")) {
            if (MedreportPrivileges.canViewImaging()) {
                appendImageObservations(context, patient);
                includedSections.add("medreport.imaging.observations");
            } else {
                refusedSections.add("medreport.imaging.observations");
            }
        }

        RenderedDocument rendered;
        try {
            rendered = renderClient.render(context, template, format, true,
                    "rapport_" + safeName(patient));
        } catch (Exception e) {
            auditService.logForPatient(MedreportConstants.ACTION_GENERATE,
                    patient.getPatientId(), false,
                    "Report generation failed: " + e.getMessage());
            throw e;
        }

        auditService.logForPatient(MedreportConstants.ACTION_GENERATE, patient.getPatientId(), true,
                "Generated a " + format + " report (template=" + template + ", language=" + lang
                        + ") covering: " + join(includedSections)
                        + (refusedSections.isEmpty() ? ""
                                : ". Refused for lack of privilege: " + join(refusedSections)));
        return rendered;
    }

    /**
     * Build one section of the document, applying selection <em>and</em> privilege.
     *
     * @return null when the section was not selected, is not permitted, or produced nothing
     */
    private DocumentContext.Section buildSection(DataSourceDescriptor descriptor,
                                                 SectionDescriptor section,
                                                 Patient patient,
                                                 ReportRequest request,
                                                 String lang,
                                                 List<String> refused) {
        boolean selected = request.isSectionSelected(section.getId());
        boolean anySubSelected = false;
        for (SectionDescriptor sub : section.getSubsections()) {
            if (request.isSectionSelected(sub.getId())) {
                anySubSelected = true;
                break;
            }
        }
        if (!selected && !anySubSelected) {
            return null;
        }

        // The same check the catalogue applied, applied again on the way in. The catalogue
        // decides what a user is offered; this decides what they actually get, and it is
        // this one that matters - the request could have been hand-crafted.
        if (!hasPrivilege(section.getRequiredPrivilege())) {
            refused.add(section.getId());
            return null;
        }

        DocumentContext.Section built = new DocumentContext.Section();
        built.setId(section.getId());
        built.setTitle(section.getLabel(lang));
        built.setDescription(section.getDescription(lang));

        if (selected) {
            List<Map<String, Object>> rows = registry.resolve(descriptor, section, patient);
            if (section.isRepeating()) {
                for (Map<String, Object> row : rows) {
                    DocumentContext.Record record = new DocumentContext.Record();
                    record.setTitle(recordTitle(section, row));
                    record.setFields(buildFields(section, row, request, lang));
                    if (!record.getFields().isEmpty()) {
                        built.getRecords().add(record);
                    }
                }
            } else if (!rows.isEmpty()) {
                built.setFields(buildFields(section, rows.get(0), request, lang));
            } else if (request.isShowEmptyFields()) {
                built.setFields(buildFields(section, Collections.<String, Object>emptyMap(),
                        request, lang));
            }
        }

        for (SectionDescriptor sub : section.getSubsections()) {
            DocumentContext.Section builtSub =
                    buildSection(descriptor, sub, patient, request, lang, refused);
            if (builtSub != null) {
                built.getSubsections().add(builtSub);
            }
        }

        return built.isEmpty() ? null : built;
    }

    private List<DocumentContext.Field> buildFields(SectionDescriptor section,
                                                    Map<String, Object> row,
                                                    ReportRequest request,
                                                    String lang) {
        List<DocumentContext.Field> fields = new ArrayList<DocumentContext.Field>();
        for (FieldDescriptor descriptor : section.getFields()) {
            if (!request.isFieldSelected(section.getId(), descriptor.getId())) {
                continue;
            }
            if (!hasPrivilege(descriptor.getRequiredPrivilege())) {
                continue;
            }
            String value = format(row.get(descriptor.getSourceKey()));
            if (value == null && !request.isShowEmptyFields()) {
                continue;
            }
            DocumentContext.Field field = new DocumentContext.Field(
                    descriptor.getId(), descriptor.getLabel(lang), value, descriptor.getType());
            field.setUnit(descriptor.getUnit());
            field.setEmphasis(descriptor.isEmphasis());
            fields.add(field);
        }
        return fields;
    }

    /**
     * Fold this patient's imaging observations into the report as "image : what was
     * written about it", which is what makes the two use cases one document rather than two.
     * Only non-removed reports, and only for a user who may read them.
     */
    private void appendImageObservations(DocumentContext context, Patient patient) {
        List<ImageReport> reports;
        try {
            reports = imageReportService.getReportsForPatient(patient);
        } catch (Exception e) {
            log.warn("medreport: could not load imaging observations for the report.", e);
            return;
        }
        for (ImageReport report : reports) {
            ImageReportVersion version = report.getCurrentVersion();
            if (version == null || report.isVoided()) {
                continue;
            }
            String author = displayName(report.getAuthor());
            String observedAt = version.getDateCreated() != null
                    ? new SimpleDateFormat("dd/MM/yyyy HH:mm").format(version.getDateCreated())
                    : null;
            for (ReportImageLink link : version.getImages()) {
                DocumentContext.ImageObservation observation = new DocumentContext.ImageObservation();
                observation.setImageLabel(link.getDisplayLabel());
                observation.setStudyUid(link.getOrthancStudyUid());
                observation.setSeriesUid(link.getOrthancSeriesUid());
                observation.setModality(link.getModality());
                observation.setStudyDate(link.getStudyDate());
                observation.setAuthor(author);
                observation.setObservedAt(observedAt);
                observation.setText(version.getObservationText());
                context.getImageObservations().add(observation);
            }
        }
    }

    public byte[] download(String artifactId) {
        MedreportPrivileges.requireDashboardGenerate();
        return renderClient.download(artifactId);
    }

    public byte[] preview(String artifactId) {
        MedreportPrivileges.requireDashboardGenerate();
        return renderClient.preview(artifactId);
    }

    // ==================================================================
    // Preferences
    // ==================================================================

    public ReportRequest getPreferences() {
        User user = Context.getAuthenticatedUser();
        if (user == null) {
            return defaults();
        }
        UserReportPreference stored = dao.getPreference(user, MedreportConstants.PREFERENCE_REPORT_OPTIONS);
        if (stored == null || stored.getPreferenceValue() == null) {
            return defaults();
        }
        try {
            return MAPPER.readValue(stored.getPreferenceValue(), ReportRequest.class);
        } catch (Exception e) {
            // A preference that no longer parses (an old shape, a manual edit) must not
            // block report generation - fall back to defaults and let the user re-save.
            log.warn("medreport: stored report preferences for user " + user.getUsername()
                    + " could not be read; falling back to defaults.", e);
            return defaults();
        }
    }

    public ReportRequest savePreferences(ReportRequest request) {
        User user = Context.getAuthenticatedUser();
        if (user == null || request == null) {
            return request;
        }
        try {
            UserReportPreference preference =
                    dao.getPreference(user, MedreportConstants.PREFERENCE_REPORT_OPTIONS);
            Date now = new Date();
            if (preference == null) {
                preference = new UserReportPreference();
                preference.setUuid(UUID.randomUUID().toString());
                // Always the authenticated user: preferences are never written on someone
                // else's behalf, whatever the request body says.
                preference.setUser(user);
                preference.setPreferenceKey(MedreportConstants.PREFERENCE_REPORT_OPTIONS);
                preference.setDateCreated(now);
            }
            preference.setPreferenceValue(MAPPER.writeValueAsString(request));
            preference.setDateChanged(now);
            dao.savePreference(preference);
        } catch (Exception e) {
            log.warn("medreport: could not persist report preferences for user "
                    + user.getUsername() + ".", e);
        }
        return request;
    }

    private ReportRequest defaults() {
        ReportRequest request = new ReportRequest();
        request.setLanguage(Context.getAdministrationService().getGlobalProperty(
                MedreportConstants.GP_DEFAULT_LANGUAGE, MedreportConstants.DEFAULT_LANGUAGE));
        request.setFormat("pdf");
        request.setTemplate(Context.getAdministrationService().getGlobalProperty(
                MedreportConstants.GP_DEFAULT_TEMPLATE, MedreportConstants.DEFAULT_TEMPLATE));

        // First use pre-ticks everything the user is allowed to see, so the very first
        // report is one click and still complete.
        List<String> sections = new ArrayList<String>();
        for (DataSourceDescriptor descriptor : registry.getDescriptors()) {
            if (!hasPrivilege(descriptor.getRequiredPrivilege())) {
                continue;
            }
            collectDefaultSelected(descriptor.getSections(), sections);
        }
        if (MedreportPrivileges.canViewImaging()) {
            sections.add("medreport.imaging.observations");
        }
        request.setSections(sections);
        return request;
    }

    private void collectDefaultSelected(List<SectionDescriptor> sections, List<String> into) {
        for (SectionDescriptor section : sections) {
            if (!hasPrivilege(section.getRequiredPrivilege())) {
                continue;
            }
            if (section.isDefaultSelected()) {
                into.add(section.getId());
            }
            collectDefaultSelected(section.getSubsections(), into);
        }
    }

    // ==================================================================
    // Helpers
    // ==================================================================

    private boolean hasPrivilege(String privilege) {
        return privilege == null || privilege.trim().isEmpty() || Context.hasPrivilege(privilege);
    }

    private boolean touchesAny(ReportRequest request, DataSourceDescriptor descriptor) {
        for (SectionDescriptor section : descriptor.getSections()) {
            if (request.isSectionSelected(section.getId())) {
                return true;
            }
        }
        return false;
    }

    private String recordTitle(SectionDescriptor section, Map<String, Object> row) {
        if (section.getRecordTitleKey() != null) {
            String title = format(row.get(section.getRecordTitleKey()));
            if (title != null) {
                return title;
            }
        }
        return null;
    }

    /** Convert a contributor's raw value into display text; null means "not recorded". */
    private String format(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Date) {
            return new SimpleDateFormat("dd/MM/yyyy").format((Date) value);
        }
        if (value instanceof Boolean) {
            // Left as a token; the renderer localises it to Oui/Yes/نعم.
            return ((Boolean) value) ? "true" : "false";
        }
        if (value instanceof BigDecimal) {
            return ((BigDecimal) value).stripTrailingZeros().toPlainString();
        }
        String text = String.valueOf(value).trim();
        // Groovy/JDBC round-trips can leave the four-character string "null" in a column;
        // treat it as missing, the same guard patientview applies in its templates.
        if (text.isEmpty() || "null".equalsIgnoreCase(text)) {
            return null;
        }
        return text;
    }

    private DocumentContext.PatientRef patientRef(Patient patient) {
        DocumentContext.PatientRef ref = new DocumentContext.PatientRef();
        ref.setFamilyName(patient.getFamilyName() != null ? patient.getFamilyName() : "");
        ref.setGivenName(patient.getGivenName() != null ? patient.getGivenName() : "");
        ref.setGender(patient.getGender());
        if (patient.getBirthdate() != null) {
            ref.setBirthdate(new SimpleDateFormat("dd/MM/yyyy").format(patient.getBirthdate()));
        }
        if (patient.getAge() != null) {
            ref.setAge(String.valueOf(patient.getAge()));
        }
        if (patient.getPatientIdentifier() != null) {
            ref.setIdentifier(patient.getPatientIdentifier().getIdentifier());
        }
        return ref;
    }

    private String normaliseLanguage(String language) {
        if (language == null) {
            return MedreportConstants.DEFAULT_LANGUAGE;
        }
        String lang = language.trim().toLowerCase();
        return SUPPORTED_LANGUAGES.contains(lang) ? lang : MedreportConstants.DEFAULT_LANGUAGE;
    }

    private String normaliseFormat(String format) {
        if (format == null) {
            return "pdf";
        }
        String value = format.trim().toLowerCase();
        return SUPPORTED_FORMATS.contains(value) ? value : "pdf";
    }

    private String defaultTitle(String lang) {
        if ("en".equals(lang)) {
            return "Medical report";
        }
        if ("ar".equals(lang)) {
            return "التقرير الطبي";
        }
        return "Rapport médical";
    }

    private String imagingLabel(String lang) {
        if ("en".equals(lang)) {
            return "Imaging observations";
        }
        if ("ar".equals(lang)) {
            return "ملاحظات التصوير";
        }
        return "Observations sur imagerie";
    }

    private String imagingDescription(String lang) {
        if ("en".equals(lang)) {
            return "Free-text observations written about this patient's DICOM studies.";
        }
        if ("ar".equals(lang)) {
            return "ملاحظات نصية حول دراسات التصوير الخاصة بهذا المريض.";
        }
        return "Observations rédigées sur les études DICOM de ce patient.";
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

    private static String safeName(Patient patient) {
        String name = patient.getFamilyName() != null ? patient.getFamilyName() : "patient";
        return name.replaceAll("[^A-Za-z0-9]+", "_");
    }

    private static String nonEmpty(String value, String fallback) {
        return value != null && !value.trim().isEmpty() ? value.trim() : fallback;
    }

    private static String join(List<String> values) {
        if (values.isEmpty()) {
            return "none";
        }
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(value);
        }
        return builder.toString();
    }
}
