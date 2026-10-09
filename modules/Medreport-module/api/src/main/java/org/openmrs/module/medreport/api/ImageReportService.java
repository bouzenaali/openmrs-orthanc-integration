package org.openmrs.module.medreport.api;

import org.openmrs.Patient;
import org.openmrs.annotation.Authorized;
import org.openmrs.api.OpenmrsService;
import org.openmrs.module.medreport.MedreportPrivileges;
import org.openmrs.module.medreport.api.model.ImageReport;
import org.openmrs.module.medreport.api.model.ImageReportVersion;
import org.openmrs.module.medreport.api.model.ReportImageLink;

import java.util.List;

/**
 * Use case 1: per-image/study observation reports.
 *
 * <p>This service is the sole owner of imaging-report behaviour. The imaging module holds no
 * report logic at all - it only renders a tab that calls medreport's REST resources for the
 * study currently on screen.
 *
 * <h3>The access rules it enforces</h3>
 * <table>
 *   <tr><td>RP1</td><td>create requires {@code medreport.imaging.manage}</td></tr>
 *   <tr><td>RP2</td><td>read - anyone's report - requires {@code medreport.imaging.view}; the author is always shown</td></tr>
 *   <tr><td>RP3</td><td>update requires {@code manage} <em>and</em> authorship; archives a new version</td></tr>
 *   <tr><td>RP4</td><td>full version history requires {@code medreport.admin}; everyone else, author included, sees only the current version</td></tr>
 *   <tr><td>RP5</td><td>remove requires {@code manage} <em>and</em> authorship; soft delete only</td></tr>
 *   <tr><td>RP7</td><td>every action, including refused ones, is written to the audit log</td></tr>
 *   <tr><td>RP8</td><td>every transition produces a new immutable version; exactly one is current</td></tr>
 * </table>
 *
 * <p>Every method re-checks its privilege here, in the service, independently of whatever the
 * UI decided to show (RP6). The {@code @Authorized} annotations are a second, weaker layer
 * for non-Reference-Application distributions - never the guard.
 */
public interface ImageReportService extends OpenmrsService {

    // -- create ----------------------------------------------------------

    /**
     * Write a new observation report over one or more images (RP1, RP9).
     *
     * <p>The text is stored verbatim and rendered as-is into a .docx, which is kept as a
     * Complex Obs against the patient.
     *
     * @param images at least one; a report may cover several studies or series
     * @throws org.openmrs.api.APIAuthenticationException without {@code medreport.imaging.manage}
     */
    @Authorized({ MedreportPrivileges.MANAGE_MEDICAL_REPORTS })
    ImageReport createReport(Patient patient, String title, String observationText,
                             List<ReportImageLink> images);

    // -- read ------------------------------------------------------------

    /**
     * @return the report, or null if there is none with that uuid. A soft-deleted report is
     *         returned only to an administrator (RP5).
     */
    @Authorized({ MedreportPrivileges.VIEW_MEDICAL_REPORTS })
    ImageReport getReport(String reportUuid);

    /** Reports whose current version covers this Orthanc study (RP2, RP9). */
    @Authorized({ MedreportPrivileges.VIEW_MEDICAL_REPORTS })
    List<ImageReport> getReportsForStudy(String orthancStudyUid);

    /** Reports whose current version covers this Orthanc series (RP2, RP9). */
    @Authorized({ MedreportPrivileges.VIEW_MEDICAL_REPORTS })
    List<ImageReport> getReportsForSeries(String orthancSeriesUid);

    @Authorized({ MedreportPrivileges.VIEW_MEDICAL_REPORTS })
    List<ImageReport> getReportsForPatient(Patient patient);

    /** The single well-defined version a non-admin is shown (RP8). */
    @Authorized({ MedreportPrivileges.VIEW_MEDICAL_REPORTS })
    ImageReportVersion getCurrentVersion(ImageReport report);

    /** The rendered document of a version, for download. */
    @Authorized({ MedreportPrivileges.VIEW_MEDICAL_REPORTS })
    byte[] getDocument(String versionUuid);

    // -- update / remove --------------------------------------------------

    /**
     * Replace the observation text and/or the covered images, archiving the current state as
     * a new immutable version first (RP3, RP8).
     *
     * @param images null leaves the covered images unchanged
     * @throws org.openmrs.api.APIAuthenticationException without the manage privilege, or when
     *         the current user is not the report's author
     */
    @Authorized({ MedreportPrivileges.MANAGE_MEDICAL_REPORTS })
    ImageReport updateReport(String reportUuid, String title, String observationText,
                             List<ReportImageLink> images, String changeReason);

    /**
     * Soft-delete a report (RP5). It disappears for every other user, while the report, all
     * its versions and their documents stay in the database. Nothing is physically deleted.
     */
    @Authorized({ MedreportPrivileges.MANAGE_MEDICAL_REPORTS })
    void removeReport(String reportUuid, String reason);

    // -- administrator ----------------------------------------------------

    /**
     * The full version chain, oldest first (RP4). Administrator only - the report's own
     * author sees only the current version. The access itself is audited.
     */
    @Authorized({ MedreportPrivileges.VIEW_MEDICAL_REPORTS })
    List<ImageReportVersion> getVersionHistory(String reportUuid);

    /** Reverse a soft delete. Administrator only; appends a RESTORE version. */
    @Authorized({ MedreportPrivileges.MANAGE_MEDICAL_REPORTS })
    ImageReport restoreReport(String reportUuid, String reason);

    // -- UI helpers (RP6, presentation half) -------------------------------

    /**
     * Whether the current user may edit this report. Used to disable the button; the server
     * re-checks the same rule on the request itself, so this is convenience, never the guard.
     */
    boolean canEdit(ImageReport report);

    boolean canRemove(ImageReport report);

    boolean canViewHistory();
}
