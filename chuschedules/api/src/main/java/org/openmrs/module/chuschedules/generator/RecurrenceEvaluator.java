package org.openmrs.module.chuschedules.generator;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Decides whether a given date is an occurrence of a recurrence rule. Pure date arithmetic: no
 * OpenMRS context, no database, no clock. That is deliberate -- this is the one piece of the module
 * whose mistakes would be invisible until a clinic failed to appear, so it must be testable
 * exhaustively without a running server. Day-of-week values follow java.util.Calendar (1 = Sunday
 * .. 7 = Saturday), matching the convention already used by the appointmentscheduling module rather
 * than introducing a second one.
 */
public final class RecurrenceEvaluator {
	
	public static final int LAST_IN_MONTH = -1;
	
	private RecurrenceEvaluator() {
	}
	
	/**
	 * Converts a java.util.Calendar day-of-week constant to its java.time equivalent.
	 */
	public static DayOfWeek toJavaDayOfWeek(int calendarDayOfWeek) {
		if (calendarDayOfWeek < 1 || calendarDayOfWeek > 7) {
			throw new IllegalArgumentException("day of week must be 1..7 (Calendar convention), got " + calendarDayOfWeek);
		}
		// Calendar: Sunday=1 .. Saturday=7. java.time: Monday=1 .. Sunday=7.
		return DayOfWeek.of(calendarDayOfWeek == 1 ? 7 : calendarDayOfWeek - 1);
	}
	
	/**
	 * The first date on or after {@code from} that falls on {@code calendarDayOfWeek}. Weekly
	 * anchors are always normalised through this method, which is what lets {@link #matchesWeekly}
	 * count whole weeks with exact integer arithmetic instead of having to pick a week-start
	 * convention (Saturday? Sunday? Monday?) that would differ between locales and be a permanent
	 * source of off-by-one bugs.
	 */
	public static LocalDate normaliseAnchor(LocalDate from, int calendarDayOfWeek) {
		DayOfWeek target = toJavaDayOfWeek(calendarDayOfWeek);
		LocalDate d = from;
		while (d.getDayOfWeek() != target) {
			d = d.plusDays(1);
		}
		return d;
	}
	
	/**
	 * "Every Nth week on this weekday", counted from the anchor.
	 * 
	 * @param anchor must already fall on {@code calendarDayOfWeek} (see {@link #normaliseAnchor});
	 *            dates before it never match.
	 */
	public static boolean matchesWeekly(LocalDate date, int calendarDayOfWeek, int weekInterval, LocalDate anchor) {
		if (date.getDayOfWeek() != toJavaDayOfWeek(calendarDayOfWeek)) {
			return false;
		}
		if (weekInterval <= 1) {
			return true;
		}
		if (anchor == null) {
			throw new IllegalArgumentException("a weekly rule with interval " + weekInterval
			        + " needs an anchor date to count from");
		}
		if (anchor.getDayOfWeek() != toJavaDayOfWeek(calendarDayOfWeek)) {
			throw new IllegalArgumentException("anchor " + anchor + " does not fall on day-of-week " + calendarDayOfWeek
			        + "; normalise it first");
		}
		long days = ChronoUnit.DAYS.between(anchor, date);
		if (days < 0) {
			return false;
		}
		// Both dates share a weekday, so days is an exact multiple of 7.
		return (days / 7) % weekInterval == 0;
	}
	
	/**
	 * "The Nth such weekday of the calendar month", where N is 1..4 or {@link #LAST_IN_MONTH} for
	 * the last one. Note that "fourth" and "last" are not the same rule: a month with five Mondays
	 * has a fourth Monday and a distinct last one.
	 */
	public static boolean matchesMonthlyNth(LocalDate date, int calendarDayOfWeek, int ordinal) {
		if (date.getDayOfWeek() != toJavaDayOfWeek(calendarDayOfWeek)) {
			return false;
		}
		if (ordinal == LAST_IN_MONTH) {
			return date.plusWeeks(1).getMonthValue() != date.getMonthValue();
		}
		if (ordinal < 1 || ordinal > 4) {
			throw new IllegalArgumentException("month ordinal must be 1..4 or " + LAST_IN_MONTH + ", got " + ordinal);
		}
		int occurrence = ((date.getDayOfMonth() - 1) / 7) + 1;
		return occurrence == ordinal;
	}
}
