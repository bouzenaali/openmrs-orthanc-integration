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
package org.openmrs.module.imaging.api;

import org.openmrs.api.OpenmrsService;
import org.openmrs.module.imaging.OrthancConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * This service exposes module's core functionality. It is a Spring managed bean which is configured
 * in moduleApplicationContext.xml. <code>
 * Context.getService(OrthancConfigurationService.class).someMethod();
 * </code>
 */
@Service
@Transactional
public interface OrthancConfigurationService extends OpenmrsService {
	
	@Transactional(readOnly = true)
	List<OrthancConfiguration> getAllOrthancConfigurations();
	
	@Transactional(readOnly = true)
	OrthancConfiguration getOrthancConfiguration(int id);
	
	void saveOrthancConfiguration(OrthancConfiguration orthancConfiguration);
	
	void removeOrthancConfiguration(OrthancConfiguration orthancConfiguration);
	
	void updateOrthancConfiguration(OrthancConfiguration orthancConfiguration);
}
