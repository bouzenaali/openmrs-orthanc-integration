package org.openmrs.module.chuschedules.generator;

import static org.junit.Assert.assertEquals;
import static org.openmrs.module.chuschedules.generator.RefreshPlanner.decide;

import java.time.LocalDate;

import org.junit.Test;

/**
 * The rule that decides whether a clinic gets voided when a template's pattern changes. Every
 * combination is asserted, not just the happy path: this is the one place in the module where a
 * wrong answer deletes a clinic that a patient is booked into.
 */
public class RefreshPlannerTest {
	
	private static final LocalDate TODAY = LocalDate.of(2026, 9, 17);
	
	private static final LocalDate FUTURE = LocalDate.of(2026, 10, 1);
	
	private static final LocalDate PAST = LocalDate.of(2026, 9, 1);
	
	// --- the protective rules ---
	
	@Test
	public void aFutureBookedBlockIsAlwaysKept() {
		assertEquals(RefreshDecision.KEEP_BOOKED, decide(FUTURE, TODAY, true, false, true));
	}
	
	@Test
	public void aFutureEmptyBlockIsVoidedSoItCanBeRegenerated() {
		assertEquals(RefreshDecision.VOID, decide(FUTURE, TODAY, true, false, false));
	}
	
	@Test
	public void thePastIsNeverTouched_evenWhenEmpty() {
		assertEquals(RefreshDecision.LEAVE_PAST, decide(PAST, TODAY, true, false, false));
	}
	
	@Test
	public void thePastIsNeverTouched_evenWhenBooked() {
		assertEquals(RefreshDecision.LEAVE_PAST, decide(PAST, TODAY, true, false, true));
	}
	
	@Test
	public void todayItselfCountsAsPast() {
		// Today's clinic may already be running; it is not ours to rewrite.
		assertEquals(RefreshDecision.LEAVE_PAST, decide(TODAY, TODAY, true, false, false));
	}
	
	@Test
	public void tomorrowIsTheFirstDayEligible() {
		assertEquals(RefreshDecision.VOID, decide(TODAY.plusDays(1), TODAY, true, false, false));
	}
	
	// --- blocks removed by hand ---
	
	@Test
	public void aBlockRemovedByHandIsReportedGoneRatherThanFailing() {
		assertEquals(RefreshDecision.ALREADY_GONE, decide(FUTURE, TODAY, false, false, false));
	}
	
	@Test
	public void anAlreadyVoidedBlockIsReportedGone() {
		assertEquals(RefreshDecision.ALREADY_GONE, decide(FUTURE, TODAY, true, true, false));
	}
	
	@Test
	public void aMissingBlockIsGoneEvenIfItSomehowLooksBooked() {
		// Defensive: "gone" is decided before "booked", so a stale appointment flag on a
		// block that no longer exists cannot wedge the refresh.
		assertEquals(RefreshDecision.ALREADY_GONE, decide(FUTURE, TODAY, false, false, true));
	}
	
	// --- the whole decision table, so no case can change unnoticed ---
	
	@Test
	public void everyCombinationIsAccountedFor() {
		for (boolean exists : new boolean[] { true, false }) {
			for (boolean voided : new boolean[] { true, false }) {
				for (boolean booked : new boolean[] { true, false }) {
					
					assertEquals("past must never be touched: exists=" + exists + " voided=" + voided + " booked=" + booked,
					    RefreshDecision.LEAVE_PAST, decide(PAST, TODAY, exists, voided, booked));
					
					RefreshDecision expected;
					if (!exists || voided) {
						expected = RefreshDecision.ALREADY_GONE;
					} else if (booked) {
						expected = RefreshDecision.KEEP_BOOKED;
					} else {
						expected = RefreshDecision.VOID;
					}
					assertEquals("future: exists=" + exists + " voided=" + voided + " booked=" + booked, expected,
					    decide(FUTURE, TODAY, exists, voided, booked));
				}
			}
		}
	}
	
	@Test
	public void aLiveFutureBlockIsOnlyEverVoidedWhenEmpty() {
		// The single assertion that matters most, stated on its own.
		for (boolean booked : new boolean[] { true, false }) {
			RefreshDecision d = decide(FUTURE, TODAY, true, false, booked);
			if (booked) {
				assertEquals(RefreshDecision.KEEP_BOOKED, d);
			} else {
				assertEquals(RefreshDecision.VOID, d);
			}
		}
	}
	
	// --- input validation ---
	
	@Test(expected = IllegalArgumentException.class)
	public void rejectsAMissingTargetDate() {
		decide(null, TODAY, true, false, false);
	}
	
	@Test(expected = IllegalArgumentException.class)
	public void rejectsAMissingToday() {
		decide(FUTURE, null, true, false, false);
	}
}
