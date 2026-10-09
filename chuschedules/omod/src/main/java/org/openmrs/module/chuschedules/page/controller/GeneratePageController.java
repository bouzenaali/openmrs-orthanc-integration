package org.openmrs.module.chuschedules.page.controller;

import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.Date;

import org.openmrs.module.chuschedules.ScheduleTemplate;
import org.openmrs.module.chuschedules.api.ChuSchedulesService;
import org.openmrs.module.chuschedules.generator.GenerationReport;
import org.openmrs.module.chuschedules.web.SchedulePatternFormatter;
import org.openmrs.ui.framework.annotation.SpringBean;
import org.openmrs.ui.framework.page.PageModel;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Preview, then generate. The preview is the generator itself running in dry-run mode, not a
 * separate estimate -- so what the page shows is exactly what the write would do, and the two can
 * never drift apart. Generation is never the default action: you have to look at the preview first.
 */
public class GeneratePageController {
	
	public void get(PageModel model, @RequestParam("templateId") Integer templateId,
	        @SpringBean("chuSchedulesService") ChuSchedulesService service) {
		
		ScheduleTemplate template = service.getTemplate(templateId);
		model.addAttribute("template", template);
		model.addAttribute("summary", SchedulePatternFormatter.describe(template));
		model.addAttribute("report", null);
		model.addAttribute("error", null);
		
		// Default to the full horizon the department books over: tomorrow, a year out.
		LocalDate tomorrow = LocalDate.now().plusDays(1);
		model.addAttribute("from", tomorrow.toString());
		model.addAttribute("to", tomorrow.plusYears(1).minusDays(1).toString());
	}
	
	public void post(PageModel model, @RequestParam("templateId") Integer templateId, @RequestParam("from") String from,
	        @RequestParam("to") String to, @RequestParam(value = "action", required = false) String action,
	        @SpringBean("chuSchedulesService") ChuSchedulesService service) {
		
		ScheduleTemplate template = service.getTemplate(templateId);
		model.addAttribute("template", template);
		model.addAttribute("summary", SchedulePatternFormatter.describe(template));
		model.addAttribute("from", from);
		model.addAttribute("to", to);
		model.addAttribute("error", null);
		model.addAttribute("report", null);
		
		// Anything other than an explicit confirmation is a preview. A stray reload or an
		// unexpected parameter must never be what writes a year of blocks.
		boolean dryRun = !"generate".equals(action);
		
		try {
			GenerationReport report = service.generate(template, parseDate(from), parseDate(to), dryRun);
			model.addAttribute("report", report);
		}
		catch (Exception e) {
			model.addAttribute("error", e.getMessage());
		}
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
