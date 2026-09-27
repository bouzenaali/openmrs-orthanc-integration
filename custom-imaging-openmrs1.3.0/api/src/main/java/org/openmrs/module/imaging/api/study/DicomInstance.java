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

package org.openmrs.module.imaging.api.study;

import org.openmrs.BaseOpenmrsData;
import org.openmrs.module.imaging.OrthancConfiguration;

public class DicomInstance extends BaseOpenmrsData implements java.io.Serializable {
	
	private static final long serialVersionUID = 1;
	
	private String sopInstanceUID;
	
	private String orthancInstanceUID;
	
	private String instanceNumber;
	
	private String imagePositionPatient;
	
	private String numberOfFrames;
	
	private OrthancConfiguration orthancConfiguration;
	
	public DicomInstance() {
	}
	
	public DicomInstance(String sopInstanceUID, String orthancInstanceUID, String instanceNumber,
	    String imagePositionPatient, String numberOfFrames, OrthancConfiguration orthancConfiguration) {
		this.sopInstanceUID = sopInstanceUID;
		this.orthancInstanceUID = orthancInstanceUID;
		this.instanceNumber = instanceNumber;
		this.imagePositionPatient = imagePositionPatient;
		this.numberOfFrames = numberOfFrames;
		this.orthancConfiguration = orthancConfiguration;
	}
	
	// Getters and Setters
	public String getSopInstanceUID() {
		return sopInstanceUID;
	}
	
	public void setSopInstanceUID(String sopInstanceUID) {
		this.sopInstanceUID = sopInstanceUID;
	}
	
	public String getInstanceNumber() {
		return instanceNumber;
	}
	
	public void setInstanceNumber(String instanceNumber) {
		this.instanceNumber = instanceNumber;
	}
	
	public String getOrthancInstanceUID() {
		return orthancInstanceUID;
	}
	
	public void setOrthancInstanceUID(String orthancInstanceUID) {
		this.orthancInstanceUID = orthancInstanceUID;
	}
	
	public String getImagePositionPatient() {
		return imagePositionPatient;
	}
	
	public void setImagePositionPatient(String imagePositionPatient) {
		this.imagePositionPatient = imagePositionPatient;
	}
	
	@Override
	public Integer getId() {
		return 0;
	}
	
	@Override
	public void setId(Integer integer) {
	}
	
	public OrthancConfiguration getOrthancConfiguration() {
		return orthancConfiguration;
	}
	
	public void setOrthancConfiguration(OrthancConfiguration orthancConfiguration) {
		this.orthancConfiguration = orthancConfiguration;
	}
	
	public String getNumberOfFrames() {
		return numberOfFrames;
	}
}
