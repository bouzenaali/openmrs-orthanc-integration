package org.openmrs.module.medreport.api;

import org.openmrs.Patient;
import org.openmrs.annotation.Authorized;
import org.openmrs.api.OpenmrsService;
import org.openmrs.module.medreport.MedreportPrivileges;
import org.openmrs.module.medreport.api.render.RenderedDocument;
import org.openmrs.module.medreport.api.report.ReportRequest;

import java.util.List;
import java.util.Map;

/**
 * Use case 2: the personalised full patient report.
 *
 * <p>Assembles a document from whatever the clinician selected in the personalisation window,
 * drawing on every module that has announced clinical data (see
 * {@code api.catalog.DataSourceRegistry}), and renders it through the Report Generation
 * Service.
 *
 * <h3>Privilege coherence</h3>
 * A user who cannot view a datum cannot put it in a report. That is enforced in two places
 * with the same rule: {@link #getCatalog} omits sets and fields the user lacks the privilege
 * for, so they never appear as a checkbox; and {@link #generate} re-applies the identical
 * filter to the incoming selection, so posting an id directly to the REST endpoint gets the
 * caller nothing. Data is also fetched through each contributor's own service, so the
 * contributor's access rules still apply on top.
 */
public interface PatientReportService extends OpenmrsService {

    /**
     * The personalisation tree: every set and field the <em>current user</em> may include,
     * with labels in {@code language}. Sets the user cannot view are absent, not disabled -
     * their existence is not disclosed.
     */
    @Authorized({ MedreportPrivileges.VIEW_MEDICAL_REPORTS })
    List<Map<String, Object>> getCatalog(String language);

    /** Template profiles offered by the rendering service, for the template picker. */
    List<Map<String, Object>> getTemplates();

    /** Whether the rendering service is reachable, and which formats it can produce. */
    Map<String, Object> getRenderServiceHealth();

    /**
     * Build and render the report. The returned artifact can be previewed and then
     * downloaded; it expires on the rendering service after a short retention window.
     */
    @Authorized({ MedreportPrivileges.VIEW_MEDICAL_REPORTS })
    RenderedDocument generate(Patient patient, ReportRequest request);

    byte[] download(String artifactId);

    byte[] preview(String artifactId);

    // -- preferences -------------------------------------------------------

    /**
     * The current user's saved choices, or sensible defaults on first use. Always the
     * authenticated user's own row; there is no way to read another user's preferences.
     */
    ReportRequest getPreferences();

    /** Persist the current user's choices so the next report starts from them. */
    ReportRequest savePreferences(ReportRequest request);
}
