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
package org.openmrs.module.imaging.api.impl;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.api.impl.BaseOpenmrsService;
import org.openmrs.module.imaging.api.RequestProcedureStepService;
import org.openmrs.module.imaging.api.dao.RequestProcedureStepDao;
import org.openmrs.module.imaging.api.worklist.RequestProcedure;
import org.openmrs.module.imaging.api.worklist.RequestProcedureStep;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.List;

@Service("requestProcedureStepServiceImpl")
@Transactional
public class RequestProcedureStepServiceImpl extends BaseOpenmrsService implements RequestProcedureStepService {
	
	protected final Log log = LogFactory.getLog(this.getClass());
	
	private RequestProcedureStepDao dao;
	
	/**
	 * @param dao the dao to set
	 */
	public void setDao(RequestProcedureStepDao dao) {
		this.dao = dao;
	}
	
	/**
	 * @return the dao
	 */
	public RequestProcedureStepDao getDao() {
		return dao;
	}
	
	/**
	 * @param requestProcedure The request procedure object
	 */
	@Override
	public List<RequestProcedureStep> getAllStepByRequestProcedure(RequestProcedure requestProcedure) {
		return dao.getAllStepByRequestProcedure(requestProcedure);
	}
	
	/**
	 * @param id The procedure step ID
	 */
	@Override
	public RequestProcedureStep getProcedureStep(int id) {
		return dao.get(id);
	}
	
	/**
	 * @param requestProcedureStep The request procedure step object
	 */
	@Override
	public void newProcedureStep(RequestProcedureStep requestProcedureStep) throws IOException {
		dao.save(requestProcedureStep);
	}
	
	/**
	 * @param requestProcedureStep The request procedure step object
	 */
	@Override
	public void deleteProcedureStep(RequestProcedureStep requestProcedureStep) throws IOException {
		dao.remove(requestProcedureStep);
	}
	
	@Override
	public void updateProcedureStep(RequestProcedureStep requestProcedureStep) {
		dao.update(requestProcedureStep);
	}
	
}
