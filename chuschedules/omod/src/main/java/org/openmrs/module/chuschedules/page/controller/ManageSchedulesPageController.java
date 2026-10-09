package org.openmrs.module.chuschedules.page.controller;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.openmrs.module.chuschedules.GeneratedBlock;
import org.openmrs.module.chuschedules.ScheduleTemplate;
import org.openmrs.module.chuschedules.api.ChuSchedulesService;
import org.openmrs.module.chuschedules.web.SchedulePatternFormatter;
import org.openmrs.ui.framework.annotation.SpringBean;
import org.openmrs.ui.framework.page.PageModel;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The landing page: every template, its pattern in words, and how far ahead each has already been
 * generated. That last column is the one people actually come here for -- "is Dr X's clinic set up
 * for November yet" is the question a recurring schedule is supposed to make answerable at a
 * glance.
 */
public class ManageSchedulesPageController {
	
	public void controller(PageModel model, @SpringBean("chuSchedulesService") ChuSchedulesService service,
	        @RequestParam(value = "voided", required = false) Integer voided,
	        @RequestParam(value = "kept", required = false) Integer kept) {
		
		// Set only after an edit that changed the pattern, so the user is told what happened
		// to the clinics that were already generated under the old one.
		model.addAttribute("voided", voided);
		model.addAttribute("kept", kept);
		
		List<ScheduleTemplate> templates = service.getAllTemplates(false);
		
		Map<Integer, String> summaries = new HashMap<Integer, String>();
		Map<Integer, Date> horizons = new HashMap<Integer, Date>();
		
		for (ScheduleTemplate template : templates) {
			summaries.put(template.getTemplateId(), SchedulePatternFormatter.describe(template));
			
			List<GeneratedBlock> generated = service.getGeneratedBlocks(template, null, null);
			Date furthest = null;
			for (GeneratedBlock g : generated) {
				if (furthest == null || g.getTargetDate().after(furthest)) {
					furthest = g.getTargetDate();
				}
			}
			horizons.put(template.getTemplateId(), furthest);
		}
		
		model.addAttribute("templates", templates);
		model.addAttribute("summaries", summaries);
		model.addAttribute("horizons", horizons);
	}
}
