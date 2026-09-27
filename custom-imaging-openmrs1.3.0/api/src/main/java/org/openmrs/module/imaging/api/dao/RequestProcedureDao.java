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
import org.openmrs.Patient;
import org.openmrs.api.db.hibernate.DbSession;
import org.openmrs.api.db.hibernate.DbSessionFactory;
import org.openmrs.module.imaging.OrthancConfiguration;
import org.openmrs.module.imaging.api.worklist.RequestProcedure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository("imaging.RequestProcedureDao")
public class RequestProcedureDao {
	
	private static final Logger log = LoggerFactory.getLogger(RequestProcedureDao.class);
	
	private DbSessionFactory sessionFactory;
	
	private DbSession getSession() {
		return sessionFactory.getCurrentSession();
	}
	
	public void setSessionFactory(DbSessionFactory sessionFactory) {
		this.sessionFactory = sessionFactory;
	}
	
	public RequestProcedure get(int id) {
		return (RequestProcedure) getSession().get(RequestProcedure.class, id);
	}
	
	@SuppressWarnings("unchecked")
	public List<RequestProcedure> getAll() {
		return getSession().createCriteria(RequestProcedure.class).list();
	}
	
	@SuppressWarnings("unchecked")
	public List<RequestProcedure> getAllByStudyInstanceUID(String studyInstanceUID) {
		Criteria criteria = sessionFactory.getCurrentSession().createCriteria(RequestProcedure.class);
		return criteria.add(Restrictions.eq("studyInstanceUID", studyInstanceUID)).list();
	}
	
	@SuppressWarnings("unchecked")
	public List<RequestProcedure> getByPatient(Patient patient) {
		Criteria criteria = sessionFactory.getCurrentSession().createCriteria(RequestProcedure.class);
		return criteria.add(Restrictions.eq("mrsPatient", patient)).list();
	}
	
	public RequestProcedure getByAccessionNumber(String accessionNumber) {
		Criteria criteria = sessionFactory.getCurrentSession().createCriteria(RequestProcedure.class);
		return (RequestProcedure) criteria.add(Restrictions.eq("accessionNumber", accessionNumber));
	}
	
	public void save(RequestProcedure requestProcedure) {
		getSession().save(requestProcedure);
	}
	
	public void update(RequestProcedure requestProcedure) {
		getSession().update(requestProcedure);
	}
	
	public void remove(RequestProcedure requestProcedure) {
		getSession().delete(requestProcedure);
	}
	
	@SuppressWarnings("unchecked")
	public List<RequestProcedure> getRequestProcedureByConfig(OrthancConfiguration orthancConfiguration) {
		Criteria criteria = sessionFactory.getCurrentSession().createCriteria(RequestProcedure.class);
		return criteria.add(Restrictions.eq("orthancConfiguration", orthancConfiguration)).list();
	}
}
