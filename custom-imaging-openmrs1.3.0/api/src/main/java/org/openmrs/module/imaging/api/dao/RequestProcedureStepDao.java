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
package org.openmrs.module.imaging.api.dao;

import org.hibernate.Criteria;
import org.hibernate.criterion.Restrictions;
import org.openmrs.api.db.hibernate.DbSession;
import org.openmrs.api.db.hibernate.DbSessionFactory;
import org.openmrs.module.imaging.api.worklist.RequestProcedure;
import org.openmrs.module.imaging.api.worklist.RequestProcedureStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository("imaging.RequestProcedureStepDao")
public class RequestProcedureStepDao {
	
	private static final Logger log = LoggerFactory.getLogger(RequestProcedureStepDao.class);
	
	//	@Autowired
	DbSessionFactory sessionFactory;
	
	private DbSession getSession() {
		return sessionFactory.getCurrentSession();
	}
	
	public void setSessionFactory(DbSessionFactory sessionFactory) {
		this.sessionFactory = sessionFactory;
	}
	
	public RequestProcedureStep get(int id) {
		return (RequestProcedureStep) getSession().get(RequestProcedureStep.class, id);
	}
	
	@SuppressWarnings("unchecked")
	public List<RequestProcedureStep> getAll() {
		return getSession().createCriteria(RequestProcedureStep.class).list();
	}
	
	@SuppressWarnings("unchecked")
	public List<RequestProcedureStep> getAllStepByRequestProcedure(RequestProcedure requestProcedure) {
		Criteria criteria = sessionFactory.getCurrentSession().createCriteria(RequestProcedureStep.class);
		return criteria.add(Restrictions.eq("requestProcedure", requestProcedure)).list();
	}
	
	public void save(RequestProcedureStep requestProcedureStep) {
		getSession().save(requestProcedureStep);
	}
	
	public void update(RequestProcedureStep requestProcedureStep) {
		getSession().update(requestProcedureStep);
	}
	
	public void remove(RequestProcedureStep requestProcedureStep) {
		getSession().delete(requestProcedureStep);
	}
}
