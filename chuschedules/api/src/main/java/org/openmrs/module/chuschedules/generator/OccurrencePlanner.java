package org.openmrs.module.chuschedules.generator;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openmrs.module.chuschedules.RecurrenceType;
import org.openmrs.module.chuschedules.ScheduleTemplate;
import org.openmrs.module.chuschedules.ScheduleTemplateRange;

/**
 * Works out which days a template produces over a date range, and why each candidate was kept or
 * dropped. Pure: no database, no OpenMRS context, no system clock -- "today", the exclusions and
 * the set of already-generated occurrences are all passed in. Everything that decides *which days a
 * clinic runs* therefore has unit tests, which matters because a mistake here shows up as a clinic
 * that quietly does not exist. Collision with existing appointment blocks is not decided here; that
 * needs the appointment service and is applied afterwards by the caller.
 */
public final class OccurrencePlanner {
	
	private OccurrencePlanner() {
	}
	
	/**
	 * @param today occurrences on or before this date are skipped as PAST; generation starts
	 *            tomorrow at the earliest
	 * @param exclusions dates this template's provider does not work, mapped to the reason shown in
	 *            the report (global holidays already merged in by the caller)
	 * @param alreadyGenerated occurrences this module has already created
	 */
	public static List<PlannedOccurrence> plan(ScheduleTemplate template, LocalDate from, LocalDate to, LocalDate today,
	        Map<LocalDate, String> exclusions, Set<RangeDate> alreadyGenerated) {
		
		if (template == null) {
			throw new IllegalArgumentException("template is required");
		}
		if (from == null || to == null) {
			throw new IllegalArgumentException("both ends of the generation range are required");
		}
		if (to.isBefore(from)) {
			throw new IllegalArgumentException("generation range ends (" + to + ") before it starts (" + from + ")");
		}
		
		List<PlannedOccurrence> plan = new ArrayList<PlannedOccurrence>();
		if (template.getRanges() == null || template.getRanges().isEmpty()) {
			return plan;
		}
		
		LocalDate validFrom = toLocalDate(template.getValidFrom());
		LocalDate validTo = toLocalDate(template.getValidTo());
		
		for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
			for (ScheduleTemplateRange range : template.getRanges()) {
				if (range.getVoided() != null && range.getVoided()) {
					continue;
				}
				if (!occursOn(range, date, validFrom)) {
					continue;
				}
				
				// A day the pattern produces. Now decide whether it survives.
				if (!date.isAfter(today)) {
					plan.add(PlannedOccurrence.skipped(date, range, SkipReason.PAST, null));
					continue;
				}
				if (template.getActive() == null || !template.getActive()) {
					plan.add(PlannedOccurrence.skipped(date, range, SkipReason.TEMPLATE_INACTIVE, null));
					continue;
				}
				if ((validFrom != null && date.isBefore(validFrom)) || (validTo != null && date.isAfter(validTo))) {
					plan.add(PlannedOccurrence.skipped(date, range, SkipReason.OUTSIDE_VALIDITY, null));
					continue;
				}
				String exclusion = exclusions == null ? null : exclusions.get(date);
				if (exclusion != null) {
					plan.add(PlannedOccurrence.skipped(date, range, SkipReason.EXCEPTION, exclusion));
					continue;
				}
				if (alreadyGenerated != null && alreadyGenerated.contains(new RangeDate(range.getRangeId(), date))) {
					plan.add(PlannedOccurrence.skipped(date, range, SkipReason.ALREADY_GENERATED, null));
					continue;
				}
				plan.add(PlannedOccurrence.included(date, range));
			}
		}
		return Collections.unmodifiableList(plan);
	}
	
	/**
	 * Whether the range's recurrence rule fires on this date, ignoring every other consideration. A
	 * rotating weekly rule with no explicit anchor counts its cycle from the start of the
	 * template's validity, which is the reading a user expects when they say "every other Tuesday
	 * from March".
	 */
	static boolean occursOn(ScheduleTemplateRange range, LocalDate date, LocalDate validFrom) {
		RecurrenceType type = range.getRecurrenceType() == null ? RecurrenceType.WEEKLY : range.getRecurrenceType();
		int dayOfWeek = range.getDayOfWeek();
		
		if (type == RecurrenceType.MONTHLY_NTH) {
			if (range.getMonthOrdinal() == null) {
				throw new IllegalStateException("range " + range.getRangeId()
				        + " is monthly but has no position in the month");
			}
			return RecurrenceEvaluator.matchesMonthlyNth(date, dayOfWeek, range.getMonthOrdinal());
		}
		
		int interval = range.getWeekInterval() == null ? 1 : range.getWeekInterval();
		if (interval <= 1) {
			return RecurrenceEvaluator.matchesWeekly(date, dayOfWeek, 1, null);
		}
		
		LocalDate anchor = toLocalDate(range.getAnchorDate());
		if (anchor == null) {
			if (validFrom == null) {
				throw new IllegalStateException("range " + range.getRangeId() + " repeats every " + interval
				        + " weeks but has neither an anchor date nor a template validity start to count from");
			}
			anchor = RecurrenceEvaluator.normaliseAnchor(validFrom, dayOfWeek);
		} else {
			anchor = RecurrenceEvaluator.normaliseAnchor(anchor, dayOfWeek);
		}
		return RecurrenceEvaluator.matchesWeekly(date, dayOfWeek, interval, anchor);
	}
	
	private static LocalDate toLocalDate(java.util.Date date) {
		if (date == null) {
			return null;
		}
		if (date instanceof java.sql.Date) {
			return ((java.sql.Date) date).toLocalDate();
		}
		return date.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate();
	}
}
