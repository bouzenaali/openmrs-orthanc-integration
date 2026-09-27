/**
 * The contents of this file are subject to the OpenMRS Public License
 * Version 1.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://license.openmrs.org
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations
 * under the License.
 *
 * Copyright (C) OpenMRS, LLC.  All Rights Reserved.
 */
package org.openmrs.module.imaging.api.match;

/**
 * Result of comparing a single identity field (e.g. the DICOM PatientID tag, or the DICOM
 * PatientBirthDate tag) between a {@code DicomStudy} and an OpenMRS {@code Patient}.
 * <p>
 * This is intentionally a tri-state, not a boolean: the field may simply be unavailable on one or
 * both sides (e.g. an older study fetched before this field was captured, or a modality that never
 * populated the tag), in which case it must not be treated as either a positive or a negative
 * signal.
 */
public enum IdentityMatch {
	
	/** Both sides had a value for this field, and they matched. */
	MATCH,
	
	/** Both sides had a value for this field, and they did not match. */
	MISMATCH,
	
	/** One or both sides had no value for this field, so no comparison could be made. */
	UNKNOWN
}
