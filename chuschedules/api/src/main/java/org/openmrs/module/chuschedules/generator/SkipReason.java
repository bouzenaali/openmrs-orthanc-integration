package org.openmrs.module.chuschedules.generator;

/**
 * Why a candidate day produced no block. Every skip is reported with one of these, so a generation
 * run can be audited after the fact rather than guessed at.
 */
public enum SkipReason {
	
	/** The date is today or earlier. The past is a record, not a schedule. */
	PAST,
	
	/** The template is switched off. */
	TEMPLATE_INACTIVE,
	
	/** The date falls outside the template's validity period. */
	OUTSIDE_VALIDITY,
	
	/** A holiday or leave day excluded the date. The detail carries the reason text. */
	EXCEPTION,
	
	/** This range already produced a block for this date. Re-running changes nothing. */
	ALREADY_GENERATED,
	
	/**
	 * An appointment block already covers this time. Existing blocks always win; we never shadow or
	 * duplicate work someone entered by hand.
	 */
	OVERLAPS_EXISTING;
	
	/**
	 * Whether a skip of this kind is worth listing date by date. Only two are. A holiday clash and
	 * a collision with an existing block each concern one specific day and may need someone to do
	 * something about that day. The rest say the same thing about every date they touch --
	 * "the template ends in December", "you asked for dates in the past" -- so enumerating them
	 * buries the two that matter. A year's generation can produce hundreds of identical
	 * out-of-validity rows.
	 */
	public boolean warrantsItsOwnRow() {
		return this == EXCEPTION || this == OVERLAPS_EXISTING;
	}
}
