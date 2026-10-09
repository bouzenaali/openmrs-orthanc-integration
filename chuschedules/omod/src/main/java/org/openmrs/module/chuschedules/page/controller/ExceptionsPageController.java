package org.openmrs.module.chuschedules.page.controller;

import java.text.SimpleDateFormat;
import java.util.Date;

import org.openmrs.api.ProviderService;
import org.openmrs.module.chuschedules.ScheduleException;
import org.openmrs.module.chuschedules.api.ChuSchedulesService;
import org.openmrs.ui.framework.annotation.SpringBean;
import org.openmrs.ui.framework.page.PageModel;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The exception calendar: public holidays hospital-wide, leave per provider. Eid and the other
 * lunar holidays move every year, so these are entered as data rather than computed. A yearly
 * reminder is a great deal cheaper than carrying an Islamic-calendar dependency, and it keeps the
 * reason text honest.
 */
public class ExceptionsPageController {
	
	public void get(PageModel model, @SpringBean("chuSchedulesService") ChuSchedulesService service,
	        @SpringBean("providerService") ProviderService providerService) {
		populate(model, service, providerService);
		model.addAttribute("error", null);
	}
	
	public void post(PageModel model, @RequestParam(value = "action", required = false) String action,
	        @RequestParam(value = "exceptionId", required = false) Integer exceptionId,
	        @RequestParam(value = "exceptionDate", required = false) String exceptionDate,
	        @RequestParam(value = "providerId", required = false) Integer providerId,
	        @RequestParam(value = "reason", required = false) String reason,
	        @SpringBean("chuSchedulesService") ChuSchedulesService service,
	        @SpringBean("providerService") ProviderService providerService) {
		
		model.addAttribute("error", null);
		try {
			if ("delete".equals(action) && exceptionId != null) {
				// Voided, not deleted: a past generation's report refers to these reasons,
				// and it should still be explicable next year.
				ScheduleException existing = service.getException(exceptionId);
				if (existing != null) {
					service.voidException(existing, "Supprimé via la page des exceptions");
				}
			} else if (exceptionDate != null) {
				ScheduleException exception = new ScheduleException();
				exception.setExceptionDate(parseDate(exceptionDate));
				exception.setReason(reason);
				exception.setProvider(providerId == null ? null : providerService.getProvider(providerId));
				service.saveException(exception);
			}
		}
		catch (Exception e) {
			model.addAttribute("error", e.getMessage());
		}
		populate(model, service, providerService);
	}
	
	private void populate(PageModel model, ChuSchedulesService service, ProviderService providerService) {
		model.addAttribute("exceptions", service.getAllExceptions(false));
		model.addAttribute("providers", providerService.getAllProviders(false));
	}
	
	private static Date parseDate(String value) {
		try {
			return new SimpleDateFormat("yyyy-MM-dd").parse(value);
		}
		catch (Exception e) {
			throw new IllegalArgumentException("Date invalide : " + value);
		}
	}
}
