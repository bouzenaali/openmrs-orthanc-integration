package org.openmrs.module.agentgateway.security;

import org.apache.commons.lang.StringUtils;
import org.openmrs.User;
import org.openmrs.module.agentgateway.AgentGatewayConfig;
import org.openmrs.module.agentgateway.AgentGatewayConstants;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Mints and verifies the short-lived tokens that carry a clinician's identity to the agent
 * service and back again (ADR-9).
 * <p>
 * The signing key pair is generated on first use and kept in global properties. The pure
 * {@code mintWith}/{@code verifyWith} forms take their keys and their idea of "now" as
 * arguments so the token contract can be tested without a database or a wall clock; the
 * convenience forms read both from the deployment's configuration.
 */
public final class DelegatedTokenService {

	/** Tolerance for clock drift between Server 1 and Server 2. They are on the same LAN. */
	private static final long CLOCK_SKEW_SECONDS = 30;

	private DelegatedTokenService() {
	}

	// ---------------------------------------------------------------- key material

	/**
	 * Generates and stores the signing key pair if this deployment does not have one yet.
	 * Idempotent, and safe to call on every module start.
	 *
	 * @return true if a new key pair was generated
	 */
	public static synchronized boolean ensureKeyPair() {
		String existingPrivate = AgentGatewayConfig
				.getGlobalPropertyAsSystem(AgentGatewayConstants.GP_SIGNING_PRIVATE_KEY);
		String existingPublic = AgentGatewayConfig.getGlobalPropertyAsSystem(AgentGatewayConstants.GP_SIGNING_PUBLIC_KEY);
		if (StringUtils.isNotBlank(existingPrivate) && StringUtils.isNotBlank(existingPublic)) {
			return false;
		}

		KeyPair keyPair = RsaJwt.generateKeyPair();
		AgentGatewayConfig.saveGlobalPropertyAsSystem(AgentGatewayConstants.GP_SIGNING_PRIVATE_KEY,
				RsaJwt.encodeKey(keyPair.getPrivate()));
		AgentGatewayConfig.saveGlobalPropertyAsSystem(AgentGatewayConstants.GP_SIGNING_PUBLIC_KEY,
				RsaJwt.encodeKey(keyPair.getPublic()));
		return true;
	}

	/** The base64 X.509 public key the agent service needs to verify what this module signs. */
	public static String getPublicKeyBase64() {
		ensureKeyPair();
		String encoded = AgentGatewayConfig.getGlobalPropertyAsSystem(AgentGatewayConstants.GP_SIGNING_PUBLIC_KEY);
		if (StringUtils.isBlank(encoded)) {
			throw new TokenException("No delegated-token public key is configured");
		}
		return encoded.trim();
	}

	private static PrivateKey getPrivateKey() {
		ensureKeyPair();
		String encoded = AgentGatewayConfig.getGlobalPropertyAsSystem(AgentGatewayConstants.GP_SIGNING_PRIVATE_KEY);
		if (StringUtils.isBlank(encoded)) {
			throw new TokenException("No delegated-token signing key is configured");
		}
		return RsaJwt.decodePrivateKey(encoded);
	}

	private static PublicKey getPublicKey() {
		return RsaJwt.decodePublicKey(getPublicKeyBase64());
	}

	// ---------------------------------------------------------------- subject

	/**
	 * The token subject for a user: their username, or their system id when the account has none.
	 * <p>
	 * OpenMRS does not require a username. An account can authenticate by system id alone, which is
	 * what the Reference Application's own user-creation flow produces - so reading only
	 * {@code getUsername()} fails for precisely the accounts most clinicians have, with
	 * "Cannot mint a delegated token without a username" and no chat at all.
	 * <p>
	 * The subject is a human-readable label, for the audit trail and the agent's logs. The
	 * authoritative identifier travels separately in the {@code user_uuid} claim, and that is what
	 * {@link DelegatedAuthenticationScheme} resolves - so this fallback cannot make one account be
	 * mistaken for another.
	 */
	public static String subjectFor(User user) {
		if (user == null) {
			throw new TokenException("Cannot mint a delegated token without a user");
		}
		if (StringUtils.isNotBlank(user.getUsername())) {
			return user.getUsername();
		}
		if (StringUtils.isNotBlank(user.getSystemId())) {
			return user.getSystemId();
		}
		throw new TokenException(
				"User " + user.getUuid() + " has neither a username nor a system id to name in a token");
	}

