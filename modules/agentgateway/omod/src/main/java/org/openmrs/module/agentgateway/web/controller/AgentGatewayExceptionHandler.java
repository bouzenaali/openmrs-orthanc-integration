package org.openmrs.module.agentgateway.web.controller;

import org.openmrs.api.APIAuthenticationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns a failure in this module's own endpoints into a short, non-technical message. CA8 is
 * explicit that no raw error payload reaches the chat, and a stack trace rendered into a clinical
 * screen is both useless to the clinician and an information leak.
 */
@ControllerAdvice(assignableTypes = { ChatRelayController.class, AgentLogController.class, SigningKeyController.class })
public class AgentGatewayExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(AgentGatewayExceptionHandler.class);

	@ExceptionHandler(APIAuthenticationException.class)
	@ResponseBody
	public Map<String, Object> handleAuthorisation(APIAuthenticationException e, HttpServletResponse response) {
		response.setStatus(HttpServletResponse.SC_FORBIDDEN);
		log.info("agentgateway: refused a request for lack of a privilege: {}", e.getMessage());
		return message("Vous n'avez pas l'autorisation d'effectuer cette action.");
	}

	@ExceptionHandler(IllegalArgumentException.class)
	@ResponseBody
	public Map<String, Object> handleBadRequest(IllegalArgumentException e, HttpServletResponse response) {
		response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
		return message("La demande est incomplete ou invalide.");
	}

	@ExceptionHandler(Exception.class)
	@ResponseBody
	public Map<String, Object> handleUnexpected(Exception e, HttpServletResponse response) {
		response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
		log.error("agentgateway: unexpected failure in a gateway endpoint", e);
		return message("Une erreur inattendue est survenue.");
	}

	private Map<String, Object> message(String text) {
		Map<String, Object> body = new LinkedHashMap<String, Object>();
		body.put("success", false);
		body.put("message", text);
		return body;
	}
}
