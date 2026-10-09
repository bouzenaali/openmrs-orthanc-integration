package org.openmrs.module.agentgateway.api;

import org.openmrs.annotation.Authorized;
import org.openmrs.api.OpenmrsService;
import org.openmrs.module.agentgateway.AgentGatewayPrivileges;
import org.openmrs.module.agentgateway.api.model.AgentOperationLog;
import org.openmrs.module.agentgateway.rollback.RollbackResult;
import org.openmrs.module.agentgateway.security.DelegatedToken;

import java.util.List;

/**
 * The whole OpenMRS-side API of the agent gateway: mint a delegated token, verify one, record
 * what the agent did, and let an administrator review and reverse it. No clinical operation of
 * any kind lives here - those all go through OpenMRS's own REST and FHIR endpoints, under the
 * clinician's own privileges (ADR-1, ADR-5).
 * <p>
 * The {@code @Authorized} annotations are belt-and-braces. On a Reference Application install the
 * real boundary is the explicit privilege check each web-layer entry point performs, because
 * plain API-level privileges are auto-granted to every role there; these annotations matter for
 * distributions that do not follow that convention.
 */
public interface AgentGatewayService extends OpenmrsService {

	/**
	 * Mints a short-lived token asserting that the current session user is who they are, and
	 * whether writes may be executed on their behalf.
	 *
	 * @param conversationId the chat conversation this turn belongs to
	 */
	@Authorized({ AgentGatewayPrivileges.CHAT_USE })
	String mintDelegatedTokenForCurrentUser(String conversationId);

	/**
	 * Mints a short-lived token naming the current user for the dictation service.
	 * <p>
	 * Deliberately different from {@link #mintDelegatedTokenForCurrentUser} in three ways: a
	 * different audience, a different purpose, and {@code may_write} always false. Dictation turns
	 * audio into text and touches no OpenMRS API, so there is nothing for a write capability to
	 * authorise - and a token that cannot write is one less thing to worry about if it leaks.
	 */
	@Authorized({ AgentGatewayPrivileges.VOICE_USE })
	String mintDictationTokenForCurrentUser();

	/**
	 * @throws org.openmrs.module.agentgateway.security.TokenException if the token is missing,
	 *             malformed, unsigned by this instance, expired, or for another audience
	 */
	DelegatedToken verifyDelegatedToken(String token);

	/** The public half of the signing key, for the agent service to verify what it is handed. */
	String getSigningPublicKey();

	/**
	 * Appends one operation to the audit trail. Called by the audit filter after the call it
	 * describes has already completed, in its own transaction, so that a problem writing the
	 * audit row can never roll back the clinical write it was recording - or the reverse.
	 */
	AgentOperationLog recordOperation(AgentOperationLog operation);

	@Authorized({ AgentGatewayPrivileges.ROLLBACK })
	AgentOperationLog getOperationLog(Integer id);

	@Authorized({ AgentGatewayPrivileges.ROLLBACK })
	List<AgentOperationLog> getOperationLogs(String conversationId, Boolean onlyReversible, int maxResults,
			int firstResult);

	/**
	 * Runs the coherence checks for a logged operation without changing anything, so an
	 * administrator can see whether a rollback is on the table before triggering one.
	 */
	@Authorized({ AgentGatewayPrivileges.ROLLBACK })
	RollbackResult evaluateRollback(Integer logId);

	/**
	 * Attempts to reverse a logged operation. Administrator-only, one operation at a time, never
	 * self-service for the clinician who made the original request (ADR-11).
	 */
	@Authorized({ AgentGatewayPrivileges.ROLLBACK })
	RollbackResult rollback(Integer logId);
}
