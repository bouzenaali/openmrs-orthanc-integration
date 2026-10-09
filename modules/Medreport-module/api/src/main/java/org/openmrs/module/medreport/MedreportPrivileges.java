package org.openmrs.module.medreport;

import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.context.Context;

/**
 * Privilege names for this module, and the explicit guards every API entry point calls.
 *
 * <h3>Why "App:"-prefixed privileges</h3>
 * The OpenMRS Reference Application deliberately auto-grants every plain (non-{@code App:},
 * non-{@code Task:}) privilege to all roles, gating real access through UI-level privileges
 * instead. A plain privilege is therefore not a boundary on this deployment - it is a
 * privilege everyone already has. Every privilege that must actually restrict something is
 * consequently {@code App:}-prefixed, matching the convention {@code patientview} already
 * established (see its {@code PatientviewPrivileges} and README §4).
 *
 * <h3>Relationship to Rapport_3's "Add/Edit reports" privileges</h3>
 * Those four ("Add reports", "Add reports objects", "Edit reports", "Edit reports objects")
 * are OpenMRS <em>core</em> privileges over the legacy {@code Report}/{@code ReportObject}
 * domain objects - report <em>definitions</em> such as cohort queries and data exports, not
 * clinical documents. They are also plain, unprefixed privileges, so on this Reference
 * Application install every role holds them already. They therefore neither cover nor
 * conflict with the privileges below, and the two sets are not redundant.
 *
 * <h3>Two layers, always</h3>
 * These constants are enforced twice on purpose (RP6): the UI hides or disables an action the
 * user cannot perform, and every service/REST entry point independently re-checks the same
 * privilege. The UI check is a convenience; the server check is the guard.
 */
public final class MedreportPrivileges {

    /** Read any imaging report - one's own or another author's (RP2). */
    public static final String APP_IMAGING_VIEW = "App: medreport.imaging.view";

    /** Create, update and remove one's own imaging reports (RP1, RP3, RP5). */
    public static final String APP_IMAGING_MANAGE = "App: medreport.imaging.manage";

    /** Browse a report's full version history and restore a removed report (RP4). */
    public static final String APP_ADMIN = "App: medreport.admin";

    /** Generate a full patient report from the dashboard (use case 2). */
    public static final String APP_DASHBOARD_GENERATE = "App: medreport.dashboardGenerate";

    /**
     * API-level privileges, kept for OpenMRS distributions that do <em>not</em> follow the
     * Reference Application's auto-grant convention. They are belt-and-suspenders only; the
     * {@code App:} privileges above are the effective boundary here.
     */
    public static final String VIEW_MEDICAL_REPORTS = "View Medical Reports";

    public static final String MANAGE_MEDICAL_REPORTS = "Manage Medical Reports";

    private MedreportPrivileges() {
    }

    public static boolean canViewImaging() {
        return Context.hasPrivilege(APP_IMAGING_VIEW);
    }

    public static boolean canManageImaging() {
        return Context.hasPrivilege(APP_IMAGING_MANAGE);
    }

    public static boolean isReportAdmin() {
        return Context.hasPrivilege(APP_ADMIN);
    }

    public static boolean canGenerateDashboardReport() {
        return Context.hasPrivilege(APP_DASHBOARD_GENERATE);
    }

    public static void requireImagingView() {
        require(APP_IMAGING_VIEW);
    }

    public static void requireImagingManage() {
        require(APP_IMAGING_MANAGE);
    }

    public static void requireAdmin() {
        require(APP_ADMIN);
    }

    public static void requireDashboardGenerate() {
        require(APP_DASHBOARD_GENERATE);
    }

    private static void require(String privilege) {
        if (!Context.hasPrivilege(privilege)) {
            throw new APIAuthenticationException("Requires privilege: " + privilege);
        }
    }
}
