package org.openmrs.module.agentgateway.rollback;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang.StringUtils;
import org.openmrs.module.agentgateway.api.dao.AgentGatewayDao;
import org.openmrs.module.agentgateway.api.model.AgentOperationLog;
import org.openmrs.module.agentgateway.http.HttpJsonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

/**
 * Decides whether a logged operation can be reversed without leaving the database incoherent,
 * and reverses it when it can (ADR-11, open question #4).
 * <p>
 * The rule is per-operation, not per-resource-type: the same kind of write can be safely
 * reversible in one case and not in another, depending entirely on what happened afterwards. So
 * every check here is a question about <em>this</em> row's aftermath - has the resource changed
 * since we wrote it, has the agent touched it again, does anything now depend on it - and the
 * answer to any of them being "yes, or I cannot tell" means the operation is handed to a human
 * with the full before/after detail rather than reversed on a guess.
 * <p>
 * Two asymmetries are deliberate. Reversing a <b>create</b> needs a dependency probe, because
 * voiding a record that something else now points at is exactly the incoherence this is meant to
 * prevent; there is no generic way to ask OpenMRS "what refers to this?", so a create is only
 * auto-reversible for resource types this class knows how to interrogate, and everything else is
 * declared manual instead of assumed safe. Reversing an <b>update</b> needs no such probe -
 * nothing is removed - but it must restore only the fields the agent actually changed, or it
 * would silently revert edits a human made to other fields in the meantime.
 */
public class RollbackEngine {

	private static final Logger log = LoggerFactory.getLogger(RollbackEngine.class);

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/**
	 * How much later than the audit row a resource's own "changed" timestamp may be before we
	 * treat it as somebody else's edit. The resource is always written a moment before the row
	 * that records it, so this only absorbs that ordering, not a real subsequent change.
	 */
	private static final long MODIFICATION_TOLERANCE_MS = 2000L;

	private static final List<String> DATE_PATTERNS = Arrays.asList("yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
			"yyyy-MM-dd'T'HH:mm:ss.SSSZ", "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ssZ",
			"yyyy-MM-dd'T'HH:mm:ss.SSS", "yyyy-MM-dd'T'HH:mm:ss");

	private final OpenmrsApiCaller caller;

	private final AgentGatewayDao dao;

	public RollbackEngine(OpenmrsApiCaller caller, AgentGatewayDao dao) {
		this.caller = caller;
		this.dao = dao;
	}

	// ------------------------------------------------------------------ public API

	/** Runs every coherence check without changing anything. */
	public RollbackResult evaluate(AgentOperationLog operation) {
		return process(operation, false);
	}

	/** Runs every coherence check and, if they all pass, issues the reversing call. */
	public RollbackResult rollback(AgentOperationLog operation) {
		return process(operation, true);
	}

	// ------------------------------------------------------------------ the rule

	private RollbackResult process(AgentOperationLog operation, boolean execute) {
		if (operation == null) {
			return RollbackResult.failed("No such logged operation");
		}
		if (operation.getDateRolledBack() != null) {
			return RollbackResult.nothingToDo("This operation has already been rolled back");
		}
		if (AgentOperationLog.TASK_TYPE_ROLLBACK.equals(operation.getTaskType())) {
			return RollbackResult.manual("This entry is itself a rollback; reversing a reversal has to be done by hand");
		}
		if (operation.getResponseStatus() == null || operation.getResponseStatus() < 200
				|| operation.getResponseStatus() >= 300) {
			return RollbackResult.nothingToDo("The original call did not succeed, so nothing was changed");
		}

		OperationTarget target = OperationTarget.parse(operation.getTargetEndpoint());
		if (target == null || target.getFamily() == OperationTarget.Family.OTHER) {
			return RollbackResult.manual("This operation did not go through the REST or FHIR API and cannot be "
					+ "reversed automatically");
		}
		if (!target.isWrite()) {
			return RollbackResult.nothingToDo("This was a read-only lookup");
		}
		if (target.getKind() == OperationTarget.Kind.DELETE) {
			return RollbackResult.manual("Restoring a voided record is not something the REST API exposes; "
					+ "an administrator has to unvoid it directly");
		}
		if (StringUtils.isBlank(operation.getResourceUuidAffected())) {
			return RollbackResult.manual("The affected record could not be identified from the response, so there "
					+ "is nothing safe to point a reversal at");
		}

		String instancePath = target.instancePath(operation.getResourceUuidAffected());
		if (instancePath == null) {
			return RollbackResult.manual("The affected record's address could not be reconstructed");
		}

		// Has the agent itself written to this record again since? If so, reversing this one in
		// isolation would restore a state that the later operation already moved on from.
		List<AgentOperationLog> laterTouches = dao
				.getOperationLogsForResourceAfter(operation.getResourceUuidAffected(), operation.getDateCreated());
		for (AgentOperationLog later : laterTouches) {
			OperationTarget laterTarget = OperationTarget.parse(later.getTargetEndpoint());
			if (laterTarget != null && laterTarget.isWrite() && later.getDateRolledBack() == null
					&& !AgentOperationLog.TASK_TYPE_ROLLBACK.equals(later.getTaskType())) {
				return RollbackResult.manual("A later agent operation (#" + later.getId() + ") also changed this "
						+ "record; roll that one back first");
			}
		}

		JsonNode currentState;
		try {
			HttpJsonClient.Response current = caller.call("GET", readBackPath(target, instancePath), null, true);
			if (current.getStatus() == 404 || current.getStatus() == 410) {
				return RollbackResult.nothingToDo("The record no longer exists or has already been voided");
			}
			if (!current.isSuccessful()) {
				return RollbackResult.manual("The record's current state could not be read back (HTTP "
						+ current.getStatus() + "), so no reversal can be checked for safety");
			}
			currentState = MAPPER.readTree(current.getBody());
		}
		catch (IOException e) {
			log.warn("Could not read back the current state of {}", instancePath, e);
			return RollbackResult.manual("OpenMRS could not be reached to check whether a reversal is safe");
		}

		if (isVoided(currentState)) {
			return RollbackResult.nothingToDo("The record has already been voided");
		}

		Date changedAt = lastModified(currentState);
		if (changedAt != null && operation.getDateCreated() != null
				&& changedAt.getTime() > operation.getDateCreated().getTime() + MODIFICATION_TOLERANCE_MS) {
			return RollbackResult.manual("The record has been edited since (last change " + changedAt
					+ "); reversing now would discard that edit");
		}

		if (target.getKind() == OperationTarget.Kind.CREATE) {
			return reverseCreate(operation, target, instancePath, execute);
		}
		return reverseUpdate(operation, target, instancePath, currentState, execute);
	}