	// ---------------------------------------------------------------- minting

	public static String mint(String username, String userUuid, String conversationId, boolean mayWrite,
			String purpose) {
		return mintWith(username, userUuid, conversationId, mayWrite, purpose,
				AgentGatewayConfig.getTokenTtlSeconds(), getPrivateKey(), System.currentTimeMillis() / 1000L);
	}

	/**
	 * Mints for a named audience rather than the clinical agent's.
	 * <p>
	 * Added for the dictation service, which verifies {@code aud = stt-service}. Keeping the
	 * audiences apart is what stops a chat token driving the GPU and a dictation token opening a
	 * chat turn - the same separation {@code purpose} already provides, enforced a second time by
	 * a claim the recipient checks before it looks at anything else.
	 */
	public static String mintForAudience(String username, String userUuid, String conversationId, boolean mayWrite,
			String purpose, String audience) {
		return mintWith(username, userUuid, conversationId, mayWrite, purpose,
				AgentGatewayConfig.getTokenTtlSeconds(), getPrivateKey(), System.currentTimeMillis() / 1000L,
				audience);
	}

	public static String mintWith(String username, String userUuid, String conversationId, boolean mayWrite,
			String purpose, int ttlSeconds, PrivateKey privateKey, long nowEpochSeconds) {
		return mintWith(username, userUuid, conversationId, mayWrite, purpose, ttlSeconds, privateKey,
				nowEpochSeconds, AgentGatewayConstants.TOKEN_AUDIENCE);
	}

	public static String mintWith(String username, String userUuid, String conversationId, boolean mayWrite,
			String purpose, int ttlSeconds, PrivateKey privateKey, long nowEpochSeconds, String audience) {
		if (StringUtils.isBlank(username)) {
			throw new TokenException("Cannot mint a delegated token without a username");
		}
		if (StringUtils.isBlank(purpose)) {
			throw new TokenException("Cannot mint a delegated token without a purpose");
		}
		if (StringUtils.isBlank(audience)) {
			throw new TokenException("Cannot mint a delegated token without an audience");
		}
		Map<String, Object> claims = new LinkedHashMap<String, Object>();
		claims.put("iss", AgentGatewayConstants.TOKEN_ISSUER);
		claims.put("aud", audience);
		claims.put("sub", username);
		claims.put("iat", nowEpochSeconds);
		claims.put("exp", nowEpochSeconds + Math.max(30, ttlSeconds));
		claims.put("jti", UUID.randomUUID().toString());
		claims.put(AgentGatewayConstants.CLAIM_USER_UUID, userUuid);
		claims.put(AgentGatewayConstants.CLAIM_CONVERSATION_ID, conversationId);
		claims.put(AgentGatewayConstants.CLAIM_MAY_WRITE, mayWrite);
		claims.put(AgentGatewayConstants.CLAIM_PURPOSE, purpose);
		return RsaJwt.sign(claims, privateKey);
	}

	// ---------------------------------------------------------------- verification

	public static DelegatedToken verify(String token) {
		return verifyWith(token, getPublicKey(), System.currentTimeMillis() / 1000L);
	}

	public static DelegatedToken verifyWith(String token, PublicKey publicKey, long nowEpochSeconds) {
		Map<String, Object> claims = RsaJwt.verify(token, publicKey, AgentGatewayConstants.TOKEN_ISSUER,
				AgentGatewayConstants.TOKEN_AUDIENCE, nowEpochSeconds, CLOCK_SKEW_SECONDS);

		String purpose = asString(claims.get(AgentGatewayConstants.CLAIM_PURPOSE));
		if (!AgentGatewayConstants.PURPOSE_CHAT.equals(purpose)
				&& !AgentGatewayConstants.PURPOSE_ROLLBACK.equals(purpose)
				&& !AgentGatewayConstants.PURPOSE_INTERNAL_READ.equals(purpose)) {
			throw new TokenException("Delegated token carries an unrecognised purpose");
		}

		return new DelegatedToken(asString(claims.get("sub")),
				asString(claims.get(AgentGatewayConstants.CLAIM_USER_UUID)),
				asString(claims.get(AgentGatewayConstants.CLAIM_CONVERSATION_ID)),
				Boolean.TRUE.equals(claims.get(AgentGatewayConstants.CLAIM_MAY_WRITE)), purpose,
				((Number) claims.get("exp")).longValue());
	}

	private static String asString(Object value) {
		return value == null ? null : String.valueOf(value);
	}
}
