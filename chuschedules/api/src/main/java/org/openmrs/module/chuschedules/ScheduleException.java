package org.openmrs.module.chuschedules;

import java.util.Date;

import org.openmrs.BaseOpenmrsData;
import org.openmrs.Provider;

/**
 * A date on which no blocks are generated: a public holiday, annual leave, a conference. A null
 * provider makes the exclusion hospital-wide. The lunar holidays move each year and are entered as
 * data rather than computed -- an Islamic-calendar dependency would be a large thing to carry for a
 * handful of dates a year.
 */
public class ScheduleException extends BaseOpenmrsData {
	
	private static final long serialVersionUID = 1L;
	
	private Integer exceptionId;
	
	private Date exceptionDate;
	
	/** Null means the exclusion applies to every provider. */
	private Provider provider;
	
	private String reason;
	
	public Integer getExceptionId() {
		return exceptionId;
	}
	
	public void setExceptionId(Integer exceptionId) {
		this.exceptionId = exceptionId;
	}
	
	public Date getExceptionDate() {
		return exceptionDate;
	}
	
	public void setExceptionDate(Date exceptionDate) {
		this.exceptionDate = exceptionDate;
	}
	
	public Provider getProvider() {
		return provider;
	}
	
	public void setProvider(Provider provider) {
		this.provider = provider;
	}
	
	public String getReason() {
		return reason;
	}
	
	public void setReason(String reason) {
		this.reason = reason;
	}
	
	public boolean isGlobal() {
		return provider == null;
	}
	
	@Override
	public Integer getId() {
		return getExceptionId();
	}
	
	@Override
	public void setId(Integer id) {
		setExceptionId(id);
	}
}
