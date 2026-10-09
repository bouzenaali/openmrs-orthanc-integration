package org.openmrs.module.agentgateway.security;

import org.openmrs.api.context.Credentials;

/**
 * Credentials standing for "this request has already been proven to belong to user X by a
 * signature this module issued". Package-private constructor on purpose: the only way to obtain
 * one is from a {@link DelegatedToken}, which in turn only exists after verification.
 * <p>
 * Carries both identifiers the token holds. The uuid is what
 * {@link DelegatedAuthenticationScheme} resolves against, because it is unambiguous; the subject
 * is the human-readable label OpenMRS uses when it reports who is authenticated, and is only used
 * to look the user up if the uuid is absent or no longer resolves.
 */
public final class DelegatedCredentials implements Credentials {

	public static final String SCHEME_NAME = "agentgateway-delegated-token";

	private final String subject;

	private final String userUuid;

	DelegatedCredentials(String subject, String userUuid) {
		this.subject = subject;
		this.userUuid = userUuid;
	}

	public static DelegatedCredentials forVerifiedToken(DelegatedToken token) {
		return new DelegatedCredentials(token.getUsername(), token.getUserUuid());
	}

	public String getUserUuid() {
		return userUuid;
	}

	@Override
	public String getAuthenticationScheme() {
		return SCHEME_NAME;
	}

	@Override
	public String getClientName() {
		return subject;
	}
}
