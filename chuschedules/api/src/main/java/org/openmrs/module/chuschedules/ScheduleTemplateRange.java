package org.openmrs.module.chuschedules;

import java.sql.Time;
import java.util.Date;

import org.openmrs.BaseOpenmrsData;

/**
 * One recurring session within a template: a weekday, a start and end time, and the rule by which
 * it repeats. Several ranges may share a weekday -- a morning and an afternoon clinic on the same
 * day are two ranges, which is also how staff describe them. Each generates its own block.
 */
public class ScheduleTemplateRange extends BaseOpenmrsData {
	
	private static final long serialVersionUID = 1L;
	
	private Integer rangeId;
	
	private ScheduleTemplate template;
	
	/** java.util.Calendar convention: 1 = Sunday .. 7 = Saturday. */
	private Integer dayOfWeek;
	
	private Time startTime;
	
	private Time endTime;
	
	private RecurrenceType recurrenceType = RecurrenceType.WEEKLY;
	
	/** WEEKLY only. 1 = every week, 2 = every other week, 3 = one week in three. */
	private Integer weekInterval = 1;
	
	/**
	 * WEEKLY only, and only meaningful when weekInterval > 1: the occurrence the cycle is counted
	 * from. Always stored already normalised onto {@link #dayOfWeek}.
	 */
	private Date anchorDate;
	
	/** MONTHLY_NTH only. 1..4, or -1 for the last such weekday of the month. */
	private Integer monthOrdinal;
	
	public Integer getRangeId() {
		return rangeId;
	}
	
	public void setRangeId(Integer rangeId) {
		this.rangeId = rangeId;
	}
	
	public ScheduleTemplate getTemplate() {
		return template;
	}
	
	public void setTemplate(ScheduleTemplate template) {
		this.template = template;
	}
	
	public Integer getDayOfWeek() {
		return dayOfWeek;
	}
	
	public void setDayOfWeek(Integer dayOfWeek) {
		this.dayOfWeek = dayOfWeek;
	}
	
	public Time getStartTime() {
		return startTime;
	}
	
	public void setStartTime(Time startTime) {
		this.startTime = startTime;
	}
	
	public Time getEndTime() {
		return endTime;
	}
	
	public void setEndTime(Time endTime) {
		this.endTime = endTime;
	}
	
	public RecurrenceType getRecurrenceType() {
		return recurrenceType;
	}
	
	public void setRecurrenceType(RecurrenceType recurrenceType) {
		this.recurrenceType = recurrenceType;
	}
	
	public Integer getWeekInterval() {
		return weekInterval;
	}
	
	public void setWeekInterval(Integer weekInterval) {
		this.weekInterval = weekInterval;
	}
	
	public Date getAnchorDate() {
		return anchorDate;
	}
	
	public void setAnchorDate(Date anchorDate) {
		this.anchorDate = anchorDate;
	}
	
	public Integer getMonthOrdinal() {
		return monthOrdinal;
	}
	
	public void setMonthOrdinal(Integer monthOrdinal) {
		this.monthOrdinal = monthOrdinal;
	}
	
	@Override
	public Integer getId() {
		return getRangeId();
	}
	
	@Override
	public void setId(Integer id) {
		setRangeId(id);
	}
}
