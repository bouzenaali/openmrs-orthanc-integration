package org.openmrs.module.agentgateway.security;

import org.apache.commons.lang.StringUtils;
import org.openmrs.User;
import org.openmrs.api.context.Authenticated;
import org.openmrs.api.context.BasicAuthenticated;
import org.openmrs.api.context.ContextAuthenticationException;
import org.openmrs.api.context.Credentials;
import org.openmrs.api.context.DaoAuthenticationScheme;

/**
 * Turns an already-verified delegated token into an authenticated OpenMRS user, so that every
 * privilege check downstream - {@code patient.write}, {@code Manage Appointments}, the
 * patientview dashboard privileges, all of it - runs unmodified as that clinician (CA7).
 * <p>
 * <b>Deliberately not registered as the platform-wide authentication scheme.</b> OpenMRS lets a
 * module override {@code Context}'s single global {@code AuthenticationScheme} bean, which is
 * how {@code openmrs-module-oauth2login} does this handoff. Doing that here would put this
 * module on the critical path of every ordinary username/password login in the hospital,
 * including the administrator login someone would need to fix it - for a module whose entire
 * point is that it must never sit on the critical path of existing workflows. Instead the audit
 * filter builds a throwaway {@code UserContext} around this scheme for the duration of one
 * agent-originated request and restores the previous one afterwards. Same end result, same SPI,
 * but a failure here can only ever break the chat.
 * <p>
 * This class performs no verification of its own and must never be handed credentials built from
 * an unverified string - {@link DelegatedCredentials} can only be constructed from a
 * {@link DelegatedToken}, which enforces that.
 */
public class DelegatedAuthenticationScheme extends DaoAuthenticationScheme {

	@Override
	public Authenticated authenticate(Credentials credentials) throws ContextAuthenticationException {
		if (!(credentials instanceof DelegatedCredentials)) {
			throw new ContextAuthenticationException(
					"The delegated authentication scheme only accepts delegated credentials");
		}

		User user = resolve((DelegatedCredentials) credentials);
		if (user == null) {
			throw new ContextAuthenticationException("No OpenMRS user matches the delegated token's subject");
		}
		if (user.isRetired()) {
			throw new ContextAuthenticationException("The delegated token's user has been retired");
		}

		return new BasicAuthenticated(user, DelegatedCredentials.SCHEME_NAME);
	}

	/**
	 * Resolves the token's user by uuid, falling back to the subject label.
	 * <p>
	 * The uuid is preferred because it is unambiguous: the subject may be either a username or a
	 * system id, since OpenMRS accounts are not required to have a username, and
	 * {@code getUserByUsername} only ever matches the former. Looking up the uuid means the
	 * subject never has to be interpreted - it stays a label. Both values come from the same
	 * signed token, so neither is more trusted than the other; this is about which one identifies
	 * a row.
	 */
	private User resolve(DelegatedCredentials credentials) {
		String uuid = credentials.getUserUuid();
		if (StringUtils.isNotBlank(uuid)) {
			User byUuid = getContextDAO().getUserByUuid(uuid);
			if (byUuid != null) {
				return byUuid;
			}
		}
		String subject = credentials.getClientName();
		return StringUtils.isBlank(subject) ? null : getContextDAO().getUserByUsername(subject);
	}
}
