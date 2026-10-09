package org.openmrs.module.agentgateway.api.model;

import org.openmrs.User;

import java.util.Date;

/**
 * One agent-originated call to OpenMRS's REST/FHIR surface, recorded whether it succeeded or
 * failed (CA9). Append-only in the same sense as every other entity in this system: a rollback
 * never edits the original row's meaning, it stamps the row with who reversed it and when, and
 * the reversing call itself is written as its own new row.
 * <p>
 * Note that this table necessarily contains PHI - the clinician's prompt and the data it
 * produced - because that is the only thing that makes an administrator's review and rollback
 * possible. That is why it lives here, on the existing system of record governed by OpenMRS's
 * own access controls, and not on the agent's server.
 */
public class AgentOperationLog {

	/** Set on a row that reverses another row, pointing back at the row it reversed. */
	public static final String TASK_TYPE_ROLLBACK = "rollback";

	private Integer id;

	private String uuid;

	private String conversationId;

	private User actingUser;

	private String rawPrompt;

	private String taskType;

	private String targetEndpoint;

	private String requestBody;

	private Integer responseStatus;

	private String responseBody;

	/** The resource's state immediately before this call overwrote it, when one was captured. */
	private String previousState;

	private String resourceUuidAffected;

	private Boolean usingAgent = Boolean.TRUE;

	/**
	 * Whether this module believes it could generate a reverse operation for this row. Evaluated
	 * again, against live data, at the moment a rollback is actually attempted - a row that was
	 * reversible when written may not be an hour later.
	 */
	private Boolean reversible = Boolean.FALSE;

	/** Why {@link #reversible} is false, or which reverse strategy applies when it is true. */
	private String reversibilityNote;

	private User rolledBackBy;

	private Date dateRolledBack;

	private Integer reversesLogId;

	private User creator;

	private Date dateCreated;

	public Integer getId() {
		return id;
	}

	public void setId(Integer id) {
		this.id = id;
	}

	public String getUuid() {
		return uuid;
	}

	public void setUuid(String uuid) {
		this.uuid = uuid;
	}

	public String getConversationId() {
		return conversationId;
	}

	public void setConversationId(String conversationId) {
		this.conversationId = conversationId;
	}

	public User getActingUser() {
		return actingUser;
	}

	public void setActingUser(User actingUser) {
		this.actingUser = actingUser;
	}

	public String getRawPrompt() {
		return rawPrompt;
	}

	public void setRawPrompt(String rawPrompt) {
		this.rawPrompt = rawPrompt;
	}

	public String getTaskType() {
		return taskType;
	}

	public void setTaskType(String taskType) {
		this.taskType = taskType;
	}

	public String getTargetEndpoint() {
		return targetEndpoint;
	}

	public void setTargetEndpoint(String targetEndpoint) {
		this.targetEndpoint = targetEndpoint;
	}

	public String getRequestBody() {
		return requestBody;
	}

	public void setRequestBody(String requestBody) {
		this.requestBody = requestBody;
	}

	public Integer getResponseStatus() {
		return responseStatus;
	}

	public void setResponseStatus(Integer responseStatus) {
		this.responseStatus = responseStatus;
	}

	public String getResponseBody() {
		return responseBody;
	}

	public void setResponseBody(String responseBody) {
		this.responseBody = responseBody;
	}

	public String getPreviousState() {
		return previousState;
	}

	public void setPreviousState(String previousState) {
		this.previousState = previousState;
	}

	public String getResourceUuidAffected() {
		return resourceUuidAffected;
	}

	public void setResourceUuidAffected(String resourceUuidAffected) {
		this.resourceUuidAffected = resourceUuidAffected;
	}

	public Boolean getUsingAgent() {
		return usingAgent;
	}

	public void setUsingAgent(Boolean usingAgent) {
		this.usingAgent = usingAgent;
	}

	public Boolean getReversible() {
		return reversible;
	}

	public void setReversible(Boolean reversible) {
		this.reversible = reversible;
	}

	public String getReversibilityNote() {
		return reversibilityNote;
	}

	public void setReversibilityNote(String reversibilityNote) {
		this.reversibilityNote = reversibilityNote;
	}

	public User getRolledBackBy() {
		return rolledBackBy;
	}

	public void setRolledBackBy(User rolledBackBy) {
		this.rolledBackBy = rolledBackBy;
	}

	public Date getDateRolledBack() {
		return dateRolledBack;
	}

	public void setDateRolledBack(Date dateRolledBack) {
		this.dateRolledBack = dateRolledBack;
	}

	public Integer getReversesLogId() {
		return reversesLogId;
	}

	public void setReversesLogId(Integer reversesLogId) {
		this.reversesLogId = reversesLogId;
	}

	public User getCreator() {
		return creator;
	}

	public void setCreator(User creator) {
		this.creator = creator;
	}

	public Date getDateCreated() {
		return dateCreated;
	}

	public void setDateCreated(Date dateCreated) {
		this.dateCreated = dateCreated;
	}
}
