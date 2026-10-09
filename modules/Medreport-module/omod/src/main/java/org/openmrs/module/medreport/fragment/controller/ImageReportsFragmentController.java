package org.openmrs.module.medreport.fragment.controller;

import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.medreport.MedreportPrivileges;
import org.openmrs.ui.framework.fragment.FragmentModel;

/**
 * The fragment the imaging module includes on its study view.
 *
 * <p>This is the entire contract between the two modules. The imaging module passes the
 * study/series UIDs it already has on screen and includes this fragment; everything about
 * reports - the list, the editor, the privilege rules, the version chain - lives here and in
 * medreport's REST resources. The imaging module holds no report logic and needs no
 * dependency on medreport's Java types.
 *
 * <p>Configuration (all optional except one of studyUid/seriesUid):
 * <pre>
 *   ui.includeFragment("medreport", "imageReports",
 *       [studyUid: study.uid, seriesUid: series.uid, patientId: patient.patientId,
 *        modality: study.modality, studyDate: study.date, studyDescription: study.description])
 * </pre>
 *
 * <p>The capability flags below drive which buttons render (RP6's UI half). They are a
 * convenience only - each REST endpoint re-checks the same privilege, so a stale or
 * hand-edited page gains nothing.
 */
public class ImageReportsFragmentController {

    public void controller(FragmentModel model) {
        model.addAttribute("canView", MedreportPrivileges.canViewImaging());
        model.addAttribute("canManage", MedreportPrivileges.canManageImaging());
        model.addAttribute("isAdmin", MedreportPrivileges.isReportAdmin());

        // The imaging module knows the study; it does not necessarily know which OpenMRS
        // patient it belongs to. Resolve it here when a uuid was passed instead of an id, so
        // the caller can hand over whichever it happens to hold.
        Object patientId = model.getAttribute("patientId");
        if (patientId instanceof String && !((String) patientId).isEmpty()
                && !isInteger((String) patientId)) {
            Patient patient = Context.getPatientService().getPatientByUuid((String) patientId);
            model.addAttribute("patientId", patient != null ? patient.getPatientId() : null);
        }
    }

    private boolean isInteger(String value) {
        try {
            Integer.parseInt(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
