package org.openmrs.module.agentgateway.security;

/**
 * A delegated token could not be produced, or could not be trusted. The message is written for
 * an administrator reading the server log, never for the chat panel - CA8 requires the clinician
 * to see a plain-language reason instead.
 */
public class TokenException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public TokenException(String message) {
		super(message);
	}

	public TokenException(String message, Throwable cause) {
		super(message, cause);
	}
}
