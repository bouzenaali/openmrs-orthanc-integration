/**
 * The contents of this file are subject to the OpenMRS Public License
 * Version 1.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://license.openmrs.org
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations
 * under the License.
 * Copyright (C) OpenMRS, LLC.  All Rights Reserved.
 */

package org.openmrs.module.imaging.api.impl;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.api.impl.BaseOpenmrsService;
import org.openmrs.module.imaging.OrthancConfiguration;
import org.openmrs.module.imaging.api.OrthancConfigurationService;
import org.openmrs.module.imaging.api.dao.OrthancConfigurationDao;

import java.util.List;

public class OrthancConfigurationServiceImpl extends BaseOpenmrsService implements OrthancConfigurationService {
	
	protected Log log = LogFactory.getLog(this.getClass());
	
	private OrthancConfigurationDao dao;
	
	public void setDao(OrthancConfigurationDao dao) {
		this.dao = dao;
	}
	
	public OrthancConfigurationDao getDao() {
		return dao;
	}
	
	@Override
	public List<OrthancConfiguration> getAllOrthancConfigurations() {
		return dao.getAll();
	}
	
	@Override
	public OrthancConfiguration getOrthancConfiguration(int id) {
		return dao.get(id);
	}
	
	@Override
	public void saveOrthancConfiguration(OrthancConfiguration config) {
		dao.saveNew(config);
	}
	
	@Override
	public void removeOrthancConfiguration(OrthancConfiguration orthancConfiguration) {
		dao.remove(orthancConfiguration);
	}
	
	@Override
	public void updateOrthancConfiguration(OrthancConfiguration orthancConfiguration) {
		dao.updateExisting(orthancConfiguration);
	}
}
