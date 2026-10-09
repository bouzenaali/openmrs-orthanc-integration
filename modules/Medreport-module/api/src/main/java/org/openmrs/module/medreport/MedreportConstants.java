package org.openmrs.module.medreport;

/**
 * Module-wide identifiers: global property names, defaults, and the fixed UUIDs this
 * module creates on first start.
 */
public final class MedreportConstants {

    public static final String MODULE_ID = "medreport";

    // -- Report Generation Service ------------------------------------------

    /** Base URL of the Report Generation Service, reachable on the private container network. */
    public static final String GP_RENDER_BASE_URL = "medreport.renderService.baseUrl";

    public static final String GP_RENDER_BASE_URL_DEFAULT = "http://medreport-rgs:8300";

    /**
     * Shared token presented to the Report Generation Service. Must match that service's
     * {@code MEDREPORT_RGS_TOKEN}. Stored as a global property so it is never in source
     * control; an empty value makes every render fail closed with a clear message.
     */
    public static final String GP_RENDER_TOKEN = "medreport.renderService.token";

    public static final String GP_RENDER_TIMEOUT_SECONDS = "medreport.renderService.timeoutSeconds";

    public static final int RENDER_TIMEOUT_SECONDS_DEFAULT = 90;

    // -- Report content defaults --------------------------------------------

    public static final String GP_DEFAULT_LANGUAGE = "medreport.defaultLanguage";

    public static final String DEFAULT_LANGUAGE = "fr";

    public static final String GP_DEFAULT_TEMPLATE = "medreport.defaultTemplate";

    public static final String DEFAULT_TEMPLATE = "chu_blida_neuro";

    public static final String GP_IMAGING_TEMPLATE = "medreport.imaging.template";

    public static final String IMAGING_TEMPLATE_DEFAULT = "chu_blida_imaging";

    public static final String GP_FACILITY_NAME = "medreport.facilityName";

    public static final String FACILITY_NAME_DEFAULT = "CHU de Blida";

    public static final String GP_DEPARTMENT_NAME = "medreport.departmentName";

    public static final String DEPARTMENT_NAME_DEFAULT = "Service de Neurochirurgie";

    // -- Complex obs storage (ADR-6) ----------------------------------------

    /**
     * Concept carrying the rendered .docx of an imaging report as a Complex Obs. Created by
     * {@link MedreportActivator} on first start if it does not already exist, so a fresh
     * install needs no manual dictionary work.
     */
    public static final String IMAGE_REPORT_CONCEPT_UUID = "1a5d3f60-8b21-4f2e-9c77-6c1f0f2a4b10";

    public static final String GP_IMAGE_REPORT_CONCEPT_UUID = "medreport.imaging.conceptUuid";

    /** Key under which {@code MedreportComplexObsHandler} is registered with the ObsService. */
    public static final String COMPLEX_OBS_HANDLER = "MedreportImageReportHandler";

    public static final String IMAGE_REPORT_ENCOUNTER_TYPE_UUID = "2b6e4c71-9d32-4a13-8e88-7d2a1e3b5c21";

    public static final String GP_IMAGE_REPORT_ENCOUNTER_TYPE_UUID = "medreport.imaging.encounterTypeUuid";

    // -- Audit ---------------------------------------------------------------

    public static final String ACTION_CREATE = "CREATE";

    public static final String ACTION_UPDATE = "UPDATE";

    public static final String ACTION_REMOVE = "REMOVE";

    public static final String ACTION_RESTORE = "RESTORE";

    public static final String ACTION_VIEW_HISTORY = "VIEW_HISTORY";

    public static final String ACTION_GENERATE = "GENERATE";

    public static final String ACTION_DENIED = "DENIED";

    // -- Version chain -------------------------------------------------------

    public static final String CHANGE_CREATE = "CREATE";

    public static final String CHANGE_UPDATE = "UPDATE";

    public static final String CHANGE_REMOVE = "REMOVE";

    public static final String CHANGE_RESTORE = "RESTORE";

    // -- Preferences ---------------------------------------------------------

    /** Preference key holding the serialised report-personalisation form, per user. */
    public static final String PREFERENCE_REPORT_OPTIONS = "report.options";

    private MedreportConstants() {
    }
}
