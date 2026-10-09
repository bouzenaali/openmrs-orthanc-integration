package org.openmrs.module.agentgateway.security;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.Charset;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal RS256 JSON Web Token support, built on the JDK's own RSA and SHA-256 primitives.
 * <p>
 * <b>Why asymmetric.</b> The agent service has to read the acting clinician's identity out of a
 * token whose signature it has verified (ADR-13), which means it needs a verification key. With a
 * shared symmetric secret, a key that lets the agent verify tokens is also a key that lets the
 * agent - or anyone who compromises it - mint a token for any user in the hospital. Signing with a
 * private key that never leaves OpenMRS, and publishing only the public half, removes that
 * escalation path entirely: a compromised agent can still misuse the tokens it was handed, but it
 * cannot manufacture new ones for other clinicians.
 * <p>
 * <b>Why not a JWT library.</b> This module mints and verifies tokens it issued itself, in a
 * single algorithm, with no key rotation protocol - roughly a hundred lines of well-trodden JDK
 * calls. Adding a JWT library would put a second copy of Jackson (and its transitive tree) into
 * the module classloader of a deployment that has already had a production incident from exactly
 * that shape of duplication. The security-relevant decisions are all made explicitly below:
 * the algorithm is pinned rather than read from the token header (no "alg" confusion), the
 * signature is checked before any claim is looked at, and expiry, issuer and audience are all
 * mandatory.
 */
public final class RsaJwt {

	private static final Charset UTF_8 = Charset.forName("UTF-8");

	private static final String ALGORITHM = "RS256";

	private static final String JAVA_SIGNATURE_ALGORITHM = "SHA256withRSA";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private RsaJwt() {
	}

	public static KeyPair generateKeyPair() {
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(2048);
			return generator.generateKeyPair();
		}
		catch (Exception e) {
			throw new TokenException("Could not generate an RSA key pair for delegated tokens", e);
		}
	}

	public static String encodeKey(java.security.Key key) {
		return Base64.getEncoder().encodeToString(key.getEncoded());
	}

	public static PrivateKey decodePrivateKey(String base64Pkcs8) {
		try {
			byte[] der = Base64.getDecoder().decode(base64Pkcs8.trim());
			return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
		}
		catch (Exception e) {
			throw new TokenException("The configured signing private key could not be read", e);
		}
	}

	public static PublicKey decodePublicKey(String base64X509) {
		try {
			byte[] der = Base64.getDecoder().decode(base64X509.trim());
			return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
		}
		catch (Exception e) {
			throw new TokenException("The configured signing public key could not be read", e);
		}
	}

	/**
	 * Signs {@code claims} as an RS256 JWT. The caller owns every claim, including {@code exp} -
	 * this method adds nothing implicitly, so there is exactly one place (DelegatedTokenService)
	 * that decides what a delegated token asserts.
	 */
	public static String sign(Map<String, Object> claims, PrivateKey privateKey) {
		try {
			Map<String, Object> header = new LinkedHashMap<String, Object>();
			header.put("alg", ALGORITHM);
			header.put("typ", "JWT");

			String signingInput = base64Url(MAPPER.writeValueAsBytes(header)) + "."
					+ base64Url(MAPPER.writeValueAsBytes(claims));

			Signature signature = Signature.getInstance(JAVA_SIGNATURE_ALGORITHM);
			signature.initSign(privateKey);
			signature.update(signingInput.getBytes(UTF_8));

			return signingInput + "." + base64Url(signature.sign());
		}
		catch (Exception e) {
			throw new TokenException("Could not sign a delegated token", e);
		}
	}

	/**
	 * Verifies the signature, then the registered claims, then returns the payload. Anything
	 * that fails throws - there is no partial-trust return value a caller could accidentally use.
	 *
	 * @param nowEpochSeconds current time, passed in so tests do not depend on the wall clock
	 * @param clockSkewSeconds tolerance applied to both {@code exp} and {@code iat}
	 */
	public static Map<String, Object> verify(String token, PublicKey publicKey, String expectedIssuer,
			String expectedAudience, long nowEpochSeconds, long clockSkewSeconds) {
		if (token == null || token.trim().isEmpty()) {
			throw new TokenException("No delegated token was presented");
		}
		String[] parts = token.trim().split("\\.");
		if (parts.length != 3) {
			throw new TokenException("Delegated token is not a well-formed JWT");
		}

		Map<String, Object> header = readJson(parts[0], "header");
		// The algorithm is pinned rather than taken from the token: a token that asks to be
		// verified with "none", or with an HMAC keyed on the public key, is rejected outright.
		if (!ALGORITHM.equals(header.get("alg"))) {
			throw new TokenException("Delegated token is not signed with " + ALGORITHM);
		}

		try {
			Signature signature = Signature.getInstance(JAVA_SIGNATURE_ALGORITHM);
			signature.initVerify(publicKey);
			signature.update((parts[0] + "." + parts[1]).getBytes(UTF_8));
			if (!signature.verify(Base64.getUrlDecoder().decode(parts[2]))) {
				throw new TokenException("Delegated token signature does not verify");
			}
		}
		catch (TokenException e) {
			throw e;
		}
		catch (Exception e) {
			throw new TokenException("Delegated token signature could not be checked", e);
		}

		Map<String, Object> claims = readJson(parts[1], "payload");

		if (!expectedIssuer.equals(claims.get("iss"))) {
			throw new TokenException("Delegated token was issued by an unexpected party");
		}
		if (!expectedAudience.equals(claims.get("aud"))) {
			throw new TokenException("Delegated token was not issued for this audience");
		}

		Long expiry = asLong(claims.get("exp"));
		if (expiry == null) {
			throw new TokenException("Delegated token has no expiry");
		}
		if (nowEpochSeconds > expiry + clockSkewSeconds) {
			throw new TokenException("Delegated token has expired");
		}

		Long issuedAt = asLong(claims.get("iat"));
		if (issuedAt != null && issuedAt - clockSkewSeconds > nowEpochSeconds) {
			throw new TokenException("Delegated token is not valid yet");
		}

		Object subject = claims.get("sub");
		if (!(subject instanceof String) || ((String) subject).trim().isEmpty()) {
			throw new TokenException("Delegated token carries no subject");
		}

		return claims;
	}

	private static Map<String, Object> readJson(String base64UrlPart, String what) {
		try {
			byte[] json = Base64.getUrlDecoder().decode(base64UrlPart);
			@SuppressWarnings("unchecked")
			Map<String, Object> parsed = MAPPER.readValue(json, Map.class);
			return parsed;
		}
		catch (Exception e) {
			throw new TokenException("Delegated token " + what + " could not be read", e);
		}
	}

	private static Long asLong(Object value) {
		if (value instanceof Number) {
			return ((Number) value).longValue();
		}
		if (value instanceof String) {
			try {
				return Long.valueOf((String) value);
			}
			catch (NumberFormatException e) {
				return null;
			}
		}
		return null;
	}

	private static String base64Url(byte[] bytes) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}
}
