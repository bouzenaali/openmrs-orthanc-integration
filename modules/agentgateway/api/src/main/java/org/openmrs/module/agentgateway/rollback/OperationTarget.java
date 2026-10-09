package org.openmrs.module.agentgateway.rollback;

import org.apache.commons.lang.StringUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * The parsed form of a logged {@code target_endpoint} ("POST /ws/fhir2/R4/Patient"), which is
 * everything the rollback engine knows about what an operation did without re-reading the body.
 */
public final class OperationTarget {

	/** The only nested-under-person collections this engine knows how to address by id. */
	private static final Set<String> PERSON_SUB_RESOURCES = Collections
			.unmodifiableSet(new HashSet<String>(Arrays.asList("attribute", "name")));

	public enum Family {
		FHIR, REST, OTHER
	}

	public enum Kind {
		/** Brought a resource into existence. Reversed by voiding it. */
		CREATE,
		/** Replaced a resource's contents. Reversed by writing the before-state back. */
		UPDATE,
		/** Voided or retired a resource. Not reversible over the REST surface. */
		DELETE,
		/** Changed nothing. */
		READ
	}

	private final String method;

	private final String path;

	private final Family family;

	private final String resourceType;

	private final String resourceId;

	private OperationTarget(String method, String path, Family family, String resourceType, String resourceId) {
		this.method = method;
		this.path = path;
		this.family = family;
		this.resourceType = resourceType;
		this.resourceId = resourceId;
	}

	/**
	 * @param targetEndpoint "METHOD /path", exactly as the audit filter records it
	 * @return null if the string is not in that shape
	 */
	public static OperationTarget parse(String targetEndpoint) {
		if (StringUtils.isBlank(targetEndpoint)) {
			return null;
		}
		String[] halves = targetEndpoint.trim().split("\\s+", 2);
		if (halves.length != 2) {
			return null;
		}

		String method = halves[0].toUpperCase();
		String path = halves[1];
		int query = path.indexOf('?');
		if (query >= 0) {
			path = path.substring(0, query);
		}
		while (path.endsWith("/")) {
			path = path.substring(0, path.length() - 1);
		}

		Family family;
		String remainder;
		if (path.startsWith("/ws/fhir2/R4/")) {
			family = Family.FHIR;
			remainder = path.substring("/ws/fhir2/R4/".length());
		} else if (path.startsWith("/ws/rest/v1/")) {
			family = Family.REST;
			remainder = path.substring("/ws/rest/v1/".length());
		} else {
			return new OperationTarget(method, path, Family.OTHER, null, null);
		}

		String[] segments = remainder.split("/");
		String resourceType = segments.length > 0 && !segments[0].isEmpty() ? segments[0] : null;
		// A trailing segment is only an identifier when it is the second one. Anything deeper
		// (patient/{uuid}/identifier/{uuid}) is a sub-resource this engine does not claim to
		// understand, and is reported as such rather than guessed at - with one deliberate,
		// narrow exception just below.
		String resourceId = segments.length == 2 && !segments[1].isEmpty() ? segments[1] : null;

		if (resourceId == null && segments.length == 4 && "person".equals(segments[0]) && !segments[3].isEmpty()
				&& PERSON_SUB_RESOURCES.contains(segments[2])) {
			// person/{uuid}/attribute/{attrId} and person/{uuid}/name/{nameId} - the two paths
			// update_patient_demographics writes phone and name through as of Phase 20 (fhir2
			// 1.2.2's own Patient PUT cannot actually change an existing attribute or name value;
			// see the clinical-agent-service implementation log). This one shape *is* understood
			// unlike the general case above: the sub-resource id is the operation's real target,
			// not the person's, and treating it as an unrecognised create meant a phone or name
			// update could never be reversed, and its before-image was never captured either.
			resourceId = segments[3];
		}

		return new OperationTarget(method, path, family, resourceType, resourceId);
	}

	public Kind getKind() {
		if ("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method)) {
			return Kind.READ;
		}
		if ("DELETE".equals(method)) {
			return Kind.DELETE;
		}
		if ("PUT".equals(method) || "PATCH".equals(method)) {
			return Kind.UPDATE;
		}
		// OpenMRS's REST API overloads POST: to a collection it creates, to an instance it
		// updates. FHIR does not - POST is always a create there.
		if ("POST".equals(method)) {
			return family == Family.REST && resourceId != null ? Kind.UPDATE : Kind.CREATE;
		}
		return Kind.UPDATE;
	}

	public boolean isWrite() {
		return getKind() != Kind.READ;
	}

	public String getMethod() {
		return method;
	}

	public String getPath() {
		return path;
	}

	public Family getFamily() {
		return family;
	}

	public String getResourceType() {
		return resourceType;
	}

	public String getResourceId() {
		return resourceId;
	}

	/** The path of the single resource this operation affected, given the id it turned out to have. */
	public String instancePath(String resourceUuid) {
		if (StringUtils.isBlank(resourceUuid)) {
			return null;
		}
		if (resourceId != null) {
			return path;
		}
		return path + "/" + resourceUuid;
	}
}
