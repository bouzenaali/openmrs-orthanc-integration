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

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.LogFactory;
import org.openmrs.Concept;
import org.openmrs.api.APIException;
import org.openmrs.api.AdministrationService;
import org.openmrs.api.ConceptService;
import org.openmrs.module.imaging.api.match.MatchThresholds;
import org.apache.commons.logging.Log;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component("imagingProperties")
public class ImagingProperties {
	
	protected final Log log = LogFactory.getLog(getClass());
	
	@Autowired
	@Qualifier("conceptService")
	protected ConceptService conceptService;
	
	@Autowired
	@Qualifier("adminService")
	protected AdministrationService administrationService;
	
	public ConceptService getConceptService() {
		return conceptService;
	}
	
	public AdministrationService getAdministrationService() {
		return administrationService;
	}
	
	/**
	 * @param globalPropertyName the global property name
	 * @return the openmrs concept
	 */
	protected Concept getConceptByGlobalProperty(String globalPropertyName) {
		String globalProperty = administrationService.getGlobalProperty(globalPropertyName);
		Concept concept = conceptService.getConceptByUuid(globalProperty);
		if (concept == null) {
			throw new IllegalStateException("Configuration required: " + globalPropertyName);
		}
		return concept;
	}
	
	/**
	 * @param globalPropertyName the global property name
	 * @param required the required id
	 * @return the value of the global property
	 */
	protected String getGlobalProperty(String globalPropertyName, boolean required) {
		String globalProperty = administrationService.getGlobalProperty(globalPropertyName);
		if (required && StringUtils.isEmpty(globalProperty)) {
			throw new APIException("Configuration required: " + globalPropertyName);
		}
		return globalProperty;
	}
	
	/**
	 * @return the global property for the max upload data size
	 */
	public Long getMaxUploadImageDataSize() {
		String globalProperty = administrationService.getGlobalProperty(ImagingConstants.GP_MAX_UPLOAD_IMAGEDATA_SIZE);
		try {
			return Long.parseLong(globalProperty);
		}
		catch (Exception e) {
			throw new APIException("Global property " + ImagingConstants.GP_MAX_UPLOAD_IMAGEDATA_SIZE + " with value "
			        + globalProperty + " is not parsable as a long", e);
		}
	}
	
	/**
	 * @return the configured OHIF viewer base URL (e.g. https://viewer.hospital.lan), or null if
	 *         not yet configured
	 */
	public String getOhifBaseUrl() {
		return administrationService.getGlobalProperty(ImagingConstants.GP_OHIF_BASE_URL);
	}
	
	/**
	 * @return the configured OHIF-AI viewer base URL (e.g. https://ai-viewer.hospital.lan), or null
	 *         if not configured, in which case the AI button is not rendered
	 */
	public String getOhifAiBaseUrl() {
		return administrationService.getGlobalProperty(ImagingConstants.GP_OHIF_AI_BASE_URL);
	}
	
	/**
	 * @return the configured {@link MatchThresholds}, built from the 4 match-threshold global
	 *         properties. Falls back to {@link MatchThresholds#defaults()} (95/75/50/25) - logging
	 *         a warning - if any of the global properties is missing/non-numeric, or if the 4
	 *         values are not in a valid, strictly-decreasing order. This method never throws: a
	 *         misconfigured threshold must not break the "Get studies"/worklist matching screens.
	 */
	public MatchThresholds getMatchThresholds() {
		int veryHigh = getIntGlobalProperty(ImagingConstants.GP_MATCH_THRESHOLD_VERY_HIGH, MatchThresholds.DEFAULT_VERY_HIGH);
		int high = getIntGlobalProperty(ImagingConstants.GP_MATCH_THRESHOLD_HIGH, MatchThresholds.DEFAULT_HIGH);
		int medium = getIntGlobalProperty(ImagingConstants.GP_MATCH_THRESHOLD_MEDIUM, MatchThresholds.DEFAULT_MEDIUM);
		int low = getIntGlobalProperty(ImagingConstants.GP_MATCH_THRESHOLD_LOW, MatchThresholds.DEFAULT_LOW);
		try {
			return new MatchThresholds(veryHigh, high, medium, low);
		}
		catch (IllegalArgumentException e) {
			log.warn("Invalid imaging match thresholds configured (veryHigh=" + veryHigh + ", high=" + high + ", medium="
			        + medium + ", low=" + low + "): " + e.getMessage()
			        + ". Falling back to default thresholds (95/75/50/25).");
			return MatchThresholds.defaults();
		}
	}
	
	/**
	 * @param globalPropertyName the global property name
	 * @param defaultValue the value to use if the global property is missing or not a valid integer
	 * @return the global property parsed as an int, or {@code defaultValue}
	 */
	private int getIntGlobalProperty(String globalPropertyName, int defaultValue) {
		String globalProperty = administrationService.getGlobalProperty(globalPropertyName);
		if (StringUtils.isEmpty(globalProperty)) {
			return defaultValue;
		}
		try {
			return Integer.parseInt(globalProperty.trim());
		}
		catch (NumberFormatException e) {
			log.warn("Global property " + globalPropertyName + " has non-numeric value '" + globalProperty
			        + "'. Using default " + defaultValue + ".");
			return defaultValue;
		}
	}
}
