package org.openmrs.module.agentgateway.rollback;

/**
 * What an administrator gets back from a rollback attempt, or from asking whether one is
 * possible. There is no "probably" - either this module reversed the operation, or it says
 * plainly that a human has to.
 */
public final class RollbackResult {

	public enum Outcome {
		/** The operation was reversed and the reversing call is itself logged. */
		REVERSED,
		/**
		 * Every coherence check passed, but this was a dry run so nothing was changed. Kept
		 * distinct from {@link #REVERSED} so an administrator is never shown "reversed" for an
		 * operation that is still exactly as it was.
		 */
		REVERSIBLE,
		/** There is nothing to reverse: already rolled back, already voided, or it never applied. */
		NOTHING_TO_DO,
		/**
		 * Reversing this automatically would leave the database incoherent, so it was not
		 * attempted. The before/after detail is in the log entry for a human to work from.
		 */
		MANUAL_INTERVENTION_REQUIRED,
		/** The reversing call was attempted and OpenMRS refused it. */
		FAILED
	}

	private final Outcome outcome;

	private final String reason;

	private final Integer reversingLogId;

	private RollbackResult(Outcome outcome, String reason, Integer reversingLogId) {
		this.outcome = outcome;
		this.reason = reason;
		this.reversingLogId = reversingLogId;
	}

	public static RollbackResult reversed(String reason, Integer reversingLogId) {
		return new RollbackResult(Outcome.REVERSED, reason, reversingLogId);
	}

	public static RollbackResult reversible(String reason) {
		return new RollbackResult(Outcome.REVERSIBLE, reason, null);
	}

	public static RollbackResult nothingToDo(String reason) {
		return new RollbackResult(Outcome.NOTHING_TO_DO, reason, null);
	}

	public static RollbackResult manual(String reason) {
		return new RollbackResult(Outcome.MANUAL_INTERVENTION_REQUIRED, reason, null);
	}

	public static RollbackResult failed(String reason) {
		return new RollbackResult(Outcome.FAILED, reason, null);
	}

	public Outcome getOutcome() {
		return outcome;
	}

	public String getReason() {
		return reason;
	}

	public Integer getReversingLogId() {
		return reversingLogId;
	}

	public boolean isReversed() {
		return outcome == Outcome.REVERSED;
	}
}
