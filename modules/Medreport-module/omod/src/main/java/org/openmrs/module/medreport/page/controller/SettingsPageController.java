package org.openmrs.module.medreport.page.controller;

import org.openmrs.api.context.Context;
import org.openmrs.module.medreport.MedreportConstants;
import org.openmrs.module.medreport.MedreportPrivileges;
import org.openmrs.module.medreport.api.MedreportAuditService;
import org.openmrs.module.medreport.api.PatientReportService;
import org.openmrs.module.medreport.api.catalog.DataSourceRegistry;
import org.openmrs.module.medreport.api.spi.DataSourceDescriptor;
import org.openmrs.ui.framework.page.PageModel;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Administrator screen: rendering-service health, the template registry, which modules are
 * contributing clinical data, and the recent audit trail.
 *
 * <p>This is the "settings system to facilitate adding report templates" the brief asks for:
 * it lists the registered templates, shows whether the service can produce PDFs on this
 * host, and points at the two ways to add a template (a JSON style profile, or an uploaded
 * .docx letterhead). It is also where an administrator confirms the data-source discovery
 * actually found the contributing modules after an upgrade.
 */
public class SettingsPageController {

    public void controller(PageModel model,
                           @RequestParam(value = "refresh", required = false) String refresh) {
        // Admin-only. The audit log lists patient ids and clinician names, so this page is
        // gated at least as tightly as the reports it describes.
        if (!MedreportPrivileges.isReportAdmin()) {
            model.addAttribute("accessDenied", true);
            model.addAttribute("templates", Collections.emptyList());
            model.addAttribute("dataSources", Collections.emptyList());
            model.addAttribute("auditEntries", Collections.emptyList());
            model.addAttribute("serviceHealth", Collections.emptyMap());
            model.addAttribute("tokenConfigured", false);
            return;
        }
        model.addAttribute("accessDenied", false);

        List<DataSourceRegistry> registries =
                Context.getRegisteredComponents(DataSourceRegistry.class);
        if (refresh != null && !registries.isEmpty()) {
            // Module start order is not fixed, so a contributor installed after medreport
            // needs a rescan to appear. Doing it here saves an OpenMRS restart.
            for (DataSourceRegistry registry : registries) {
                registry.refresh();
            }
        }

        PatientReportService reportService = Context.getService(PatientReportService.class);
        model.addAttribute("serviceHealth", reportService.getRenderServiceHealth());

        List<Map<String, Object>> templates;
        try {
            templates = reportService.getTemplates();
        } catch (Exception e) {
            templates = Collections.emptyList();
        }
        model.addAttribute("templates", templates);

        List<Map<String, Object>> sources = new ArrayList<Map<String, Object>>();
        if (!registries.isEmpty()) {
            for (DataSourceDescriptor descriptor : registries.get(0).getDescriptors()) {
                Map<String, Object> entry = new LinkedHashMap<String, Object>();
                entry.put("id", descriptor.getId());
                entry.put("module", descriptor.getModuleId());
                entry.put("label", descriptor.getLabel("fr"));
                entry.put("sectionCount", countSections(descriptor));
                entry.put("requiredPrivilege", descriptor.getRequiredPrivilege());
                sources.add(entry);
            }
        }
        model.addAttribute("dataSources", sources);

        model.addAttribute("auditEntries",
                Context.getService(MedreportAuditService.class).getRecentLogs(50));

        String token = Context.getAdministrationService()
                .getGlobalProperty(MedreportConstants.GP_RENDER_TOKEN, "");
        // Whether it is set, never the value itself.
        model.addAttribute("tokenConfigured", token != null && !token.trim().isEmpty());
        model.addAttribute("renderBaseUrl", Context.getAdministrationService()
                .getGlobalProperty(MedreportConstants.GP_RENDER_BASE_URL,
                        MedreportConstants.GP_RENDER_BASE_URL_DEFAULT));
    }

    private int countSections(DataSourceDescriptor descriptor) {
        return descriptor.getSections() != null ? descriptor.getSections().size() : 0;
    }
}
