package org.openmrs.module.chuschedules;

/**
 * How a {@link ScheduleTemplateRange} repeats. Deliberately only two rules. Between them they
 * express every rotation the department described ("every other Tuesday",
 * "first Monday of the month"); a full iCalendar recurrence engine would be far more than is needed
 * and far harder to explain on screen.
 */
public enum RecurrenceType {
	
	/**
	 * Every Nth week on a fixed weekday, counted from an anchor date. weekInterval 1 is the
	 * ordinary "every Tuesday" case.
	 */
	WEEKLY,
	
	/**
	 * A fixed weekday at a fixed position within the calendar month: first, second, third, fourth,
	 * or last.
	 */
	MONTHLY_NTH
}
