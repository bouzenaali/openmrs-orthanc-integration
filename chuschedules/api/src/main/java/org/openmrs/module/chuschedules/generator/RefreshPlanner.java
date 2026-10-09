package org.openmrs.module.chuschedules.generator;

import java.time.LocalDate;

/**
 * Decides the fate of one previously generated block when its template's pattern changes. Pure: no
 * OpenMRS context, no database, no clock. The three facts it needs about the block -- does it still
 * exist, is it voided, does it have live appointments -- are gathered by the caller and passed in.
 * Split out for the same reason as {@link OccurrencePlanner}: this is the rule that decides whether
 * a clinic someone may be booked into gets deleted, and it must be verifiable without standing up a
 * server. Testing it through the real service would mean assembling the reporting, calculation and
 * serialization modules into the test classpath just to reach it, which buys a slow, brittle test
 * that fails for reasons unrelated to this rule.
 */
public final class RefreshPlanner {
	
	private RefreshPlanner() {
	}
	
	/**
	 * @param targetDate the day the block was generated for
	 * @param today generation never touches this day or earlier
	 * @param blockExists whether the appointment block could still be resolved by uuid
	 * @param blockVoided whether it has since been voided
	 * @param hasLiveAppointments whether any non-cancelled appointment sits in its slots
	 */
	public static RefreshDecision decide(LocalDate targetDate, LocalDate today, boolean blockExists, boolean blockVoided,
	        boolean hasLiveAppointments) {
		
		if (targetDate == null || today == null) {
			throw new IllegalArgumentException("both the target date and today are required");
		}
		
		// The past is a record. Even an empty past clinic stays: it is evidence of what the
		// department was offering, and reports read it.
		if (!targetDate.isAfter(today)) {
			return RefreshDecision.LEAVE_PAST;
		}
		
		// Someone removed it by hand. Ordinary and expected, not an error.
		if (!blockExists || blockVoided) {
			return RefreshDecision.ALREADY_GONE;
		}
		
		// The line this module does not cross, checked before anything destructive.
		if (hasLiveAppointments) {
			return RefreshDecision.KEEP_BOOKED;
		}
		
		return RefreshDecision.VOID;
	}
}
