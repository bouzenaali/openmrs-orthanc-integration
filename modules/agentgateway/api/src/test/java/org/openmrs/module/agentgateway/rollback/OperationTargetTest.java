package org.openmrs.module.agentgateway.rollback;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Parsing a logged endpoint back into "what did this actually do".
 * <p>
 * The rollback engine's first decision - void it, restore it, or refuse - comes entirely from
 * this, so a misparse is a wrong reversal.
 */
public class OperationTargetTest {

	@Test
	public void aFhirPostIsACreate() {
		OperationTarget target = OperationTarget.parse("POST /ws/fhir2/R4/Patient");
		assertEquals(OperationTarget.Family.FHIR, target.getFamily());
		assertEquals("Patient", target.getResourceType());
		assertNull(target.getResourceId());
		assertEquals(OperationTarget.Kind.CREATE, target.getKind());
	}

	@Test
	public void aFhirPutIsAnUpdate() {
		OperationTarget target = OperationTarget.parse("PUT /ws/fhir2/R4/Patient/abc-123");
		assertEquals("abc-123", target.getResourceId());
		assertEquals(OperationTarget.Kind.UPDATE, target.getKind());
	}

	@Test
	public void aRestPostToACollectionIsACreateButToAnInstanceIsAnUpdate() {
		// OpenMRS's REST API overloads POST this way; FHIR does not, and conflating them would
		// have the engine try to void a record that was only edited.
		assertEquals(OperationTarget.Kind.CREATE, OperationTarget.parse("POST /ws/rest/v1/patient").getKind());
		assertEquals(OperationTarget.Kind.UPDATE,
				OperationTarget.parse("POST /ws/rest/v1/patient/abc-123").getKind());
	}

	@Test
	public void aDeleteIsADelete() {
		assertEquals(OperationTarget.Kind.DELETE,
				OperationTarget.parse("DELETE /ws/rest/v1/patient/abc-123").getKind());
	}

	@Test
	public void readsAreRecognisedAndAreNotWrites() {
		OperationTarget target = OperationTarget.parse("GET /ws/fhir2/R4/Patient?name=Benali&_count=10");
		assertEquals(OperationTarget.Kind.READ, target.getKind());
		assertFalse(target.isWrite());
		assertEquals("/ws/fhir2/R4/Patient", target.getPath());
	}

	@Test
	public void aDeeperSubResourceIsNotMistakenForAnInstance() {
		// patient/{uuid}/identifier/{uuid} is a sub-resource this engine does not claim to
		// understand; reporting no id makes the rollback engine refuse rather than guess.
		OperationTarget target = OperationTarget.parse("POST /ws/rest/v1/patient/abc-123/identifier/def-456");
		assertNull(target.getResourceId());
	}

	@Test
	public void aPersonAttributeUpdateIsRecognisedAsAnInstance() {
		// update_patient_demographics writes phone changes through person/{uuid}/attribute/{id}
		// (Phase 20 - fhir2's own Patient PUT cannot actually change an existing attribute value).
		// Unlike the general "deeper sub-resource" case above, this one specific shape is
		// understood: the sub-resource id is the operation's real target.
		OperationTarget target = OperationTarget.parse("POST /ws/rest/v1/person/abc-123/attribute/attr-456");
		assertEquals("attr-456", target.getResourceId());
		assertEquals(OperationTarget.Kind.UPDATE, target.getKind());
		assertEquals("/ws/rest/v1/person/abc-123/attribute/attr-456", target.instancePath("attr-456"));
	}

	@Test
	public void aPersonNameUpdateIsRecognisedAsAnInstance() {
		OperationTarget target = OperationTarget.parse("POST /ws/rest/v1/person/abc-123/name/name-456");
		assertEquals("name-456", target.getResourceId());
		assertEquals(OperationTarget.Kind.UPDATE, target.getKind());
	}

	@Test
	public void addingAPersonsFirstAttributeIsStillACreate() {
		// person/{uuid}/attribute (three segments, no trailing id) is the collection - a brand
		// new attribute, not an edit to one that already exists.
		OperationTarget target = OperationTarget.parse("POST /ws/rest/v1/person/abc-123/attribute");
		assertNull(target.getResourceId());
		assertEquals(OperationTarget.Kind.CREATE, target.getKind());
	}

	@Test
	public void aPersonSubResourceOutsideTheKnownSetIsStillUnrecognised() {
		// Same four-segment shape, but not one of the two collections this engine actually
		// understands - still refused rather than guessed at.
		OperationTarget target = OperationTarget.parse("POST /ws/rest/v1/person/abc-123/address/addr-456");
		assertNull(target.getResourceId());
	}

	@Test
	public void anythingOutsideTheApiSurfaceIsFlaggedAsSuch() {
		OperationTarget target = OperationTarget.parse("POST /module/patientview/addNeuroAssessment.form");
		assertEquals(OperationTarget.Family.OTHER, target.getFamily());
		assertTrue(target.isWrite());
	}

	@Test
	public void malformedInputYieldsNothingRatherThanAGuess() {
		assertNull(OperationTarget.parse(null));
		assertNull(OperationTarget.parse(""));
		assertNull(OperationTarget.parse("POST"));
	}

	@Test
	public void theInstancePathIsBuiltFromTheIdTheCallTurnedOutToAffect() {
		OperationTarget create = OperationTarget.parse("POST /ws/fhir2/R4/Patient");
		assertEquals("/ws/fhir2/R4/Patient/new-uuid", create.instancePath("new-uuid"));

		OperationTarget update = OperationTarget.parse("PUT /ws/fhir2/R4/Patient/abc-123");
		assertEquals("/ws/fhir2/R4/Patient/abc-123", update.instancePath("abc-123"));
	}
}
