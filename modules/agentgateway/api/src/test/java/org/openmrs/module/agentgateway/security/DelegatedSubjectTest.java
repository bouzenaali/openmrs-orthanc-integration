package org.openmrs.module.agentgateway.security;

import org.junit.Test;
import org.openmrs.User;
import org.openmrs.module.agentgateway.AgentGatewayConstants;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

/**
 * Which identifier a token names, and which one it is resolved by.
 * <p>
 * OpenMRS does not require a username: an account can authenticate by system id alone, and the
 * Reference Application's user-creation flow produces exactly such accounts. Minting from
 * {@code getUsername()} alone therefore failed with "Cannot mint a delegated token without a
 * username" for most real clinician accounts, which meant the chat could never relay a single
 * turn. These tests pin the fallback so that cannot regress.
 */
public class DelegatedSubjectTest {

	private static User user(String username, String systemId) {
		User user = new User();
		user.setUuid("11111111-2222-3333-4444-555555555555");
		user.setUsername(username);
		user.setSystemId(systemId);
		return user;
	}

	@Test
	public void usesTheUsernameWhenTheAccountHasOne() {
		assertEquals("dr.benali", DelegatedTokenService.subjectFor(user("dr.benali", "1234-5")));
	}

	@Test
	public void fallsBackToTheSystemIdWhenTheAccountHasNoUsername() {
		assertEquals("1234-5", DelegatedTokenService.subjectFor(user(null, "1234-5")));
		assertEquals("1234-5", DelegatedTokenService.subjectFor(user("", "1234-5")));
		assertEquals("1234-5", DelegatedTokenService.subjectFor(user("   ", "1234-5")));
	}

	@Test
	public void refusesToMintForAnAccountWithNeither() {
		try {
			DelegatedTokenService.subjectFor(user(null, null));
			fail("expected a TokenException");
		}
		catch (TokenException expected) {
			// A token with a blank subject would be unusable on the agent side, which requires a
			// non-empty "sub". Failing here names the account instead.
		}
	}

	@Test
	public void refusesToMintWithoutAUser() {
		try {
			DelegatedTokenService.subjectFor(null);
			fail("expected a TokenException");
		}
		catch (TokenException expected) {
			// as above
		}
	}

	@Test
	public void credentialsCarryBothIdentifiersFromTheVerifiedToken() {
		DelegatedToken token = new DelegatedToken("1234-5", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", "conv-1", true,
				AgentGatewayConstants.PURPOSE_CHAT, 0L);

		DelegatedCredentials credentials = DelegatedCredentials.forVerifiedToken(token);

		// The uuid is what the scheme resolves against; the subject stays a label.
		assertEquals("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", credentials.getUserUuid());
		assertEquals("1234-5", credentials.getClientName());
		assertEquals(DelegatedCredentials.SCHEME_NAME, credentials.getAuthenticationScheme());
	}

	@Test
	public void credentialsTolerateATokenWithNoUuidClaim() {
		// Tokens minted by an earlier version of this module carry no user_uuid. The scheme falls
		// back to the subject for those rather than refusing them outright.
		DelegatedToken token = new DelegatedToken("dr.benali", null, "conv-1", false,
				AgentGatewayConstants.PURPOSE_CHAT, 0L);

		DelegatedCredentials credentials = DelegatedCredentials.forVerifiedToken(token);

		assertNull(credentials.getUserUuid());
		assertEquals("dr.benali", credentials.getClientName());
	}
}
