package org.openmrs.module.agentgateway.api.impl;

import org.apache.commons.lang.StringUtils;
import org.openmrs.User;
import org.openmrs.api.context.Context;
import org.openmrs.api.impl.BaseOpenmrsService;
import org.openmrs.module.agentgateway.AgentGatewayConstants;
import org.openmrs.module.agentgateway.AgentGatewayPrivileges;
import org.openmrs.module.agentgateway.api.AgentGatewayService;
import org.openmrs.module.agentgateway.api.dao.AgentGatewayDao;
import org.openmrs.module.agentgateway.api.model.AgentOperationLog;
import org.openmrs.module.agentgateway.rollback.DelegatedApiCaller;
import org.openmrs.module.agentgateway.rollback.RollbackEngine;
import org.openmrs.module.agentgateway.rollback.RollbackResult;
import org.openmrs.module.agentgateway.security.DelegatedToken;
import org.openmrs.module.agentgateway.security.DelegatedTokenService;

import java.util.Date;
import java.util.List;
import java.util.UUID;

public class AgentGatewayServiceImpl extends BaseOpenmrsService implements AgentGatewayService {

	private AgentGatewayDao dao;

	public void setDao(AgentGatewayDao dao) {
		this.dao = dao;
	}

	// ---------------------------------------------------------------- tokens

	@Override
	public String mintDelegatedTokenForCurrentUser(String conversationId) {
		AgentGatewayPrivileges.requireChatUse();

		User user = Context.getAuthenticatedUser();
		if (user == null) {
			throw new IllegalStateException("A delegated token can only be minted for an authenticated user");
		}
		// The write capability is decided here, once, from the user's real privileges - not from
		// anything the agent or the browser says about itself.
		boolean mayWrite = Context.hasPrivilege(AgentGatewayPrivileges.CHAT_WRITE);

		return DelegatedTokenService.mint(DelegatedTokenService.subjectFor(user), user.getUuid(), conversationId, mayWrite,
				AgentGatewayConstants.PURPOSE_CHAT);
	}

	@Override
	public String mintDictationTokenForCurrentUser() {
		AgentGatewayPrivileges.requireVoiceUse();

		User user = Context.getAuthenticatedUser();
		if (user == null) {
			throw new IllegalStateException("A dictation token can only be minted for an authenticated user");
		}
		// mayWrite is false unconditionally, and there is no branch that could make it true.
		// Dictation returns text to the clinician's compose box; it reaches no OpenMRS API.
		return DelegatedTokenService.mintForAudience(DelegatedTokenService.subjectFor(user), user.getUuid(), null, false,
				AgentGatewayConstants.PURPOSE_STT, AgentGatewayConstants.TOKEN_AUDIENCE_STT);
	}

	@Override
	public DelegatedToken verifyDelegatedToken(String token) {
		return DelegatedTokenService.verify(token);
	}

	@Override
	public String getSigningPublicKey() {
		return DelegatedTokenService.getPublicKeyBase64();
	}

	// ---------------------------------------------------------------- audit trail

	@Override
	public AgentOperationLog recordOperation(AgentOperationLog operation) {
		if (operation == null) {
			throw new IllegalArgumentException("operation is required");
		}
		if (operation.getActingUser() == null) {
			throw new IllegalArgumentException("An operation must be attributable to a user");
		}
		if (StringUtils.isBlank(operation.getUuid())) {
			operation.setUuid(UUID.randomUUID().toString());
		}
		if (operation.getDateCreated() == null) {
			operation.setDateCreated(new Date());
		}
		if (operation.getCreator() == null) {
			operation.setCreator(operation.getActingUser());
		}
		if (operation.getUsingAgent() == null) {
			operation.setUsingAgent(Boolean.TRUE);
		}
		if (operation.getReversible() == null) {
			operation.setReversible(Boolean.FALSE);
		}
		return dao.saveOperationLog(operation);
	}

	@Override
	public AgentOperationLog getOperationLog(Integer id) {
		AgentGatewayPrivileges.requireRollback();
		return dao.getOperationLog(id);
	}

	@Override
	public List<AgentOperationLog> getOperationLogs(String conversationId, Boolean onlyReversible, int maxResults,
			int firstResult) {
		AgentGatewayPrivileges.requireRollback();
		return dao.getOperationLogs(conversationId, onlyReversible, maxResults, firstResult);
	}

	// ---------------------------------------------------------------- rollback

	@Override
	public RollbackResult evaluateRollback(Integer logId) {
		AgentGatewayPrivileges.requireRollback();
		AgentOperationLog operation = dao.getOperationLog(logId);
		if (operation == null) {
			return RollbackResult.failed("No such logged operation");
		}
		return newEngine(operation, false).evaluate(operation);
	}

	@Override
	public RollbackResult rollback(Integer logId) {
		AgentGatewayPrivileges.requireRollback();

		AgentOperationLog operation = dao.getOperationLog(logId);
		if (operation == null) {
			return RollbackResult.failed("No such logged operation");
		}

		RollbackResult result = newEngine(operation, true).rollback(operation);
		if (result.isReversed()) {
			// The reversing call is not stamped onto this row - it is written as its own log
			// entry by the audit filter, with reverses_log_id pointing back here, so the trail
			// stays append-only and shows both the mistake and the correction.
			operation.setRolledBackBy(Context.getAuthenticatedUser());
			operation.setDateRolledBack(new Date());
			dao.saveOperationLog(operation);
		}
		return result;
	}

	private RollbackEngine newEngine(AgentOperationLog operation, boolean allowWrites) {
		User admin = Context.getAuthenticatedUser();
		DelegatedApiCaller caller = allowWrites
				? new DelegatedApiCaller(DelegatedTokenService.subjectFor(admin), admin.getUuid(), operation.getConversationId(),
						AgentOperationLog.TASK_TYPE_ROLLBACK, operation.getId(),
						AgentGatewayConstants.PURPOSE_ROLLBACK)
				: DelegatedApiCaller.readOnly(DelegatedTokenService.subjectFor(admin), admin.getUuid(),
						operation.getConversationId());
		return new RollbackEngine(caller, dao);
	}
}
