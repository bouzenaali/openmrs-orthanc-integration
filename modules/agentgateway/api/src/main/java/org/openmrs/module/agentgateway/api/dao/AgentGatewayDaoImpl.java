package org.openmrs.module.agentgateway.api.dao;

import org.hibernate.Criteria;
import org.hibernate.SessionFactory;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;
import org.openmrs.module.agentgateway.api.model.AgentOperationLog;

import java.util.Date;
import java.util.List;

public class AgentGatewayDaoImpl implements AgentGatewayDao {

	private SessionFactory sessionFactory;

	public void setSessionFactory(SessionFactory sessionFactory) {
		this.sessionFactory = sessionFactory;
	}

	@Override
	public AgentOperationLog saveOperationLog(AgentOperationLog log) {
		sessionFactory.getCurrentSession().saveOrUpdate(log);
		return log;
	}

	@Override
	public AgentOperationLog getOperationLog(Integer id) {
		return sessionFactory.getCurrentSession().get(AgentOperationLog.class, id);
	}

	@Override
	public AgentOperationLog getOperationLogByUuid(String uuid) {
		return (AgentOperationLog) sessionFactory.getCurrentSession().createCriteria(AgentOperationLog.class)
				.add(Restrictions.eq("uuid", uuid)).uniqueResult();
	}

	@Override
	@SuppressWarnings("unchecked")
	public List<AgentOperationLog> getOperationLogs(String conversationId, Boolean onlyReversible, int maxResults,
			int firstResult) {
		Criteria criteria = sessionFactory.getCurrentSession().createCriteria(AgentOperationLog.class);
		if (conversationId != null) {
			criteria.add(Restrictions.eq("conversationId", conversationId));
		}
		if (Boolean.TRUE.equals(onlyReversible)) {
			criteria.add(Restrictions.eq("reversible", Boolean.TRUE));
			criteria.add(Restrictions.isNull("dateRolledBack"));
		}
		criteria.addOrder(Order.desc("dateCreated"));
		criteria.addOrder(Order.desc("id"));
		criteria.setFirstResult(Math.max(0, firstResult));
		criteria.setMaxResults(maxResults <= 0 ? 50 : maxResults);
		return criteria.list();
	}

	@Override
	@SuppressWarnings("unchecked")
	public List<AgentOperationLog> getOperationLogsForResourceAfter(String resourceUuid, Date after) {
		return sessionFactory.getCurrentSession().createCriteria(AgentOperationLog.class)
				.add(Restrictions.eq("resourceUuidAffected", resourceUuid)).add(Restrictions.gt("dateCreated", after))
				.addOrder(Order.asc("dateCreated")).list();
	}
}
