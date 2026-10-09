package org.openmrs.module.agentgateway.web.controller;

import org.apache.commons.lang.StringUtils;
import org.openmrs.api.context.Context;
import org.openmrs.module.agentgateway.AgentGatewayConfig;
import org.openmrs.module.agentgateway.AgentGatewayConstants;
import org.openmrs.module.agentgateway.api.AgentGatewayService;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.servlet.http.HttpServletResponse;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Hands the agent service the public half of the delegated-token signing key, so it can verify
 * the identity in a token instead of taking it on trust (ADR-13).
 * <p>
 * A public key is not a secret, but the endpoint is still gated on the shared channel secret: it
 * is a server-to-server endpoint, and leaving it open would tell anyone who can reach OpenMRS
 * that this instance runs an agent and which key it signs with. The comparison is
 * length-independent and constant-time, so it cannot be used as an oracle to guess the secret.
 * <p>
 * Deployments that would rather not expose it at all can copy the key into the agent's
 * configuration directly - the agent prefers a configured key and only falls back to this.
 */
@Controller
public class SigningKeyController {

	@RequestMapping(value = "/module/agentgateway/publicKey.form", method = RequestMethod.GET)
	@ResponseBody
	public Map<String, Object> publicKey(
			@RequestHeader(value = AgentGatewayConstants.HEADER_CHANNEL_SECRET, required = false) String presentedSecret,
			HttpServletResponse response) {

		Map<String, Object> result = new LinkedHashMap<String, Object>();

		String configured = AgentGatewayConfig.getChannelSecret();
		if (StringUtils.isBlank(configured) || !constantTimeEquals(configured, presentedSecret)) {
			response.setStatus(HttpServletResponse.SC_FORBIDDEN);
			result.put("success", false);
			result.put("message", "Not authorised");
			return result;
		}

		result.put("success", true);
		result.put("algorithm", "RS256");
		result.put("format", "X.509/base64");
		result.put("publicKey", Context.getService(AgentGatewayService.class).getSigningPublicKey());
		return result;
	}

	private boolean constantTimeEquals(String expected, String presented) {
		if (presented == null) {
			return false;
		}
		byte[] a = sha256(expected);
		byte[] b = sha256(presented);
		return MessageDigest.isEqual(a, b);
	}

	private byte[] sha256(String value) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(value.getBytes("UTF-8"));
		}
		catch (Exception e) {
			throw new IllegalStateException("SHA-256 is unavailable in this JVM", e);
		}
	}
}
