package org.openmrs.module.chuschedules;

import java.util.Date;

import org.openmrs.BaseOpenmrsData;

/**
 * Provenance: which appointment block this module created, from which range, for which date. Two
 * jobs. First, idempotency. A unique constraint on (range, target date) makes double-generation
 * impossible in the database rather than merely unlikely in code, so re-running a generation is
 * always safe. Second, rollback. If a generation turns out to be wrong, this table is the
 * authoritative list of exactly what to undo -- blocks entered by hand are not in it and so are
 * never at risk. The block is referenced by uuid with no foreign key on purpose: the appointment
 * block table belongs to another module, and a constraint would couple our schema to its liquibase
 * and could block its upgrades. "Block no longer exists" is an ordinary case we handle, not an
 * error.
 */
public class GeneratedBlock extends BaseOpenmrsData {
	
	private static final long serialVersionUID = 1L;
	
	private Integer generatedId;
	
	private ScheduleTemplate template;
	
	private ScheduleTemplateRange range;
	
	private Date targetDate;
	
	private String blockUuid;
	
	public Integer getGeneratedId() {
		return generatedId;
	}
	
	public void setGeneratedId(Integer generatedId) {
		this.generatedId = generatedId;
	}
	
	public ScheduleTemplate getTemplate() {
		return template;
	}
	
	public void setTemplate(ScheduleTemplate template) {
		this.template = template;
	}
	
	public ScheduleTemplateRange getRange() {
		return range;
	}
	
	public void setRange(ScheduleTemplateRange range) {
		this.range = range;
	}
	
	public Date getTargetDate() {
		return targetDate;
	}
	
	public void setTargetDate(Date targetDate) {
		this.targetDate = targetDate;
	}
	
	public String getBlockUuid() {
		return blockUuid;
	}
	
	public void setBlockUuid(String blockUuid) {
		this.blockUuid = blockUuid;
	}
	
	@Override
	public Integer getId() {
		return getGeneratedId();
	}
	
	@Override
	public void setId(Integer id) {
		setGeneratedId(id);
	}
}
