package org.openmrs.module.agentgateway.api.dao;

import org.openmrs.module.agentgateway.api.model.AgentOperationLog;

import java.util.Date;
import java.util.List;

public interface AgentGatewayDao {

	AgentOperationLog saveOperationLog(AgentOperationLog log);

	AgentOperationLog getOperationLog(Integer id);

	AgentOperationLog getOperationLogByUuid(String uuid);

	/**
	 * Most recent first. {@code conversationId} and {@code onlyReversible} are optional filters.
	 */
	List<AgentOperationLog> getOperationLogs(String conversationId, Boolean onlyReversible, int maxResults,
			int firstResult);

	/**
	 * Every logged operation that touched {@code resourceUuid} strictly after {@code after}.
	 * Used by the coherence check to spot a resource an agent has written to again since.
	 */
	List<AgentOperationLog> getOperationLogsForResourceAfter(String resourceUuid, Date after);
}
