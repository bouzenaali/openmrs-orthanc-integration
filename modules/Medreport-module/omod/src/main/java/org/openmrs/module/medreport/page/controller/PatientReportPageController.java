package org.openmrs.module.medreport.page.controller;

import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.medreport.MedreportConstants;
import org.openmrs.module.medreport.MedreportPrivileges;
import org.openmrs.ui.framework.page.PageModel;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The "report personnalisation" window opened from the patient dashboard button.
 *
 * <p>Follows the access-control shape the rest of this deployment uses: set {@code
 * accessDenied} and render a plain message instead of the form, rather than throwing. The
 * page itself carries almost no state - the catalogue, templates and saved preferences are
 * fetched over REST by the page's JavaScript, so there is exactly one code path building
 * them and it is the one the API-layer privilege checks protect.
 */
public class PatientReportPageController {

    public void controller(PageModel model,
                           @RequestParam("patientId") String patientUuid) {
        Patient patient = Context.getPatientService().getPatientByUuid(patientUuid);
        model.addAttribute("patient", patient);

        if (patient == null || !MedreportPrivileges.canGenerateDashboardReport()) {
            model.addAttribute("accessDenied", true);
            return;
        }
        model.addAttribute("accessDenied", false);

        model.addAttribute("defaultLanguage", Context.getAdministrationService()
                .getGlobalProperty(MedreportConstants.GP_DEFAULT_LANGUAGE,
                        MedreportConstants.DEFAULT_LANGUAGE));
        model.addAttribute("facility", Context.getAdministrationService()
                .getGlobalProperty(MedreportConstants.GP_FACILITY_NAME,
                        MedreportConstants.FACILITY_NAME_DEFAULT));
        model.addAttribute("canViewImaging", MedreportPrivileges.canViewImaging());
    }
}
