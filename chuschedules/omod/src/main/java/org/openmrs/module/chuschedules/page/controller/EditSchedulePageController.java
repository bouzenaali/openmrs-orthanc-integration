package org.openmrs.module.chuschedules.page.controller;

import java.sql.Time;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import javax.servlet.http.HttpServletRequest;

import org.openmrs.Location;
import org.openmrs.Provider;
import org.openmrs.api.APIException;
import org.openmrs.api.LocationService;
import org.openmrs.api.ProviderService;
import org.openmrs.module.appointmentscheduling.AppointmentType;
import org.openmrs.module.appointmentscheduling.api.AppointmentService;
import org.openmrs.module.chuschedules.RecurrenceType;
import org.openmrs.module.chuschedules.ScheduleTemplate;
import org.openmrs.module.chuschedules.ScheduleTemplateRange;
import org.openmrs.api.context.Context;
import org.openmrs.module.chuschedules.api.ChuSchedulesService;
import org.openmrs.module.chuschedules.api.impl.ChuSchedulesServiceImpl;
import org.openmrs.module.chuschedules.generator.RefreshReport;
import org.openmrs.ui.framework.annotation.SpringBean;
import org.openmrs.ui.framework.page.PageModel;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Create or edit one template. Sessions are posted as parallel arrays, one entry per row of the
 * weekday grid, which keeps the page a plain form post with no client-side state.
 */
public class EditSchedulePageController {
	
	public void get(PageModel model, @RequestParam(value = "templateId", required = false) Integer templateId,
	        @SpringBean("chuSchedulesService") ChuSchedulesService service,
	        @SpringBean("providerService") ProviderService providerService,
	        @SpringBean("locationService") LocationService locationService,
	        @SpringBean("appointmentService") AppointmentService appointmentService) {
		
		ScheduleTemplate template = templateId == null ? null : service.getTemplate(templateId);
		model.addAttribute("template", template);
		model.addAttribute("ranges", template == null ? new ArrayList<ScheduleTemplateRange>()
		        : new ArrayList<ScheduleTemplateRange>(ChuSchedulesServiceImpl.activeRanges(template)));
		addReferenceData(model, providerService, locationService, appointmentService);
		model.addAttribute("error", null);
	}
	
	public String post(PageModel model, HttpServletRequest request,
	        @RequestParam(value = "templateId", required = false) Integer templateId, @RequestParam("name") String name,
	        @RequestParam("providerId") Integer providerId, @RequestParam("locationId") Integer locationId,
	        @RequestParam("validFrom") String validFrom, @RequestParam(value = "validTo", required = false) String validTo,
	        @RequestParam(value = "active", required = false) Boolean active,
	        @RequestParam(value = "appointmentTypeIds", required = false) Integer[] appointmentTypeIds,
	        @SpringBean("chuSchedulesService") ChuSchedulesService service,
	        @SpringBean("providerService") ProviderService providerService,
	        @SpringBean("locationService") LocationService locationService,
	        @SpringBean("appointmentService") AppointmentService appointmentService) {
		
		try {
			ScheduleTemplate template = templateId == null ? new ScheduleTemplate() : service.getTemplate(templateId);
			
			template.setName(name);
			template.setProvider(providerService.getProvider(providerId));
			template.setLocation(locationService.getLocation(locationId));
			template.setValidFrom(parseDate(validFrom));
			template.setValidTo(isBlank(validTo) ? null : parseDate(validTo));
			template.setActive(active == null ? Boolean.FALSE : active);
			
			LinkedHashSet<AppointmentType> types = new LinkedHashSet<AppointmentType>();
			if (appointmentTypeIds != null) {
				for (Integer id : appointmentTypeIds) {
					types.add(appointmentService.getAppointmentType(id));
				}
			}
			template.setTypes(types);
			
			List<ScheduleTemplateRange> submitted = parseRanges(request);
			
			// Only disturb the schedule if the pattern genuinely changed. Without this, saving
			// the form after correcting a typo in the name would void and regenerate every
			// future clinic -- a rename must not move anybody's appointments.
			boolean patternChanged = templateId == null
			        || !signature(ChuSchedulesServiceImpl.activeRanges(template)).equals(signature(submitted));
			
			if (patternChanged) {
				// Void the old sessions rather than deleting them: chu_generated_block
				// references them as the provenance of everything already generated, and the
				// database rightly refuses to delete a referenced row.
				for (ScheduleTemplateRange existing : ChuSchedulesServiceImpl.activeRanges(template)) {
					existing.setVoided(true);
					existing.setVoidReason(VOID_REASON);
					existing.setDateVoided(new java.util.Date());
					existing.setVoidedBy(Context.getAuthenticatedUser());
				}
				for (ScheduleTemplateRange range : submitted) {
					template.addRange(range);
				}
			}
			
			service.saveTemplate(template);
			
			if (patternChanged && templateId != null) {
				// The old pattern's future clinics no longer match the template. Clear the
				// empty ones so they can be regenerated at the new hours; booked ones stay put
				// and are reported, because only a human can move a patient.
				RefreshReport refresh = service.refreshFutureBlocks(template, VOID_REASON);
				return "redirect:chuschedules/manageSchedules.page?voided=" + refresh.getVoidedCount() + "&kept="
				        + refresh.getKeptBookedCount();
			}
			return "redirect:chuschedules/manageSchedules.page";
		}
		catch (Exception e) {
			model.addAttribute("error", e.getMessage());
			model.addAttribute("template", templateId == null ? null : service.getTemplate(templateId));
			model.addAttribute("ranges", parseRanges(request));
			addReferenceData(model, providerService, locationService, appointmentService);
			return null;
		}
	}
	
