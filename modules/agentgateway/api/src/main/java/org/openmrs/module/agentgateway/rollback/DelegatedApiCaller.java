package org.openmrs.module.agentgateway.rollback;

import org.openmrs.module.agentgateway.AgentGatewayConfig;
import org.openmrs.module.agentgateway.AgentGatewayConstants;
import org.openmrs.module.agentgateway.http.HttpJsonClient;
import org.openmrs.module.agentgateway.security.DelegatedTokenService;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Calls OpenMRS's own REST/FHIR surface over HTTP as a named user, using a freshly minted
 * delegated token. Used for the two calls this module makes to itself: reading a resource's
 * before-state, and issuing a reversing call during rollback.
 * <p>
 * Going out and back in through the front door - rather than short-cutting to the service layer -
 * means these calls are privilege-checked and audited by the same filter as everything else, so
 * there is no second, unaudited route into the database to keep secure.
 */
public class DelegatedApiCaller implements OpenmrsApiCaller {

	private final String username;

	private final String userUuid;

	private final String conversationId;

	private final String taskType;

	private final Integer reversesLogId;

	private final String writePurpose;

	/**
	 * @param writePurpose the token purpose to use for calls that change something - normally
	 *            {@code PURPOSE_ROLLBACK}. Read calls always use the read-only purpose, so a
	 *            caller that only ever reads cannot be tricked into holding a write token.
	 */
	public DelegatedApiCaller(String username, String userUuid, String conversationId, String taskType,
			Integer reversesLogId, String writePurpose) {
		this.username = username;
		this.userUuid = userUuid;
		this.conversationId = conversationId;
		this.taskType = taskType;
		this.reversesLogId = reversesLogId;
		this.writePurpose = writePurpose;
	}

	/** A caller that can only read - used for before-state capture. */
	public static DelegatedApiCaller readOnly(String username, String userUuid, String conversationId) {
		return new DelegatedApiCaller(username, userUuid, conversationId, null, null, null);
	}

	@Override
	public HttpJsonClient.Response call(String method, String pathWithinContext, String body, boolean readOnly)
			throws IOException {
		if (!readOnly && writePurpose == null) {
			throw new IllegalStateException("This caller was created read-only and cannot issue " + method);
		}

		String purpose = readOnly ? AgentGatewayConstants.PURPOSE_INTERNAL_READ : writePurpose;
		String token = DelegatedTokenService.mint(username, userUuid, conversationId, !readOnly, purpose);

		Map<String, String> headers = new HashMap<String, String>();
		headers.put(AgentGatewayConstants.HEADER_AGENT_TOKEN, token);
		if (conversationId != null) {
			headers.put(AgentGatewayConstants.HEADER_CONVERSATION_ID, conversationId);
		}
		if (readOnly) {
			headers.put(AgentGatewayConstants.HEADER_INTERNAL_CALL, "true");
		} else {
			if (taskType != null) {
				headers.put(AgentGatewayConstants.HEADER_TASK_TYPE, taskType);
			}
			if (reversesLogId != null) {
				headers.put(AgentGatewayConstants.HEADER_REVERSES_LOG_ID, String.valueOf(reversesLogId));
			}
		}

		// Through the relay prefix, for the same reason the agent uses it: a direct call to
		// /ws/fhir2/* is answered 401 by fhir2's own filter before this module can authenticate
		// anyone. Without this, before-state capture (CA9) and rollback (CA10) fail silently.
		String url = AgentGatewayConfig.getSelfBaseUrl() + AgentGatewayConstants.RELAY_PATH_PREFIX
				+ pathWithinContext;
		return HttpJsonClient.request(method, url, headers, body, AgentGatewayConfig.getAgentTimeoutMillis());
	}
}
