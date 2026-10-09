package org.openmrs.module.agentgateway.rollback;

import org.junit.Before;
import org.junit.Test;
import org.openmrs.module.agentgateway.api.dao.AgentGatewayDao;
import org.openmrs.module.agentgateway.api.model.AgentOperationLog;
import org.openmrs.module.agentgateway.http.HttpJsonClient;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The coherence rule (ADR-11, open question #4), which is the difference between an undo and a
 * data-loss incident.
 * <p>
 * The bias these tests pin down is that "I cannot tell" always resolves to "a human has to do
 * this", never to "go ahead". Every case where the engine lacks information - no before-image, an
 * unreadable current state, a resource type it cannot interrogate for dependents - is asserted to
 * end in MANUAL_INTERVENTION_REQUIRED rather than an attempted reversal.
 */
public class RollbackEngineTest {

	private RecordingCaller caller;

	private StubDao dao;

	private RollbackEngine engine;

	/** 2025-10-09T08:53:20Z. The fixtures' resource timestamps are expressed against this. */
	private static final Date WRITTEN_AT = new Date(1_760_000_000_000L);

	@Before
	public void setUp() {
		caller = new RecordingCaller();
		dao = new StubDao();
		engine = new RollbackEngine(caller, dao);
	}

	// ------------------------------------------------------------------ nothing to do

	@Test
	public void anOperationAlreadyRolledBackIsNotRolledBackTwice() {
		AgentOperationLog operation = create("POST /ws/fhir2/R4/Patient", "abc-123");
		operation.setDateRolledBack(new Date());

		assertEquals(RollbackResult.Outcome.NOTHING_TO_DO, engine.evaluate(operation).getOutcome());
	}

	@Test
	public void aCallThatFailedChangedNothingToReverse() {
		AgentOperationLog operation = create("POST /ws/fhir2/R4/Patient", "abc-123");
		operation.setResponseStatus(403);

		assertEquals(RollbackResult.Outcome.NOTHING_TO_DO, engine.evaluate(operation).getOutcome());
	}

	@Test
	public void aLookupHasNothingToReverse() {
		assertEquals(RollbackResult.Outcome.NOTHING_TO_DO,
				engine.evaluate(create("GET /ws/fhir2/R4/Patient?name=Benali", null)).getOutcome());
	}

	@Test
	public void aRecordThatIsAlreadyGoneNeedsNothing() {
		caller.respond("GET", "/ws/fhir2/R4/Patient/abc-123", 404, "{}");

		assertEquals(RollbackResult.Outcome.NOTHING_TO_DO,
				engine.evaluate(create("POST /ws/fhir2/R4/Patient", "abc-123")).getOutcome());
	}

	@Test
	public void aRecordThatHasAlreadyBeenVoidedNeedsNothing() {
		caller.respond("GET", "/ws/fhir2/R4/Patient/abc-123", 200, "{\"id\":\"abc-123\",\"voided\":true}");

		assertEquals(RollbackResult.Outcome.NOTHING_TO_DO,
				engine.evaluate(create("POST /ws/fhir2/R4/Patient", "abc-123")).getOutcome());
	}

	// ------------------------------------------------------------------ create

	@Test
	public void aCreatedPatientWithNoClinicalHistoryCanBeVoided() {
		givenPatientExistsUnchanged();
		givenNoDependents();

		RollbackResult result = engine.evaluate(create("POST /ws/fhir2/R4/Patient", "abc-123"));

		assertEquals(RollbackResult.Outcome.REVERSIBLE, result.getOutcome());
	}

	@Test
	public void rollingBackACreateIssuesADeleteAndSaysSo() {
		givenPatientExistsUnchanged();
		givenNoDependents();
		caller.respond("DELETE", "/ws/fhir2/R4/Patient/abc-123", 204, "");

		RollbackResult result = engine.rollback(create("POST /ws/fhir2/R4/Patient", "abc-123"));

		assertEquals(RollbackResult.Outcome.REVERSED, result.getOutcome());
		assertTrue(caller.made("DELETE", "/ws/fhir2/R4/Patient/abc-123"));
	}

	@Test
	public void aCreatedPatientWithAnEncounterIsNotVoidedAutomatically() {
		givenPatientExistsUnchanged();
		caller.respond("GET", "/ws/rest/v1/encounter", 200, "{\"results\":[{\"uuid\":\"enc-1\"}]}");
		caller.respond("GET", "/ws/rest/v1/visit", 200, "{\"results\":[]}");
		caller.respond("GET", "/ws/rest/v1/obs", 200, "{\"results\":[]}");

		RollbackResult result = engine.evaluate(create("POST /ws/fhir2/R4/Patient", "abc-123"));

		assertEquals(RollbackResult.Outcome.MANUAL_INTERVENTION_REQUIRED, result.getOutcome());
		assertTrue(result.getReason().contains("encounter"));
	}

	@Test
	public void aDependencyProbeThatCannotBeAnsweredBlocksTheRollback() {
		givenPatientExistsUnchanged();
		caller.respond("GET", "/ws/rest/v1/encounter", 500, "{}");

		assertEquals(RollbackResult.Outcome.MANUAL_INTERVENTION_REQUIRED,
				engine.evaluate(create("POST /ws/fhir2/R4/Patient", "abc-123")).getOutcome());
	}

	@Test
	public void aResourceTypeWithNoDependencyProbeIsNeverVoidedOnAGuess() {
		caller.respond("GET", "/ws/fhir2/R4/Observation/obs-9", 200,
				"{\"id\":\"obs-9\",\"meta\":{\"lastUpdated\":\"2025-10-09T08:53:20.000+00:00\"}}");

		RollbackResult result = engine.evaluate(create("POST /ws/fhir2/R4/Observation", "obs-9"));

		assertEquals(RollbackResult.Outcome.MANUAL_INTERVENTION_REQUIRED, result.getOutcome());
		assertTrue(result.getReason().contains("depends on"));
	}

	// ------------------------------------------------------------------ update

	@Test
	public void anUpdateIsReversedByRestoringOnlyTheFieldsTheAgentChanged() {
		caller.respond("GET", "/ws/rest/v1/person/p-1", 200,
				"{\"uuid\":\"p-1\",\"gender\":\"M\",\"birthdate\":\"1978-04-03\","
						+ "\"auditInfo\":{\"dateChanged\":\"2025-10-09T08:53:20.000+0000\"}}");
		caller.respond("POST", "/ws/rest/v1/person/p-1", 200, "{\"uuid\":\"p-1\"}");

		AgentOperationLog operation = create("POST /ws/rest/v1/person/p-1", "p-1");
		operation.setRequestBody("{\"gender\":\"F\"}");
		operation.setPreviousState("{\"uuid\":\"p-1\",\"gender\":\"M\",\"birthdate\":\"1978-04-03\"}");

		RollbackResult result = engine.rollback(operation);

		assertEquals(RollbackResult.Outcome.REVERSED, result.getOutcome());
		// Only the field the agent touched is written back. Restoring the whole before-image
		// would silently undo any edit a human made to another field in the meantime.
		assertEquals("{\"gender\":\"M\"}", caller.bodyOf("POST", "/ws/rest/v1/person/p-1"));
	}

	@Test
	public void aFhirUpdateIsReversedByPuttingTheWholeBeforeImageBack() {
		caller.respond("GET", "/ws/fhir2/R4/Patient/abc-123", 200,
				"{\"id\":\"abc-123\",\"meta\":{\"lastUpdated\":\"2025-10-09T08:53:20.000+00:00\"}}");
		caller.respond("PUT", "/ws/fhir2/R4/Patient/abc-123", 200, "{\"id\":\"abc-123\"}");

		AgentOperationLog operation = create("PUT /ws/fhir2/R4/Patient/abc-123", "abc-123");
		operation.setRequestBody("{\"resourceType\":\"Patient\",\"gender\":\"female\"}");
		operation.setPreviousState("{\"resourceType\":\"Patient\",\"id\":\"abc-123\",\"gender\":\"male\"}");

		assertEquals(RollbackResult.Outcome.REVERSED, engine.rollback(operation).getOutcome());
		assertTrue(caller.bodyOf("PUT", "/ws/fhir2/R4/Patient/abc-123").contains("\"gender\":\"male\""));
	}

	@Test
	public void anUpdateWithNoBeforeImageCannotBeReversed() {
		caller.respond("GET", "/ws/fhir2/R4/Patient/abc-123", 200,
				"{\"id\":\"abc-123\",\"meta\":{\"lastUpdated\":\"2025-10-09T08:53:20.000+00:00\"}}");

		AgentOperationLog operation = create("PUT /ws/fhir2/R4/Patient/abc-123", "abc-123");
		operation.setRequestBody("{\"gender\":\"female\"}");

		RollbackResult result = engine.evaluate(operation);

		assertEquals(RollbackResult.Outcome.MANUAL_INTERVENTION_REQUIRED, result.getOutcome());
		assertTrue(result.getReason().contains("before-image"));
	}

	@Test
	public void aFieldTheAgentAddedForTheFirstTimeCannotBeUnset() {
		caller.respond("GET", "/ws/rest/v1/person/p-1", 200,
				"{\"uuid\":\"p-1\",\"auditInfo\":{\"dateChanged\":\"2025-10-09T08:53:20.000+0000\"}}");

		AgentOperationLog operation = create("POST /ws/rest/v1/person/p-1", "p-1");
		operation.setRequestBody("{\"deathDate\":\"2026-10-01\"}");
		operation.setPreviousState("{\"uuid\":\"p-1\"}");

		assertEquals(RollbackResult.Outcome.MANUAL_INTERVENTION_REQUIRED, engine.evaluate(operation).getOutcome());
	}

	// ------------------------------------------------------------------ what happened since

	@Test
	public void aRecordEditedSinceIsNotOverwritten() {
		// The agent wrote at 09:00; somebody edited the record at 11:00. Reversing now would
		// throw that person's edit away.
		caller.respond("GET", "/ws/fhir2/R4/Patient/abc-123", 200,
				"{\"id\":\"abc-123\",\"meta\":{\"lastUpdated\":\"2025-10-09T11:00:00.000+00:00\"}}");

		AgentOperationLog operation = create("PUT /ws/fhir2/R4/Patient/abc-123", "abc-123");
		operation.setRequestBody("{\"gender\":\"female\"}");
		operation.setPreviousState("{\"gender\":\"male\"}");

		RollbackResult result = engine.evaluate(operation);

		assertEquals(RollbackResult.Outcome.MANUAL_INTERVENTION_REQUIRED, result.getOutcome());
		assertTrue(result.getReason().contains("edited since"));
	}

	@Test
	public void aLaterAgentOperationOnTheSameRecordMustBeRolledBackFirst() {
		givenPatientExistsUnchanged();
		givenNoDependents();

		AgentOperationLog later = create("PUT /ws/fhir2/R4/Patient/abc-123", "abc-123");
		later.setId(99);
		dao.laterOperations.add(later);

		RollbackResult result = engine.evaluate(create("POST /ws/fhir2/R4/Patient", "abc-123"));

		assertEquals(RollbackResult.Outcome.MANUAL_INTERVENTION_REQUIRED, result.getOutcome());
		assertTrue(result.getReason().contains("#99"));
	}

	// ------------------------------------------------------------------ refusals

	@Test
	public void aVoidCannotBeUndoneThroughThisApi() {
		assertEquals(RollbackResult.Outcome.MANUAL_INTERVENTION_REQUIRED,
				engine.evaluate(create("DELETE /ws/fhir2/R4/Patient/abc-123", "abc-123")).getOutcome());
	}

	@Test
	public void aCallOutsideTheApiSurfaceIsNotReversedAutomatically() {
		assertEquals(RollbackResult.Outcome.MANUAL_INTERVENTION_REQUIRED,
				engine.evaluate(create("POST /module/patientview/addNeuroAssessment.form", "x-1")).getOutcome());
	}

	@Test
	public void anOperationWhoseAffectedRecordIsUnknownIsNotGuessedAt() {
		assertEquals(RollbackResult.Outcome.MANUAL_INTERVENTION_REQUIRED,
				engine.evaluate(create("POST /ws/fhir2/R4/Patient", null)).getOutcome());
	}

	@Test
	public void aRollbackEntryIsNotItselfRolledBack() {
		AgentOperationLog operation = create("DELETE /ws/fhir2/R4/Patient/abc-123", "abc-123");
		operation.setTaskType(AgentOperationLog.TASK_TYPE_ROLLBACK);

		assertEquals(RollbackResult.Outcome.MANUAL_INTERVENTION_REQUIRED, engine.evaluate(operation).getOutcome());
	}

	@Test
	public void anUnreachableOpenmrsBlocksTheRollbackRatherThanAssumingItIsSafe() {
		caller.failOn("GET", "/ws/fhir2/R4/Patient/abc-123");

		assertEquals(RollbackResult.Outcome.MANUAL_INTERVENTION_REQUIRED,
				engine.evaluate(create("POST /ws/fhir2/R4/Patient", "abc-123")).getOutcome());
	}

	// ------------------------------------------------------------------ helpers

	private void givenPatientExistsUnchanged() {
		caller.respond("GET", "/ws/fhir2/R4/Patient/abc-123", 200,
				"{\"id\":\"abc-123\",\"meta\":{\"lastUpdated\":\"2025-10-09T08:53:20.000+00:00\"}}");
	}

	private void givenNoDependents() {
		caller.respond("GET", "/ws/rest/v1/encounter", 200, "{\"results\":[]}");
		caller.respond("GET", "/ws/rest/v1/visit", 200, "{\"results\":[]}");
		caller.respond("GET", "/ws/rest/v1/obs", 200, "{\"results\":[]}");
	}

	private AgentOperationLog create(String endpoint, String resourceUuid) {
		AgentOperationLog operation = new AgentOperationLog();
		operation.setId(1);
		operation.setTargetEndpoint(endpoint);
		operation.setResourceUuidAffected(resourceUuid);
		operation.setResponseStatus(200);
		operation.setTaskType("create_patient");
		operation.setDateCreated(WRITTEN_AT);
		return operation;
	}

	/** Scripted responses keyed by method plus path prefix, with every call recorded. */
	private static final class RecordingCaller implements OpenmrsApiCaller {

		private final Map<String, HttpJsonClient.Response> scripted = new LinkedHashMap<String, HttpJsonClient.Response>();

		private final List<String> failures = new ArrayList<String>();

		private final List<String[]> made = new ArrayList<String[]>();

		void respond(String method, String pathPrefix, int status, String body) {
			scripted.put(method + " " + pathPrefix, newResponse(status, body));
		}

		void failOn(String method, String pathPrefix) {
			failures.add(method + " " + pathPrefix);
		}

		boolean made(String method, String path) {
			for (String[] call : made) {
				if (call[0].equals(method) && call[1].startsWith(path)) {
					return true;
				}
			}
			return false;
		}

		String bodyOf(String method, String path) {
			for (String[] call : made) {
				if (call[0].equals(method) && call[1].startsWith(path)) {
					return call[2];
				}
			}
			return null;
		}

		@Override
		public HttpJsonClient.Response call(String method, String path, String body, boolean readOnly)
				throws IOException {
			made.add(new String[] { method, path, body });
			for (String failure : failures) {
				if ((method + " " + path).startsWith(failure)) {
					throw new IOException("simulated network failure");
				}
			}
			for (Map.Entry<String, HttpJsonClient.Response> entry : scripted.entrySet()) {
				if ((method + " " + path).startsWith(entry.getKey())) {
					return entry.getValue();
				}
			}
			return newResponse(404, "{}");
		}
	}

	/**
	 * HttpJsonClient.Response has a package-private constructor precisely so nothing outside the
	 * HTTP layer fabricates one in production code; the test reaches for it reflectively rather
	 * than widening that constructor for the sake of testing.
	 */
	private static HttpJsonClient.Response newResponse(int status, String body) {
		try {
			Constructor<HttpJsonClient.Response> constructor = HttpJsonClient.Response.class
					.getDeclaredConstructor(int.class, String.class);
			constructor.setAccessible(true);
			return constructor.newInstance(status, body);
		}
		catch (Exception e) {
			throw new IllegalStateException("Could not build a test response", e);
		}
	}

	private static final class StubDao implements AgentGatewayDao {

		final List<AgentOperationLog> laterOperations = new ArrayList<AgentOperationLog>();

		@Override
		public AgentOperationLog saveOperationLog(AgentOperationLog operation) {
			return operation;
		}

		@Override
		public AgentOperationLog getOperationLog(Integer id) {
			return null;
		}

		@Override
		public AgentOperationLog getOperationLogByUuid(String uuid) {
			return null;
		}

		@Override
		public List<AgentOperationLog> getOperationLogs(String conversationId, Boolean onlyReversible, int maxResults,
				int firstResult) {
			return new ArrayList<AgentOperationLog>();
		}

		@Override
		public List<AgentOperationLog> getOperationLogsForResourceAfter(String resourceUuid, Date after) {
			return laterOperations;
		}
	}
}
