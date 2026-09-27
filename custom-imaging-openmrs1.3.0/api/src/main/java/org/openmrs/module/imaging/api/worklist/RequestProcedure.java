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
package org.openmrs.module.imaging.api.worklist;

import org.openmrs.BaseOpenmrsData;
import org.openmrs.Patient;
import org.openmrs.module.imaging.OrthancConfiguration;

public class RequestProcedure extends BaseOpenmrsData implements java.io.Serializable {
	
	private static final long serialVersionUID = 1;
	
	private Integer id;
	
	private String status; //e.g. active, completed
	
	private OrthancConfiguration orthancConfiguration;
	
	private Patient mrsPatient;
	
	private String accessionNumber;
	
	private String studyInstanceUID;
	
	private String requestingPhysician;
	
	private String requestDescription;
	
	private String priority;
	
	public RequestProcedure() {
	}
	
	public RequestProcedure(String status, Patient mrsPatient, OrthancConfiguration config, String accessionNumber,
	    String studyInstanceUID, String requestingPhysician, String requestDescription, String priority) {
		this.status = status;
		this.mrsPatient = mrsPatient;
		this.orthancConfiguration = config;
		this.accessionNumber = accessionNumber;
		this.studyInstanceUID = studyInstanceUID;
		this.requestingPhysician = requestingPhysician;
		this.requestDescription = requestDescription;
		this.priority = priority;
	}
	
	public Integer getId() {
		return id;
	}
	
	public void setId(Integer id) {
		this.id = id;
	}
	
	public String getStatus() {
		return status;
	}
	
	public void setStatus(String status) {
		this.status = status;
	}
	
	public OrthancConfiguration getOrthancConfiguration() {
		return orthancConfiguration;
	}
	
	public void setOrthancConfiguration(OrthancConfiguration orthancConfiguration) {
		this.orthancConfiguration = orthancConfiguration;
	}
	
	public Patient getMrsPatient() {
		return mrsPatient;
	}
	
	public void setMrsPatient(Patient mrsPatient) {
		this.mrsPatient = mrsPatient;
	}
	
	public String getAccessionNumber() {
		return accessionNumber;
	}
	
	public void setAccessionNumber(String accessionNumber) {
		this.accessionNumber = accessionNumber;
	}
	
	public String getStudyInstanceUID() {
		return studyInstanceUID;
	}
	
	public void setStudyInstanceUID(String studyInstanceUID) {
		this.studyInstanceUID = studyInstanceUID;
	}
	
	public String getRequestingPhysician() {
		return requestingPhysician;
	}
	
	public void setRequestingPhysician(String requestingPhysician) {
		this.requestingPhysician = requestingPhysician;
	}
	
	public String getRequestDescription() {
		return requestDescription;
	}
	
	public void setRequestDescription(String requestDescription) {
		this.requestDescription = requestDescription;
	}
	
	public String getPriority() {
		return priority;
	}
	
	public void setPriority(String priority) {
		this.priority = priority;
	}
}
