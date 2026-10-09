package org.openmrs.module.agentgateway.security;

import org.junit.BeforeClass;
import org.junit.Test;
import org.openmrs.module.agentgateway.AgentGatewayConstants;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The token contract, tested without a database or a wall clock.
 * <p>
 * These are the properties the whole delegation model rests on: a token can only be produced by
 * whoever holds this instance's private key, it stops being usable a few minutes later, and it
 * cannot be re-pointed at a different audience, issuer or purpose. Everything else in this module
 * assumes them.
 */
public class DelegatedTokenTest {

	private static PrivateKey privateKey;

	private static PublicKey publicKey;

	private static final long NOW = 1_760_000_000L;

	@BeforeClass
	public static void generateKeys() {
		KeyPair keyPair = RsaJwt.generateKeyPair();
		privateKey = keyPair.getPrivate();
		publicKey = keyPair.getPublic();
	}

	private String mint(String username, boolean mayWrite, String purpose, int ttlSeconds, long issuedAt) {
		return DelegatedTokenService.mintWith(username, "user-uuid", "conv-1", mayWrite, purpose, ttlSeconds,
				privateKey, issuedAt);
	}

	@Test
	public void aMintedTokenVerifiesAndCarriesTheUserItWasMintedFor() {
		String token = mint("dr.benali", true, AgentGatewayConstants.PURPOSE_CHAT, 300, NOW);

		DelegatedToken verified = DelegatedTokenService.verifyWith(token, publicKey, NOW + 10);

		assertEquals("dr.benali", verified.getUsername());
		assertEquals("user-uuid", verified.getUserUuid());
		assertEquals("conv-1", verified.getConversationId());
		assertEquals(AgentGatewayConstants.PURPOSE_CHAT, verified.getPurpose());
		assertTrue(verified.mayWrite());
	}

	@Test
	public void theWriteCapabilityIsCarriedFaithfullyRatherThanDefaultingOpen() {
		String token = mint("nurse.amina", false, AgentGatewayConstants.PURPOSE_CHAT, 300, NOW);
		assertFalse(DelegatedTokenService.verifyWith(token, publicKey, NOW + 10).mayWrite());
	}

	@Test
	public void anExpiredTokenIsRefused() {
		String token = mint("dr.benali", true, AgentGatewayConstants.PURPOSE_CHAT, 60, NOW);
		expectRejection(token, publicKey, NOW + 3600, "expired");
	}

	@Test
	public void aTokenIsNotAcceptedBeforeItWasIssued() {
		String token = mint("dr.benali", true, AgentGatewayConstants.PURPOSE_CHAT, 300, NOW);
		expectRejection(token, publicKey, NOW - 3600, "not valid yet");
	}

	@Test
	public void aTokenSignedByAnotherKeyIsRefused() {
		String token = mint("dr.chief", true, AgentGatewayConstants.PURPOSE_CHAT, 300, NOW);
		PublicKey somebodyElse = RsaJwt.generateKeyPair().getPublic();
		expectRejection(token, somebodyElse, NOW + 10, "signature");
	}

	@Test
	public void aTokenWithTamperedClaimsIsRefused() {
		// Same signature, different payload: the exact attack a signature exists to stop.
		String token = mint("nurse.amina", false, AgentGatewayConstants.PURPOSE_CHAT, 300, NOW);
		String[] parts = token.split("\\.");
		String payload = new String(Base64.getUrlDecoder().decode(parts[1]));
		String tampered = payload.replace("\"may_write\":false", "\"may_write\":true");
		String forged = parts[0] + "."
				+ Base64.getUrlEncoder().withoutPadding().encodeToString(tampered.getBytes()) + "." + parts[2];

		expectRejection(forged, publicKey, NOW + 10, "signature");
	}

	@Test
	public void aTokenAskingToBeVerifiedWithNoAlgorithmIsRefused() {
		Map<String, Object> header = new LinkedHashMap<String, Object>();
		header.put("alg", "none");
		header.put("typ", "JWT");
		Map<String, Object> claims = new LinkedHashMap<String, Object>();
		claims.put("iss", AgentGatewayConstants.TOKEN_ISSUER);
		claims.put("aud", AgentGatewayConstants.TOKEN_AUDIENCE);
		claims.put("sub", "dr.chief");
		claims.put("exp", NOW + 300);
		claims.put(AgentGatewayConstants.CLAIM_PURPOSE, AgentGatewayConstants.PURPOSE_CHAT);

		String unsigned = base64(header.toString()) + "." + base64(claims.toString()) + ".";
		expectRejection(unsigned, publicKey, NOW, "");
	}

	@Test
	public void aTokenForAnotherAudienceIsRefused() {
		Map<String, Object> claims = baseClaims();
		claims.put("aud", "some-other-service");
		expectRejection(RsaJwt.sign(claims, privateKey), publicKey, NOW, "audience");
	}

	@Test
	public void aTokenFromAnotherIssuerIsRefused() {
		Map<String, Object> claims = baseClaims();
		claims.put("iss", "somebody-else");
		expectRejection(RsaJwt.sign(claims, privateKey), publicKey, NOW, "issued by");
	}

	@Test
	public void aTokenWithNoExpiryIsRefused() {
		Map<String, Object> claims = baseClaims();
		claims.remove("exp");
		expectRejection(RsaJwt.sign(claims, privateKey), publicKey, NOW, "expiry");
	}

	@Test
	public void aTokenWithAnUnknownPurposeIsRefused() {
		Map<String, Object> claims = baseClaims();
		claims.put(AgentGatewayConstants.CLAIM_PURPOSE, "anything-goes");
		expectRejection(RsaJwt.sign(claims, privateKey), publicKey, NOW, "purpose");
	}

	@Test
	public void garbageIsRefusedRatherThanParsedLeniently() {
		expectRejection("not-a-token", publicKey, NOW, "well-formed");
		expectRejection("", publicKey, NOW, "No delegated token");
	}

	private Map<String, Object> baseClaims() {
		Map<String, Object> claims = new LinkedHashMap<String, Object>();
		claims.put("iss", AgentGatewayConstants.TOKEN_ISSUER);
		claims.put("aud", AgentGatewayConstants.TOKEN_AUDIENCE);
		claims.put("sub", "dr.benali");
		claims.put("iat", NOW);
		claims.put("exp", NOW + 300);
		claims.put(AgentGatewayConstants.CLAIM_PURPOSE, AgentGatewayConstants.PURPOSE_CHAT);
		return claims;
	}

	private String base64(String value) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes());
	}

	private void expectRejection(String token, PublicKey key, long now, String expectedInMessage) {
		try {
			DelegatedTokenService.verifyWith(token, key, now);
			fail("Expected the token to be refused");
		}
		catch (TokenException e) {
			assertTrue("Unexpected reason: " + e.getMessage(),
					e.getMessage().toLowerCase().contains(expectedInMessage.toLowerCase()));
		}
	}
}
