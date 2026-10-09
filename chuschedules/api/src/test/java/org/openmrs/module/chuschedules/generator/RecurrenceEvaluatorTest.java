package org.openmrs.module.chuschedules.generator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.openmrs.module.chuschedules.generator.RecurrenceEvaluator.LAST_IN_MONTH;
import static org.openmrs.module.chuschedules.generator.RecurrenceEvaluator.matchesMonthlyNth;
import static org.openmrs.module.chuschedules.generator.RecurrenceEvaluator.matchesWeekly;
import static org.openmrs.module.chuschedules.generator.RecurrenceEvaluator.normaliseAnchor;
import static org.openmrs.module.chuschedules.generator.RecurrenceEvaluator.toJavaDayOfWeek;

import java.time.DayOfWeek;
import java.time.LocalDate;

import org.junit.Test;

/**
 * All dates used here were checked against a real calendar. August 2026 is used for the monthly
 * cases because it has five Mondays, which is the only situation where "fourth" and "last"
 * disagree.
 */
public class RecurrenceEvaluatorTest {
	
	private static final int SUNDAY = 1;
	
	private static final int MONDAY = 2;
	
	private static final int TUESDAY = 3;
	
	private static final int SATURDAY = 7;
	
	// --- day-of-week conversion ---
	
	@Test
	public void toJavaDayOfWeek_mapsCalendarConventionToJavaTime() {
		assertEquals(DayOfWeek.SUNDAY, toJavaDayOfWeek(SUNDAY));
		assertEquals(DayOfWeek.MONDAY, toJavaDayOfWeek(MONDAY));
		assertEquals(DayOfWeek.TUESDAY, toJavaDayOfWeek(TUESDAY));
		assertEquals(DayOfWeek.SATURDAY, toJavaDayOfWeek(SATURDAY));
	}
	
	@Test(expected = IllegalArgumentException.class)
	public void toJavaDayOfWeek_rejectsOutOfRange() {
		toJavaDayOfWeek(0);
	}
	
	// --- weekly, interval 1 (the ordinary case) ---
	
	@Test
	public void weekly_everyWeek_matchesEveryTuesdayAndNothingElse() {
		LocalDate anchor = LocalDate.of(2026, 9, 1);
		assertTrue(matchesWeekly(LocalDate.of(2026, 9, 1), TUESDAY, 1, anchor));
		assertTrue(matchesWeekly(LocalDate.of(2026, 9, 8), TUESDAY, 1, anchor));
		assertTrue(matchesWeekly(LocalDate.of(2026, 9, 15), TUESDAY, 1, anchor));
		assertFalse(matchesWeekly(LocalDate.of(2026, 9, 16), TUESDAY, 1, anchor)); // Wednesday
	}
	
	@Test
	public void weekly_everyWeek_needsNoAnchor() {
		assertTrue(matchesWeekly(LocalDate.of(2026, 9, 8), TUESDAY, 1, null));
	}
	
	// --- weekly, rotating ---
	
	@Test
	public void weekly_everyOtherTuesday_alternates() {
		LocalDate anchor = LocalDate.of(2026, 9, 1);
		assertTrue(matchesWeekly(LocalDate.of(2026, 9, 1), TUESDAY, 2, anchor));
		assertFalse(matchesWeekly(LocalDate.of(2026, 9, 8), TUESDAY, 2, anchor));
		assertTrue(matchesWeekly(LocalDate.of(2026, 9, 15), TUESDAY, 2, anchor));
		assertFalse(matchesWeekly(LocalDate.of(2026, 9, 22), TUESDAY, 2, anchor));
		assertTrue(matchesWeekly(LocalDate.of(2026, 9, 29), TUESDAY, 2, anchor));
	}
	
	@Test
	public void weekly_oneWeekInThree_matchesEveryThirdTuesday() {
		LocalDate anchor = LocalDate.of(2026, 9, 1);
		assertTrue(matchesWeekly(LocalDate.of(2026, 9, 1), TUESDAY, 3, anchor));
		assertFalse(matchesWeekly(LocalDate.of(2026, 9, 8), TUESDAY, 3, anchor));
		assertFalse(matchesWeekly(LocalDate.of(2026, 9, 15), TUESDAY, 3, anchor));
		assertTrue(matchesWeekly(LocalDate.of(2026, 9, 22), TUESDAY, 3, anchor));
	}
	
