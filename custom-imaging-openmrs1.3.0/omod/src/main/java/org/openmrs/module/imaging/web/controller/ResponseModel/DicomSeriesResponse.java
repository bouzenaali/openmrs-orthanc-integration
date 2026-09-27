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

import org.openmrs.module.imaging.OrthancConfiguration;
import org.openmrs.module.imaging.api.study.DicomSeries;

public class DicomSeriesResponse {
	
	private String seriesInstanceUID;
	
	private String orthancSeriesUID;
	
	private OrthancConfigurationResponse orthancConfiguration;
	
	private String seriesDescription;
	
	private String seriesNumber;
	
	private String seriesDate;
	
	private String seriesTime;
	
	private String modality;
	
	public static DicomSeriesResponse createResponse(DicomSeries dicomSeries) {
		DicomSeriesResponse response = new DicomSeriesResponse();
		response.setSeriesInstanceUID(dicomSeries.getSeriesInstanceUID());
		response.setOrthancSeriesUID(dicomSeries.getOrthancSeriesUID());
		response.setOrthancConfiguration(OrthancConfigurationResponse.createResponse(dicomSeries.getOrthancConfiguration()));
		response.setSeriesDescription(dicomSeries.getSeriesDescription());
		response.setSeriesNumber(dicomSeries.getSeriesNumber());
		response.setSeriesDate(dicomSeries.getSeriesDate());
		response.setSeriesTime(dicomSeries.getSeriesTime());
		response.setModality(dicomSeries.getModality());
		return response;
	}
	
	public void setSeriesInstanceUID(String seriesInstanceUID) {
		this.seriesInstanceUID = seriesInstanceUID;
	}
	
	public String getOrthancSeriesUID() {
		return orthancSeriesUID;
	}
	
	public void setOrthancSeriesUID(String orthancSeriesUID) {
		this.orthancSeriesUID = orthancSeriesUID;
	}
	
	public OrthancConfigurationResponse getOrthancConfiguration() {
		return orthancConfiguration;
	}
	
	public void setOrthancConfiguration(OrthancConfigurationResponse orthancConfiguration) {
		this.orthancConfiguration = orthancConfiguration;
	}
	
	public String getSeriesInstanceUID() {
		return seriesInstanceUID;
	}
	
	public String getSeriesDescription() {
		return seriesDescription;
	}
	
	public void setSeriesDescription(String seriesDescription) {
		this.seriesDescription = seriesDescription;
	}
	
	public String getSeriesNumber() {
		return seriesNumber;
	}
	
	public void setSeriesNumber(String seriesNumber) {
		this.seriesNumber = seriesNumber;
	}
	
	public String getSeriesDate() {
		return seriesDate;
	}
	
	public void setSeriesDate(String seriesDate) {
		this.seriesDate = seriesDate;
	}
	
	public String getSeriesTime() {
		return seriesTime;
	}
	
	public void setSeriesTime(String seriesTime) {
		this.seriesTime = seriesTime;
	}
	
	public String getModality() {
		return modality;
	}
	
	public void setModality(String modality) {
		this.modality = modality;
	}
	
}
