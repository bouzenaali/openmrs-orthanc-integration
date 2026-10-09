package org.openmrs.module.chuschedules.web;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;

import org.openmrs.module.chuschedules.RecurrenceType;
import org.openmrs.module.chuschedules.ScheduleTemplate;
import org.openmrs.module.chuschedules.ScheduleTemplateRange;
import org.openmrs.module.chuschedules.generator.RecurrenceEvaluator;

/**
 * Renders a recurrence rule as the sentence a scheduling clerk would say out loud. Worth doing
 * carefully: the whole risk of a recurring schedule is that someone sets a pattern meaning one
 * thing and reads it back as another, so the summary has to state the rotation explicitly rather
 * than let "Mardi 08:00" stand for both every week and every other week.
 */
public class SchedulePatternFormatter {
	
	private static final String[] DAYS = { "", "Dimanche", "Lundi", "Mardi", "Mercredi", "Jeudi", "Vendredi", "Samedi" };
	
	private static final String[] DAYS_SHORT = { "", "Dim", "Lun", "Mar", "Mer", "Jeu", "Ven", "Sam" };
	
	private static final String[] ORDINALS = { "", "1er", "2e", "3e", "4e" };
	
	public static String dayName(Integer calendarDayOfWeek) {
		if (calendarDayOfWeek == null || calendarDayOfWeek < 1 || calendarDayOfWeek > 7) {
			return "?";
		}
		return DAYS[calendarDayOfWeek];
	}
	
	public static String dayShort(Integer calendarDayOfWeek) {
		if (calendarDayOfWeek == null || calendarDayOfWeek < 1 || calendarDayOfWeek > 7) {
			return "?";
		}
		return DAYS_SHORT[calendarDayOfWeek];
	}
	
	private static String time(java.sql.Time t) {
		return t == null ? "?" : new SimpleDateFormat("HH:mm").format(t);
	}
	
	/** e.g. "Mardi 08:00-12:00, une semaine sur deux". */
	public static String describe(ScheduleTemplateRange range) {
		if (range == null) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		sb.append(dayName(range.getDayOfWeek())).append(' ').append(time(range.getStartTime())).append('-')
		        .append(time(range.getEndTime()));
		
		if (range.getRecurrenceType() == RecurrenceType.MONTHLY_NTH) {
			Integer o = range.getMonthOrdinal();
			if (o != null && o == RecurrenceEvaluator.LAST_IN_MONTH) {
				sb.append(", le dernier du mois");
			} else if (o != null && o >= 1 && o <= 4) {
				sb.append(", le ").append(ORDINALS[o]).append(" du mois");
			}
			return sb.toString();
		}
		
		int interval = range.getWeekInterval() == null ? 1 : range.getWeekInterval();
		if (interval == 2) {
			sb.append(", une semaine sur deux");
		} else if (interval > 2) {
			sb.append(", une semaine sur ").append(interval);
		}
		return sb.toString();
	}
	
	/** Every session of a template, joined for a one-line listing. */
	public static String describe(ScheduleTemplate template) {
		if (template == null || template.getRanges() == null || template.getRanges().isEmpty()) {
			return "Aucune séance définie";
		}
		List<String> parts = new ArrayList<String>();
		for (ScheduleTemplateRange r : template.getRanges()) {
			if (r.getVoided() == null || !r.getVoided()) {
				parts.add(describe(r));
			}
		}
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < parts.size(); i++) {
			if (i > 0) {
				sb.append(" · ");
			}
			sb.append(parts.get(i));
		}
		return sb.toString();
	}
}
