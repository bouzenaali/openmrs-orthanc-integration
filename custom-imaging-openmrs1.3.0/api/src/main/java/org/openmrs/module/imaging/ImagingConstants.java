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
package org.openmrs.module.imaging;

public class ImagingConstants {
	
	public static final String MODULE_ID = "imaging";
	
	public static final String PRIVILEGE_MODIFY_IMAGE_DATA = "Task: Modify Image Data";
	
	public static final String TASK_MANAGER_ORTHANC_CONFIGURATION = "Task: Manager Orthanc Configuration";
	
	public static final String GP_MAX_UPLOAD_IMAGEDATA_SIZE = "imaging.maxUploadImageDataSize";
	
	public static final String GP_OHIF_BASE_URL = "imaging.ohifBaseUrl";
	
	public static final String PRIVILEGE_EDIT_WORKLIST = "Task: Edit Worklist";
	
	/**
	 * Lower bound (inclusive), out of 100, of the "Very High" patient-match confidence band. Must
	 * be greater than {@link #GP_MATCH_THRESHOLD_HIGH}. See
	 * {@link org.openmrs.module.imaging.api.match.MatchTier}.
	 */
	public static final String GP_MATCH_THRESHOLD_VERY_HIGH = "imaging.matchThreshold.veryHigh";
	
	/**
	 * Lower bound (inclusive), out of 100, of the "High" patient-match confidence band. Must be
	 * greater than {@link #GP_MATCH_THRESHOLD_MEDIUM} and less than
	 * {@link #GP_MATCH_THRESHOLD_VERY_HIGH}.
	 */
	public static final String GP_MATCH_THRESHOLD_HIGH = "imaging.matchThreshold.high";
	
	/**
	 * Lower bound (inclusive), out of 100, of the "Medium" patient-match confidence band. Must be
	 * greater than {@link #GP_MATCH_THRESHOLD_LOW} and less than {@link #GP_MATCH_THRESHOLD_HIGH}.
	 */
	public static final String GP_MATCH_THRESHOLD_MEDIUM = "imaging.matchThreshold.medium";
	
	/**
	 * Lower bound (inclusive), out of 100, of the "Low" patient-match confidence band. Scores below
	 * this fall into "Very Low". Must be less than {@link #GP_MATCH_THRESHOLD_MEDIUM}.
	 */
	public static final String GP_MATCH_THRESHOLD_LOW = "imaging.matchThreshold.low";
	
	/**
	 * Base URL of the OHIF-AI viewer. Deliberately separate from GP_OHIF_BASE_URL: OHIF-AI is a
	 * second, independent viewer, and the routine one must keep working whether or not it is
	 * deployed. Left empty, the AI button does not appear.
	 */
	public static final String GP_OHIF_AI_BASE_URL = "imaging.ohifAiBaseUrl";
	
	/**
	 * Opening a study in the AI viewer is privileged. Its segmentations are written back to the
	 * PACS as real clinical data, so access is granted deliberately rather than to everyone who can
	 * reach the studies table.
	 */
	public static final String PRIVILEGE_USE_OHIF_AI = "Task: Use AI Imaging Viewer";
}