	/**
	 * Reads the weekday grid back off the form. A row is ignored entirely when its times are blank,
	 * so a user can leave spare rows empty rather than having to delete them.
	 */
	private static final String VOID_REASON = "Horaire récurrent modifié";
	
	/**
	 * A comparable fingerprint of a weekly pattern, used only to answer "did anything about the
	 * sessions actually change?". Order-independent, so reordering rows is correctly treated as no
	 * change.
	 */
	private static String signature(List<ScheduleTemplateRange> ranges) {
		List<String> parts = new ArrayList<String>();
		for (ScheduleTemplateRange r : ranges) {
			parts.add(r.getDayOfWeek() + "|" + r.getStartTime() + "|" + r.getEndTime() + "|" + r.getRecurrenceType() + "|"
			        + r.getWeekInterval() + "|" + r.getMonthOrdinal());
		}
		java.util.Collections.sort(parts);
		return parts.toString();
	}
	
	private List<ScheduleTemplateRange> parseRanges(HttpServletRequest request) {
		List<ScheduleTemplateRange> ranges = new ArrayList<ScheduleTemplateRange>();
		String[] days = request.getParameterValues("rangeDayOfWeek");
		if (days == null) {
			return ranges;
		}
		String[] starts = request.getParameterValues("rangeStartTime");
		String[] ends = request.getParameterValues("rangeEndTime");
		String[] recurrences = request.getParameterValues("rangeRecurrence");
		
		for (int i = 0; i < days.length; i++) {
			if (isBlank(starts[i]) || isBlank(ends[i])) {
				continue;
			}
			ScheduleTemplateRange range = new ScheduleTemplateRange();
			range.setDayOfWeek(Integer.valueOf(days[i]));
			range.setStartTime(parseTime(starts[i]));
			range.setEndTime(parseTime(ends[i]));
			applyRecurrence(range, recurrences[i]);
			ranges.add(range);
		}
		return ranges;
	}
	
	/**
	 * The rotation is posted as a single token so the form stays one dropdown per row instead of
	 * three interdependent fields: WEEKLY:n, or MONTHLY:n where n is 1..4 or -1.
	 */
	private void applyRecurrence(ScheduleTemplateRange range, String token) {
		if (isBlank(token)) {
			range.setRecurrenceType(RecurrenceType.WEEKLY);
			range.setWeekInterval(1);
			return;
		}
		String[] parts = token.split(":");
		if ("MONTHLY".equals(parts[0])) {
			range.setRecurrenceType(RecurrenceType.MONTHLY_NTH);
			range.setMonthOrdinal(Integer.valueOf(parts[1]));
			range.setWeekInterval(null);
		} else {
			range.setRecurrenceType(RecurrenceType.WEEKLY);
			range.setWeekInterval(Integer.valueOf(parts[1]));
			range.setMonthOrdinal(null);
		}
	}
	
	private void addReferenceData(PageModel model, ProviderService providerService, LocationService locationService,
	        AppointmentService appointmentService) {
		model.addAttribute("providers", activeProviders(providerService));
		model.addAttribute("locations", locationService.getAllLocations(false));
		model.addAttribute("appointmentTypes", appointmentService.getAllAppointmentTypes(false));
	}
	
	private List<Provider> activeProviders(ProviderService providerService) {
		List<Provider> providers = new ArrayList<Provider>();
		for (Provider p : providerService.getAllProviders(false)) {
			providers.add(p);
		}
		return providers;
	}
	
	private static boolean isBlank(String s) {
		return s == null || s.trim().isEmpty();
	}
	
	private static java.util.Date parseDate(String value) {
		try {
			return new SimpleDateFormat("yyyy-MM-dd").parse(value);
		}
		catch (Exception e) {
			throw new APIException("Date invalide : " + value);
		}
	}
	
	private static Time parseTime(String value) {
		String v = value.trim();
		if (v.length() == 5) {
			v = v + ":00";
		}
		return Time.valueOf(v);
	}
	
	// Referenced from the GSP to keep location typing out of the template.
	public static Location location(LocationService service, Integer id) {
		return service.getLocation(id);
	}
}
