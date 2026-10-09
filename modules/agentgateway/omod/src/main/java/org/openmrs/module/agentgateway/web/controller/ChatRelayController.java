package org.openmrs.module.agentgateway.web.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang.StringUtils;
import org.openmrs.api.context.Context;
import org.openmrs.module.agentgateway.AgentGatewayConfig;
import org.openmrs.module.agentgateway.AgentGatewayConstants;
import org.openmrs.module.agentgateway.AgentGatewayPrivileges;
import org.openmrs.module.agentgateway.api.AgentGatewayService;
import org.openmrs.module.agentgateway.http.HttpJsonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Relays one chat turn from the clinician's browser to the Clinical Agent Service, and the
 * answer back.
 * <p>
 * The browser never talks to the agent service itself (ADR-12). If it did, the shared channel
 * secret would have to be reachable from page JavaScript, which means it would not be secret -
 * anyone could read it out of devtools and call the agent directly, with OpenMRS's session and
 * privilege model entirely bypassed. So the browser talks same-origin to OpenMRS exactly as it
 * does for everything else, and this controller - already inside the authenticated session - is
 * the only thing that holds the secret.
 * <p>
 * The relayed request carries the clinician's identity in one place only: the signed delegated
 * token. There is deliberately no plaintext user id alongside it (ADR-13); a second, unsigned
 * assertion of "who is this" is exactly the kind of thing a downstream bug reads instead of the
 * verified one.
 */
@Controller
public class ChatRelayController {

	private static final Logger log = LoggerFactory.getLogger(ChatRelayController.class);

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** What the browser is allowed to see back. Anything else the agent says is dropped here. */
	private static final String[] PASSTHROUGH_FIELDS = { "reply", "state", "task_type", "pending_action",
			"conversation_id" };

	@RequestMapping(value = "/module/agentgateway/chat.form", method = RequestMethod.POST)
	@ResponseBody
	public Map<String, Object> chat(@RequestParam("message") String message,
			@RequestParam(value = "conversationId", required = false) String conversationId,
			@RequestParam(value = "patientUuid", required = false) String patientUuid) {

		AgentGatewayPrivileges.requireChatUse();

		Map<String, Object> result = new LinkedHashMap<String, Object>();
		String conversation = StringUtils.isBlank(conversationId) ? UUID.randomUUID().toString() : conversationId.trim();
		result.put("conversationId", conversation);

		if (StringUtils.isBlank(message)) {
			result.put("success", false);
			result.put("reply", "Merci de saisir une demande.");
			return result;
		}

		if (StringUtils.isBlank(AgentGatewayConfig.getChannelSecret())) {
			// Refusing to start rather than relaying unauthenticated: without the shared secret
			// the agent service has no way to tell this OpenMRS instance from anything else that
			// can reach it on the network.
			log.error("agentgateway: {} is not set; refusing to relay a chat turn",
					AgentGatewayConstants.GP_CHANNEL_SECRET);
			result.put("success", false);
			result.put("reply", "L'assistant n'est pas configure. Contactez l'administrateur.");
			return result;
		}

		String token;
		try {
			token = Context.getService(AgentGatewayService.class).mintDelegatedTokenForCurrentUser(conversation);
		}
		catch (Exception e) {
			log.error("agentgateway: could not mint a delegated token", e);
			result.put("success", false);
			result.put("reply", "L'assistant n'a pas pu verifier votre identite. Reconnectez-vous et reessayez.");
			return result;
		}

		try {
			ObjectNode payload = MAPPER.createObjectNode();
			payload.put("conversation_id", conversation);
			payload.put("prompt", message);
			payload.put("delegated_token", token);
			ObjectNode context = payload.putObject("context");
			if (StringUtils.isNotBlank(patientUuid)) {
				context.put("patient_uuid", patientUuid.trim());
			}
			context.put("locale", Context.getLocale() == null ? "fr" : Context.getLocale().getLanguage());

			Map<String, String> headers = new HashMap<String, String>();
			headers.put(AgentGatewayConstants.HEADER_CHANNEL_SECRET, AgentGatewayConfig.getChannelSecret());
			headers.put("Content-Type", "application/json;charset=UTF-8");

			HttpJsonClient.Response response = HttpJsonClient.request("POST",
					AgentGatewayConfig.getAgentServiceUrl() + "/chat", headers, payload.toString(),
					AgentGatewayConfig.getAgentTimeoutMillis());

			if (!response.isSuccessful()) {
				log.warn("agentgateway: the agent service answered HTTP {}", response.getStatus());
				result.put("success", false);
				result.put("reply", "L'assistant n'a pas pu traiter votre demande. Reessayez dans un instant.");
				return result;
			}

			result.put("success", true);
			copyPassthroughFields(MAPPER.readTree(response.getBody()), result);
			return result;
		}
		catch (IOException e) {
			// Graceful degradation: the assistant being down must never look like OpenMRS being
			// down. Everything else on this page keeps working.
			log.warn("agentgateway: the agent service could not be reached", e);
			result.put("success", false);
			result.put("reply", "L'assistant est momentanement indisponible.");
			return result;
		}
		catch (Exception e) {
			log.error("agentgateway: unexpected failure relaying a chat turn", e);
			result.put("success", false);
			result.put("reply", "L'assistant a rencontre une erreur inattendue.");
			return result;
		}
	}

	private void copyPassthroughFields(JsonNode agentResponse, Map<String, Object> result) {
		for (String field : PASSTHROUGH_FIELDS) {
			JsonNode value = agentResponse.get(field);
			if (value == null || value.isNull()) {
				continue;
			}
			if (value.isTextual()) {
				result.put(field, value.asText());
			} else if (value.isObject() || value.isArray()) {
				result.put(field, MAPPER.convertValue(value, Object.class));
			} else {
				result.put(field, value.asText());
			}
		}
	}
}
