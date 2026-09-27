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

import org.openmrs.BaseOpenmrsData;
import java.io.Serializable;

public class OrthancConfiguration extends BaseOpenmrsData implements Serializable {
	
	private static final long serialVersionUID = 1;
	
	private Integer id;
	
	private String orthancBaseUrl;
	
	private String orthancProxyUrl;
	
	private String orthancUsername;
	
	private String orthancPassword;
	
	private Integer lastChangedIndex = -1;
	
	public OrthancConfiguration() {
	}
	
	public OrthancConfiguration(String orthancBaseUrl, String orthancProxyUrl, String orthancUsername,
	    String orthancPassword, int lastChangedIndex) {
		this.orthancBaseUrl = orthancBaseUrl;
		this.orthancProxyUrl = orthancProxyUrl;
		this.orthancUsername = orthancUsername;
		this.orthancPassword = orthancPassword;
		this.lastChangedIndex = lastChangedIndex;
	}
	
	@Override
	public void setId(Integer id) {
		this.id = id;
	}
	
	@Override
	public Integer getId() {
		return id;
	}
	
	/**
	 * @return the orthanc base url
	 */
	public String getOrthancBaseUrl() {
		return orthancBaseUrl;
	}
	
	/**
	 * @param url to set
	 */
	public void setOrthancBaseUrl(String url) {
		this.orthancBaseUrl = url;
	}
	
	/**
	 * @param orthancUsername to set
	 */
	public void setOrthancUsername(String orthancUsername) {
		this.orthancUsername = orthancUsername;
	}
	
	/**
	 * @return get the orthanc user name
	 */
	public String getOrthancUsername() {
		return this.orthancUsername;
	}
	
	/**
	 * @param password to set
	 */
	public void setOrthancPassword(String password) {
		this.orthancPassword = password;
	}
	
	/**
	 * @return get the orthanc password
	 */
	public String getOrthancPassword() {
		return this.orthancPassword;
	}
	
	/**
	 * @return get the last changed index
	 */
	public Integer getLastChangedIndex() {
		return lastChangedIndex;
	}
	
	/**
	 * @param lastChangedIndex to set
	 */
	public void setLastChangedIndex(Integer lastChangedIndex) {
		this.lastChangedIndex = lastChangedIndex;
	}
	
	public String getOrthancProxyUrl() {
		return orthancProxyUrl;
	}
	
	public void setOrthancProxyUrl(String orthancProxyUrl) {
		this.orthancProxyUrl = orthancProxyUrl;
	}
}
