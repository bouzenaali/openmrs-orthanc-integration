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
import org.openmrs.Patient;
import org.openmrs.api.impl.BaseOpenmrsService;
import org.openmrs.module.imaging.OrthancConfiguration;
import org.openmrs.module.imaging.api.RequestProcedureService;
import org.openmrs.module.imaging.api.dao.RequestProcedureDao;
import org.openmrs.module.imaging.api.study.DicomStudy;
import org.openmrs.module.imaging.api.worklist.RequestProcedure;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.List;

@Service("requestProcedureServiceImpl")
@Transactional
public class RequestProcedureServiceImpl extends BaseOpenmrsService implements RequestProcedureService {
	
	protected final Log log = LogFactory.getLog(this.getClass());
	
	private RequestProcedureDao dao;
	
	/**
	 * @param dao the dao set
	 */
	public void setDao(RequestProcedureDao dao) {
		this.dao = dao;
	}
	
	/**
	 * @return the dao
	 */
	public RequestProcedureDao getDao() {
		return dao;
	}
	
	/**
	 * Get all request procedures
	 */
	@Override
	public List<RequestProcedure> getAllRequestProcedures() {
		return dao.getAll();
	}
	
	/**
	 * @param studyInstanceUID the study instance UID
	 */
	@Override
	public List<RequestProcedure> getAllByStudyInstanceUID(String studyInstanceUID) {
		return dao.getAllByStudyInstanceUID(studyInstanceUID);
	}
	
	/**
	 * @param pt the openmrs patient object
	 */
	@Override
	public List<RequestProcedure> getRequestProcedureByPatient(Patient pt) {
		return dao.getByPatient(pt);
	}
	
	/**
	 * @param requestProcedureId the request procedure ID
	 */
	@Override
	public RequestProcedure getRequestProcedure(int requestProcedureId) {
		return dao.get(requestProcedureId);
	}
	
	@Override
	public RequestProcedure getRequestProcedureByAccessionNUmber(String accessionNumber) {
		return dao.getByAccessionNumber(accessionNumber);
	}
	
	/**
	 * @param requestProcedure the request procedure object
	 */
	@Override
	public void deleteRequestProcedure(RequestProcedure requestProcedure) throws IOException {
		dao.remove(requestProcedure);
	}
	
	/**
	 * @param requestProcedure The request procedure object
	 */
	@Override
	public void newRequest(RequestProcedure requestProcedure) {
		dao.save(requestProcedure);
	}
	
	/**
	 * @param requestProcedure the request procedure object
	 */
	@Override
	public void updateRequestStatus(RequestProcedure requestProcedure) {
		dao.update(requestProcedure);
	}
	
	/**
	 * @param orthancConfiguration the orthanc configuration
	 */
	@Override
	public List<RequestProcedure> getRequestProcedureByConfig(OrthancConfiguration orthancConfiguration) {
		return dao.getRequestProcedureByConfig(orthancConfiguration);
	}
}