	// ------------------------------------------------------------------ create

	private RollbackResult reverseCreate(AgentOperationLog operation, OperationTarget target, String instancePath,
			boolean execute) {
		String uuid = operation.getResourceUuidAffected();
		String resourceType = StringUtils.lowerCase(target.getResourceType());

		if ("patient".equals(resourceType) || "person".equals(resourceType)) {
			RollbackResult dependencies = probePatientDependencies(uuid);
			if (dependencies != null) {
				return dependencies;
			}
		} else if ("appointment".equals(resourceType)) {
			// Nothing structurally depends on an appointment, but one somebody has already acted
			// on is a different matter - voiding that erases a clinical fact, not a mistake.
			RollbackResult acted = probeAppointmentUntouched(instancePath);
			if (acted != null) {
				return acted;
			}
		} else {
			return RollbackResult.manual("There is no way to check what now depends on a " + target.getResourceType()
					+ " record, so voiding it automatically could orphan other data");
		}

		if (!execute) {
			return RollbackResult.reversible("Can be reversed by voiding the created record");
		}
		try {
			HttpJsonClient.Response response = caller.call("DELETE", instancePath, null, false);
			if (response.isSuccessful() || response.getStatus() == 204) {
				return RollbackResult.reversed("The created record was voided", null);
			}
			return RollbackResult.failed("OpenMRS refused to void the record (HTTP " + response.getStatus() + ")");
		}
		catch (IOException e) {
			log.warn("Reversing create of {} failed", instancePath, e);
			return RollbackResult.failed("OpenMRS could not be reached to void the record");
		}
	}

	/**
	 * A patient nobody has documented anything against is a duplicate that can be voided. One
	 * with a visit, an encounter or an observation is a patient with a clinical history, and
	 * voiding it would strand that history.
	 */
	private RollbackResult probePatientDependencies(String patientUuid) {
		String[][] probes = { { "encounter", "/ws/rest/v1/encounter?patient=" + patientUuid + "&limit=1" },
				{ "visit", "/ws/rest/v1/visit?patient=" + patientUuid + "&limit=1" },
				{ "observation", "/ws/rest/v1/obs?patient=" + patientUuid + "&limit=1" } };

		for (String[] probe : probes) {
			try {
				HttpJsonClient.Response response = caller.call("GET", probe[1], null, true);
				if (!response.isSuccessful()) {
					return RollbackResult.manual("Whether this patient has any " + probe[0]
							+ " records could not be checked (HTTP " + response.getStatus() + ")");
				}
				JsonNode results = MAPPER.readTree(response.getBody()).path("results");
				if (results.isArray() && results.size() > 0) {
					return RollbackResult.manual("This patient already has at least one " + probe[0]
							+ " recorded; voiding the patient would leave it orphaned");
				}
			}
			catch (IOException e) {
				log.warn("Dependency probe {} failed", probe[1], e);
				return RollbackResult.manual("OpenMRS could not be reached to check what depends on this patient");
			}
		}
		return null;
	}

