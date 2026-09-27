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

public class RequestProcedureStep extends BaseOpenmrsData implements java.io.Serializable {
	
	private static final long serialVersionUID = 1;
	
	private Integer id;
	
	private RequestProcedure requestProcedure;
	
	private String modality;
	
	private String aetTitle;
	
	private String scheduledReferringPhysician;
	
	private String requestedProcedureDescription;
	
	private String stepStartDate;
	
	private String stepStartTime;
	
	private String performedProcedureStepStatus;
	
	private String stationName;
	
	private String procedureStepLocation;
	
	public RequestProcedureStep() {
	}
	
	public RequestProcedureStep(RequestProcedure requestProcedure, String modality, String scheduledReferringPhysician,
	    String requestedProcedureDescription, String aetTitle, String stepStartDate, String stepStartTime,
	    String performedProcedureStepStatus, String stationName, String procedureStepLocation) {
		this.requestProcedure = requestProcedure;
		this.modality = modality;
		this.aetTitle = aetTitle;
		this.scheduledReferringPhysician = scheduledReferringPhysician;
		this.requestedProcedureDescription = requestedProcedureDescription;
		this.stepStartDate = stepStartDate;
		this.stepStartTime = stepStartTime;
		this.performedProcedureStepStatus = performedProcedureStepStatus;
		this.stationName = stationName;
		this.procedureStepLocation = procedureStepLocation;
	}
	
	public Integer getId() {
		return id;
	}
	
	public void setId(Integer id) {
		this.id = id;
	}
	
	public RequestProcedure getRequestProcedure() {
		return requestProcedure;
	}
	
	public void setRequestProcedure(RequestProcedure requestProcedure) {
		this.requestProcedure = requestProcedure;
	}
	
	public String getScheduledReferringPhysician() {
		return scheduledReferringPhysician;
	}
	
	public String getModality() {
		return modality;
	}
	
	public void setModality(String modality) {
		this.modality = modality;
	}
	
	public void setScheduledReferringPhysician(String scheduledReferringPhysician) {
		this.scheduledReferringPhysician = scheduledReferringPhysician;
	}
	
	public String getRequestedProcedureDescription() {
		return requestedProcedureDescription;
	}
	
	public void setRequestedProcedureDescription(String requestedProcedureDescription) {
		this.requestedProcedureDescription = requestedProcedureDescription;
	}
	
	public String getAetTitle() {
		return aetTitle;
	}
	
	public void setAetTitle(String aetTitle) {
		this.aetTitle = aetTitle;
	}
	
	public String getStepStartDate() {
		return stepStartDate;
	}
	
	public void setStepStartDate(String stepStartDate) {
		this.stepStartDate = stepStartDate;
	}
	
	public String getStepStartTime() {
		return stepStartTime;
	}
	
	public void setStepStartTime(String stepStartTime) {
		this.stepStartTime = stepStartTime;
	}
	
	public String getPerformedProcedureStepStatus() {
		return performedProcedureStepStatus;
	}
	
	public void setPerformedProcedureStepStatus(String performedProcedureStepStatus) {
		this.performedProcedureStepStatus = performedProcedureStepStatus;
	}
	
	public String getStationName() {
		return stationName;
	}
	
	public void setStationName(String stationName) {
		this.stationName = stationName;
	}
	
	public String getProcedureStepLocation() {
		return procedureStepLocation;
	}
	
	public void setProcedureStepLocation(String procedureStepLocation) {
		this.procedureStepLocation = procedureStepLocation;
	}
}
