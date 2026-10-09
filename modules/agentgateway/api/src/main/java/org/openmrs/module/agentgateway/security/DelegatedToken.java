package org.openmrs.module.agentgateway.security;

/**
 * The verified payload of a delegated token. Only ever produced by
 * {@code AgentGatewayService#verifyDelegatedToken}, i.e. only after the signature checked out -
 * there is deliberately no way to build one of these from an unverified string, so no caller can
 * accidentally trust an identity that was merely asserted (ADR-13).
 */
public final class DelegatedToken {

	private final String username;

	private final String userUuid;

	private final String conversationId;

	private final boolean mayWrite;

	private final String purpose;

	private final long expiresAtEpochSeconds;

	DelegatedToken(String username, String userUuid, String conversationId, boolean mayWrite, String purpose,
			long expiresAtEpochSeconds) {
		this.username = username;
		this.userUuid = userUuid;
		this.conversationId = conversationId;
		this.mayWrite = mayWrite;
		this.purpose = purpose;
		this.expiresAtEpochSeconds = expiresAtEpochSeconds;
	}

	/** The OpenMRS username every call carrying this token runs as. */
	public String getUsername() {
		return username;
	}

	public String getUserUuid() {
		return userUuid;
	}

	public String getConversationId() {
		return conversationId;
	}

	/**
	 * Whether the clinician held {@code App: agentgateway.chat.write} when this token was minted.
	 * A false value blocks writes on its own, before any resource-level privilege is consulted;
	 * a true value grants nothing by itself - the user's own OpenMRS privileges still decide.
	 */
	public boolean mayWrite() {
		return mayWrite;
	}

	/** One of {@code AgentGatewayConstants.PURPOSE_*}. Never null on a verified token. */
	public String getPurpose() {
		return purpose;
	}

	public long getExpiresAtEpochSeconds() {
		return expiresAtEpochSeconds;
	}
}