	private RollbackResult probeAppointmentUntouched(String instancePath) {
		try {
			HttpJsonClient.Response response = caller.call("GET", instancePath, null, true);
			if (!response.isSuccessful()) {
				return RollbackResult.manual("The appointment's current status could not be read");
			}
			String status = MAPPER.readTree(response.getBody()).path("status").asText("");
			if (!"booked".equalsIgnoreCase(status) && !"proposed".equalsIgnoreCase(status)
					&& !"pending".equalsIgnoreCase(status)) {
				return RollbackResult.manual("This appointment is no longer simply booked (status: " + status
						+ "), so somebody has already acted on it");
			}
			return null;
		}
		catch (IOException e) {
			return RollbackResult.manual("OpenMRS could not be reached to check the appointment's status");
		}
	}

	// ------------------------------------------------------------------ update

	private RollbackResult reverseUpdate(AgentOperationLog operation, OperationTarget target, String instancePath,
			JsonNode currentState, boolean execute) {
		if (StringUtils.isBlank(operation.getPreviousState())) {
			return RollbackResult.manual("No before-image of this record was captured, so there is nothing to "
					+ "restore it to");
		}

		JsonNode before;
		try {
			before = MAPPER.readTree(operation.getPreviousState());
		}
		catch (IOException e) {
			return RollbackResult.manual("The stored before-image of this record could not be read");
		}

		String method;
		String body;
		if (target.getFamily() == OperationTarget.Family.FHIR) {
			// FHIR's PUT replaces the whole resource, and the before-image is a whole resource.
			method = "PUT";
			body = before.toString();
		} else {
			JsonNode restored = restoreOnlyChangedFields(operation.getRequestBody(), before);
			if (restored == null) {
				return RollbackResult.manual("The agent set a field that did not exist on this record before, and "
						+ "the REST API has no way to unset it again");
			}
			method = "POST";
			body = restored.toString();
		}

		if (!execute) {
			return RollbackResult.reversible("Can be reversed by writing the previous values back");
		}
		try {
			HttpJsonClient.Response response = caller.call(method, instancePath, body, false);
			if (response.isSuccessful()) {
				return RollbackResult.reversed("The previous values were written back", null);
			}
			return RollbackResult.failed("OpenMRS refused the reversing update (HTTP " + response.getStatus() + ")");
		}
		catch (IOException e) {
			log.warn("Reversing update of {} failed", instancePath, e);
			return RollbackResult.failed("OpenMRS could not be reached to write the previous values back");
		}
	}

	/**
	 * Builds a body that restores the previous value of every field the agent set, and touches
	 * nothing else. Reverting the whole before-image instead would quietly undo any edit a human
	 * made to a different field of the same record in the meantime.
	 *
	 * @return null if some field the agent set has no previous value to restore
	 */
	JsonNode restoreOnlyChangedFields(String requestBody, JsonNode before) {
		if (StringUtils.isBlank(requestBody)) {
			return null;
		}
		JsonNode written;
		try {
			written = MAPPER.readTree(requestBody);
		}
		catch (IOException e) {
			return null;
		}
		if (!written.isObject() || !before.isObject()) {
			return null;
		}

		ObjectNode restored = MAPPER.createObjectNode();
		Iterator<String> fields = written.fieldNames();
		while (fields.hasNext()) {
			String field = fields.next();
			if (!before.has(field)) {
				return null;
			}
			restored.set(field, before.get(field));
		}
		return restored.size() == 0 ? null : restored;
	}

	// ------------------------------------------------------------------ helpers

	/** REST needs an explicit representation to return the audit metadata this engine reads. */
	private String readBackPath(OperationTarget target, String instancePath) {
		if (target.getFamily() == OperationTarget.Family.REST) {
			return instancePath + (instancePath.contains("?") ? "&" : "?") + "v=full";
		}
		return instancePath;
	}

	private boolean isVoided(JsonNode state) {
		return state.path("voided").asBoolean(false) || state.path("retired").asBoolean(false)
				|| state.path("auditInfo").path("dateVoided").isTextual();
	}

	/** {@code meta.lastUpdated} on FHIR, {@code auditInfo.dateChanged} on REST. */
	Date lastModified(JsonNode state) {
		String raw = firstText(state.path("meta").path("lastUpdated"), state.path("auditInfo").path("dateChanged"),
				state.path("auditInfo").path("dateCreated"));
		return raw == null ? null : parseDate(raw);
	}

	private String firstText(JsonNode... candidates) {
		for (JsonNode candidate : candidates) {
			if (candidate != null && candidate.isTextual() && !candidate.asText().isEmpty()) {
				return candidate.asText();
			}
		}
		return null;
	}

	private Date parseDate(String raw) {
		for (String pattern : DATE_PATTERNS) {
			try {
				return new SimpleDateFormat(pattern).parse(raw);
			}
			catch (ParseException ignored) {
				// try the next shape
			}
		}
		log.debug("Could not parse timestamp '{}' from a resource representation", raw);
		return null;
	}
}
