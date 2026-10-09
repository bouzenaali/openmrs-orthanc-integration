package org.openmrs.module.agentgateway.web.controller;

import org.apache.commons.lang.StringUtils;
import org.openmrs.User;
import org.openmrs.api.context.Context;
import org.openmrs.module.agentgateway.AgentGatewayPrivileges;
import org.openmrs.module.agentgateway.api.AgentGatewayService;
import org.openmrs.module.agentgateway.api.model.AgentOperationLog;
import org.openmrs.module.agentgateway.rollback.RollbackResult;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The administrator's view of what the assistant has done, and the one way to reverse any of it.
 * Every method here is gated on {@code App: agentgateway.rollback} - never self-service for the
 * clinician who made the original request (ADR-11).
 */
@Controller
public class AgentLogController {

	@RequestMapping(value = "/module/agentgateway/log.form", method = RequestMethod.GET)
	@ResponseBody
	public Map<String, Object> list(@RequestParam(value = "conversationId", required = false) String conversationId,
			@RequestParam(value = "onlyReversible", required = false) Boolean onlyReversible,
			@RequestParam(value = "limit", required = false) Integer limit,
			@RequestParam(value = "startIndex", required = false) Integer startIndex) {

		AgentGatewayPrivileges.requireRollback();

		List<AgentOperationLog> operations = Context.getService(AgentGatewayService.class).getOperationLogs(
				StringUtils.trimToNull(conversationId), onlyReversible, limit == null ? 50 : limit,
				startIndex == null ? 0 : startIndex);

		List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
		for (AgentOperationLog operation : operations) {
			rows.add(summarise(operation));
		}

		Map<String, Object> response = new LinkedHashMap<String, Object>();
		response.put("success", true);
		response.put("results", rows);
		return response;
	}

	@RequestMapping(value = "/module/agentgateway/logEntry.form", method = RequestMethod.GET)
	@ResponseBody
	public Map<String, Object> detail(@RequestParam("logId") Integer logId) {
		AgentGatewayPrivileges.requireRollback();

		AgentOperationLog operation = Context.getService(AgentGatewayService.class).getOperationLog(logId);
		Map<String, Object> response = new LinkedHashMap<String, Object>();
		if (operation == null) {
			response.put("success", false);
			response.put("message", "Operation introuvable");
			return response;
		}

		Map<String, Object> detail = summarise(operation);
		// The full before/after is the whole point of the detail view: it is what an
		// administrator works from when an operation turns out to need fixing by hand.
		detail.put("rawPrompt", operation.getRawPrompt());
		detail.put("requestBody", operation.getRequestBody());
		detail.put("responseBody", operation.getResponseBody());
		detail.put("previousState", operation.getPreviousState());

		response.put("success", true);
		response.put("operation", detail);
		return response;
	}

	/** Dry run: says whether a rollback would be attempted, and why not when it would not. */
	@RequestMapping(value = "/module/agentgateway/rollbackCheck.form", method = RequestMethod.GET)
	@ResponseBody
	public Map<String, Object> check(@RequestParam("logId") Integer logId) {
		AgentGatewayPrivileges.requireRollback();
		return describe(Context.getService(AgentGatewayService.class).evaluateRollback(logId));
	}

	@RequestMapping(value = "/module/agentgateway/rollback.form", method = RequestMethod.POST)
	@ResponseBody
	public Map<String, Object> rollback(@RequestParam("logId") Integer logId) {
		AgentGatewayPrivileges.requireRollback();
		return describe(Context.getService(AgentGatewayService.class).rollback(logId));
	}

	private Map<String, Object> describe(RollbackResult result) {
		Map<String, Object> response = new LinkedHashMap<String, Object>();
		response.put("success", result.getOutcome() != RollbackResult.Outcome.FAILED);
		response.put("outcome", result.getOutcome().name());
		response.put("reason", result.getReason());
		return response;
	}

	private Map<String, Object> summarise(AgentOperationLog operation) {
		Map<String, Object> row = new LinkedHashMap<String, Object>();
		row.put("id", operation.getId());
		row.put("uuid", operation.getUuid());
		row.put("conversationId", operation.getConversationId());
		row.put("actingUser", describeUser(operation.getActingUser()));
		row.put("taskType", operation.getTaskType());
		row.put("targetEndpoint", operation.getTargetEndpoint());
		row.put("responseStatus", operation.getResponseStatus());
		row.put("resourceUuidAffected", operation.getResourceUuidAffected());
		row.put("usingAgent", operation.getUsingAgent());
		row.put("reversible", operation.getReversible());
		row.put("reversibilityNote", operation.getReversibilityNote());
		row.put("reversesLogId", operation.getReversesLogId());
		row.put("rolledBackBy", describeUser(operation.getRolledBackBy()));
		row.put("dateRolledBack", formatTimestamp(operation.getDateRolledBack()));
		row.put("dateCreated", formatTimestamp(operation.getDateCreated()));
		return row;
	}

	/**
	 * Who an operation ran as, for a human to read.
	 * <p>
	 * Username first, system id when the account has no username - which is not an edge case here:
	 * OpenMRS does not require one, and this installation's own administrator account has none. The
	 * column rendered blank for every row until this fell back, making the log look as though it had
	 * not recorded who acted. It had; it just could not say so.
	 */
	private String describeUser(User user) {
		if (user == null) {
			return null;
		}
		if (StringUtils.isNotBlank(user.getUsername())) {
			return user.getUsername();
		}
		if (StringUtils.isNotBlank(user.getSystemId())) {
			return user.getSystemId();
		}
		return user.getUuid();
	}

	/**
	 * A timestamp an administrator can read, rather than epoch milliseconds.
	 * <p>
	 * Serialised as a string on purpose: the audit log is read by a person deciding whether to
	 * reverse something, and "1787072923000" is not a date. Rendered in the server's own zone, since
	 * that is the one the hospital works in.
	 */
	private String formatTimestamp(Date when) {
		return when == null ? null : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(when);
	}
}
