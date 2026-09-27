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
import org.openmrs.module.imaging.api.study.DicomStudy;

import java.util.List;
import java.util.stream.Collectors;

public class DicomStudyResponse {
	
	private Integer id;
	
	private String studyInstanceUID;
	
	private String orthancStudyUID;
	
	private String mrsPatientUuid;
	
	private OrthancConfigurationResponse orthancConfiguration;
	
	private String patientName;
	
	private String studyDate;
	
	private String studyDescription;
	
	private String gender;
	
	private String dicomPatientId;
	
	private String patientBirthDate;
	
	public static DicomStudyResponse createResponse(DicomStudy study) {
		DicomStudyResponse response = new DicomStudyResponse();
		response.setId(study.getId());
		response.setStudyInstanceUID(study.getStudyInstanceUID());
		response.setMrsPatientUuid(study.getMrsPatient() == null ? null : study.getMrsPatient().getUuid());
		response.setOrthancStudyUID(study.getOrthancStudyUID());
		response.setPatientName(study.getPatientName());
		response.setStudyDate(study.getStudyDate());
		response.setStudyDescription(study.getStudyDescription());
		response.setGender(study.getGender());
		response.setDicomPatientId(study.getDicomPatientId());
		response.setPatientBirthDate(study.getPatientBirthDate());
		response.setOrthancConfiguration(OrthancConfigurationResponse.createResponse(study.getOrthancConfiguration()));
		return response;
	}
	
	public static List<DicomStudyResponse> createResponse(List<DicomStudy> studies) {
        return studies.stream().map(DicomStudyResponse::createResponse).collect(Collectors.toList());
    }
	
	public OrthancConfigurationResponse getOrthancConfiguration() {
		return orthancConfiguration;
	}
	
	public void setOrthancConfiguration(OrthancConfigurationResponse orthancConfiguration) {
		this.orthancConfiguration = orthancConfiguration;
	}
	
	public Integer getId() {
		return id;
	}
	
	public void setId(Integer id) {
		this.id = id;
	}
	
	public String getStudyInstanceUID() {
		return studyInstanceUID;
	}
	
	public void setStudyInstanceUID(String studyInstanceUID) {
		this.studyInstanceUID = studyInstanceUID;
	}
	
	public String getOrthancStudyUID() {
		return orthancStudyUID;
	}
	
	public void setOrthancStudyUID(String orthancStudyUID) {
		this.orthancStudyUID = orthancStudyUID;
	}
	
	public String getPatientName() {
		return patientName;
	}
	
	public void setPatientName(String patientName) {
		this.patientName = patientName;
	}
	
	public String getStudyDate() {
		return studyDate;
	}
	
	public void setStudyDate(String studyDate) {
		this.studyDate = studyDate;
	}
	
	public String getStudyDescription() {
		return studyDescription;
	}
	
	public void setStudyDescription(String studyDescription) {
		this.studyDescription = studyDescription;
	}
	
	public String getGender() {
		return gender;
	}
	
	public void setGender(String gender) {
		this.gender = gender;
	}
	
	public String getDicomPatientId() {
		return dicomPatientId;
	}
	
	public void setDicomPatientId(String dicomPatientId) {
		this.dicomPatientId = dicomPatientId;
	}
	
	public String getPatientBirthDate() {
		return patientBirthDate;
	}
	
	public void setPatientBirthDate(String patientBirthDate) {
		this.patientBirthDate = patientBirthDate;
	}
	
	public void setMrsPatientUuid(String patientUuid) {
		this.mrsPatientUuid = patientUuid;
	}
	
	public String getMrsPatientUuid() {
		return mrsPatientUuid;
	}
}