	@Test
	public void weekly_rotationHoldsAcrossAYear() {
		// The cycle must not drift over the full generation horizon.
		LocalDate anchor = LocalDate.of(2026, 9, 1);
		LocalDate aYearOn = LocalDate.of(2027, 8, 31); // 52 weeks after the anchor, so even
		assertEquals(DayOfWeek.TUESDAY, aYearOn.getDayOfWeek());
		assertTrue(matchesWeekly(aYearOn, TUESDAY, 2, anchor));
		assertFalse(matchesWeekly(aYearOn.minusWeeks(1), TUESDAY, 2, anchor));
	}
	
	@Test
	public void weekly_datesBeforeTheAnchorNeverMatch() {
		LocalDate anchor = LocalDate.of(2026, 9, 15);
		assertFalse(matchesWeekly(LocalDate.of(2026, 9, 1), TUESDAY, 2, anchor));
	}
	
	@Test(expected = IllegalArgumentException.class)
	public void weekly_rotatingRuleRejectsMissingAnchor() {
		matchesWeekly(LocalDate.of(2026, 9, 8), TUESDAY, 2, null);
	}
	
	@Test(expected = IllegalArgumentException.class)
	public void weekly_rejectsAnchorOnTheWrongWeekday() {
		matchesWeekly(LocalDate.of(2026, 9, 8), TUESDAY, 2, LocalDate.of(2026, 9, 7));
	}
	
	// --- anchor normalisation ---
	
	@Test
	public void normaliseAnchor_movesForwardToTheRightWeekday() {
		assertEquals(LocalDate.of(2026, 9, 8), normaliseAnchor(LocalDate.of(2026, 9, 2), TUESDAY));
	}
	
	@Test
	public void normaliseAnchor_leavesAnAlreadyCorrectDateAlone() {
		LocalDate tuesday = LocalDate.of(2026, 9, 8);
		assertEquals(tuesday, normaliseAnchor(tuesday, TUESDAY));
	}
	
	// --- monthly ---
	
	@Test
	public void monthly_firstMonday() {
		assertTrue(matchesMonthlyNth(LocalDate.of(2026, 8, 3), MONDAY, 1));
		assertFalse(matchesMonthlyNth(LocalDate.of(2026, 8, 10), MONDAY, 1));
		assertTrue(matchesMonthlyNth(LocalDate.of(2026, 9, 7), MONDAY, 1));
	}
	
	@Test
	public void monthly_secondMonday() {
		assertTrue(matchesMonthlyNth(LocalDate.of(2026, 8, 10), MONDAY, 2));
		assertFalse(matchesMonthlyNth(LocalDate.of(2026, 8, 3), MONDAY, 2));
	}
	
	@Test
	public void monthly_fourthAndLastDifferInAMonthWithFiveMondays() {
		// August 2026 Mondays: 3, 10, 17, 24, 31.
		assertTrue(matchesMonthlyNth(LocalDate.of(2026, 8, 24), MONDAY, 4));
		assertFalse(matchesMonthlyNth(LocalDate.of(2026, 8, 31), MONDAY, 4));
		
		assertTrue(matchesMonthlyNth(LocalDate.of(2026, 8, 31), MONDAY, LAST_IN_MONTH));
		assertFalse(matchesMonthlyNth(LocalDate.of(2026, 8, 24), MONDAY, LAST_IN_MONTH));
	}
	
	@Test
	public void monthly_fourthAndLastCoincideInAMonthWithFourMondays() {
		// September 2026 Mondays: 7, 14, 21, 28.
		assertTrue(matchesMonthlyNth(LocalDate.of(2026, 9, 28), MONDAY, 4));
		assertTrue(matchesMonthlyNth(LocalDate.of(2026, 9, 28), MONDAY, LAST_IN_MONTH));
	}
	
	@Test
	public void monthly_wrongWeekdayNeverMatches() {
		assertFalse(matchesMonthlyNth(LocalDate.of(2026, 8, 4), MONDAY, 1)); // a Tuesday
	}
	
	@Test
	public void monthly_lastWorksInFebruary() {
		// February 2027 has exactly four Mondays: 1, 8, 15, 22.
		assertEquals(DayOfWeek.MONDAY, LocalDate.of(2027, 2, 22).getDayOfWeek());
		assertTrue(matchesMonthlyNth(LocalDate.of(2027, 2, 22), MONDAY, LAST_IN_MONTH));
		assertFalse(matchesMonthlyNth(LocalDate.of(2027, 2, 15), MONDAY, LAST_IN_MONTH));
	}
	
	@Test(expected = IllegalArgumentException.class)
	public void monthly_rejectsNonsenseOrdinal() {
		matchesMonthlyNth(LocalDate.of(2026, 8, 3), MONDAY, 9);
	}
}
