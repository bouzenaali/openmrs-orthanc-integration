package org.openmrs.module.agentgateway.rollback;

import org.openmrs.module.agentgateway.http.HttpJsonClient;

import java.io.IOException;

/**
 * How the rollback engine reaches OpenMRS's own REST/FHIR surface. It goes over HTTP, back
 * through the same gate every other agent-originated call passes through, rather than reaching
 * into the service layer directly - so a reversing call is privilege-checked, coherence-checked
 * and audited by exactly the same code as the call it reverses, with no second path to keep in
 * sync. It is also the seam that lets the engine's rules be tested without a running server.
 */
public interface OpenmrsApiCaller {

	/**
	 * @param pathWithinContext e.g. "/ws/fhir2/R4/Patient/abc-123"
	 * @param body JSON body, or null
	 * @param readOnly true for a state read, which is authorised and confined but not logged
	 */
	HttpJsonClient.Response call(String method, String pathWithinContext, String body, boolean readOnly)
			throws IOException;
}
