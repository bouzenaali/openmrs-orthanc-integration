package org.openmrs.module.chuschedules.api.db.hibernate;

import java.util.Date;
import java.util.List;

import org.hibernate.Criteria;
import org.hibernate.SessionFactory;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;
import org.openmrs.Provider;
import org.openmrs.module.chuschedules.GeneratedBlock;
import org.openmrs.module.chuschedules.ScheduleException;
import org.openmrs.module.chuschedules.ScheduleTemplate;
import org.openmrs.module.chuschedules.api.db.ChuSchedulesDAO;

public class HibernateChuSchedulesDAO implements ChuSchedulesDAO {
	
	private SessionFactory sessionFactory;
	
	public void setSessionFactory(SessionFactory sessionFactory) {
		this.sessionFactory = sessionFactory;
	}
	
	private Criteria criteria(Class<?> clazz) {
		return sessionFactory.getCurrentSession().createCriteria(clazz);
	}
	
	@Override
	public ScheduleTemplate saveTemplate(ScheduleTemplate template) {
		sessionFactory.getCurrentSession().saveOrUpdate(template);
		return template;
	}
	
	@Override
	public ScheduleTemplate getTemplate(Integer templateId) {
		return (ScheduleTemplate) sessionFactory.getCurrentSession().get(ScheduleTemplate.class, templateId);
	}
	
	@Override
	public ScheduleTemplate getTemplateByUuid(String uuid) {
		return (ScheduleTemplate) criteria(ScheduleTemplate.class).add(Restrictions.eq("uuid", uuid)).uniqueResult();
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public List<ScheduleTemplate> getAllTemplates(boolean includeVoided) {
		Criteria c = criteria(ScheduleTemplate.class);
		if (!includeVoided) {
			c.add(Restrictions.eq("voided", false));
		}
		return c.addOrder(Order.asc("name")).list();
	}
	
	@Override
	public ScheduleException saveException(ScheduleException exception) {
		sessionFactory.getCurrentSession().saveOrUpdate(exception);
		return exception;
	}
	
	@Override
	public ScheduleException getException(Integer exceptionId) {
		return (ScheduleException) sessionFactory.getCurrentSession().get(ScheduleException.class, exceptionId);
	}
	
	@Override
	public ScheduleException getExceptionByUuid(String uuid) {
		return (ScheduleException) criteria(ScheduleException.class).add(Restrictions.eq("uuid", uuid)).uniqueResult();
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public List<ScheduleException> getExceptions(Date from, Date to, Provider provider) {
		Criteria c = criteria(ScheduleException.class).add(Restrictions.eq("voided", false));
		if (from != null) {
			c.add(Restrictions.ge("exceptionDate", from));
		}
		if (to != null) {
			c.add(Restrictions.le("exceptionDate", to));
		}
		// A null provider on the row means hospital-wide, so those always apply.
		if (provider == null) {
			c.add(Restrictions.isNull("provider"));
		} else {
			c.add(Restrictions.or(Restrictions.isNull("provider"), Restrictions.eq("provider", provider)));
		}
		return c.addOrder(Order.asc("exceptionDate")).list();
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public List<ScheduleException> getAllExceptions(boolean includeVoided) {
		Criteria c = criteria(ScheduleException.class);
		if (!includeVoided) {
			c.add(Restrictions.eq("voided", false));
		}
		return c.addOrder(Order.asc("exceptionDate")).list();
	}
	
	@Override
	public GeneratedBlock saveGeneratedBlock(GeneratedBlock generatedBlock) {
		sessionFactory.getCurrentSession().saveOrUpdate(generatedBlock);
		return generatedBlock;
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public List<GeneratedBlock> getGeneratedBlocks(ScheduleTemplate template, Date from, Date to) {
		Criteria c = criteria(GeneratedBlock.class).add(Restrictions.eq("voided", false));
		if (template != null) {
			c.add(Restrictions.eq("template", template));
		}
		if (from != null) {
			c.add(Restrictions.ge("targetDate", from));
		}
		if (to != null) {
			c.add(Restrictions.le("targetDate", to));
		}
		return c.addOrder(Order.asc("targetDate")).list();
	}
}
