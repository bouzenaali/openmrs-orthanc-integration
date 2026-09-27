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
package org.openmrs.module.imaging.web.controller.ResponseModel;

import java.util.List;
import java.util.Map;

public class StudiesWithScoreResponse {
	
	public List<DicomStudyResponse> studies;
	
	public Map<String, Integer> scores;
	
	/**
	 * Maps each study's StudyInstanceUID to its {@code MatchTier} name (e.g. "HIGHEST",
	 * "VERY_HIGH", ...), computed from {@link #scores} via {@code MatchScoreCalculator.classify}.
	 * Exposed as a plain String (rather than the enum itself) to keep JSON serialization simple and
	 * stable for API consumers.
	 */
	public Map<String, String> tiers;
}
