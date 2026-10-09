package org.openmrs.module.chuschedules;

import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Set;

import org.openmrs.BaseOpenmrsData;
import org.openmrs.Location;
import org.openmrs.Provider;
import org.openmrs.module.appointmentscheduling.AppointmentType;

/**
 * A provider's recurring clinic pattern: who, where, which services, and over what period the
 * pattern is valid. The pattern itself lives in the {@link ScheduleTemplateRange}s. A template is
 * an input to generation, never a parallel source of availability. What the booking UI reads is
 * still AppointmentBlock/TimeSlot, exactly as it does for blocks entered by hand.
 */
public class ScheduleTemplate extends BaseOpenmrsData {
	
	private static final long serialVersionUID = 1L;
	
	private Integer templateId;
	
	private String name;
	
	private Provider provider;
	
	private Location location;
	
	private Date validFrom;
	
	/** Null means open-ended. */
	private Date validTo;
	
	private Boolean active = Boolean.TRUE;
	
	private Set<ScheduleTemplateRange> ranges = new LinkedHashSet<ScheduleTemplateRange>();
	
	private Set<AppointmentType> types = new LinkedHashSet<AppointmentType>();
	
	public Integer getTemplateId() {
		return templateId;
	}
	
	public void setTemplateId(Integer templateId) {
		this.templateId = templateId;
	}
	
	public String getName() {
		return name;
	}
	
	public void setName(String name) {
		this.name = name;
	}
	
	public Provider getProvider() {
		return provider;
	}
	
	public void setProvider(Provider provider) {
		this.provider = provider;
	}
	
	public Location getLocation() {
		return location;
	}
	
	public void setLocation(Location location) {
		this.location = location;
	}
	
	public Date getValidFrom() {
		return validFrom;
	}
	
	public void setValidFrom(Date validFrom) {
		this.validFrom = validFrom;
	}
	
	public Date getValidTo() {
		return validTo;
	}
	
	public void setValidTo(Date validTo) {
		this.validTo = validTo;
	}
	
	public Boolean getActive() {
		return active;
	}
	
	public void setActive(Boolean active) {
		this.active = active;
	}
	
	public Set<ScheduleTemplateRange> getRanges() {
		return ranges;
	}
	
	public void setRanges(Set<ScheduleTemplateRange> ranges) {
		this.ranges = ranges;
	}
	
	public void addRange(ScheduleTemplateRange range) {
		range.setTemplate(this);
		this.ranges.add(range);
	}
	
	public Set<AppointmentType> getTypes() {
		return types;
	}
	
	public void setTypes(Set<AppointmentType> types) {
		this.types = types;
	}
	
	@Override
	public Integer getId() {
		return getTemplateId();
	}
	
	@Override
	public void setId(Integer id) {
		setTemplateId(id);
	}
}
