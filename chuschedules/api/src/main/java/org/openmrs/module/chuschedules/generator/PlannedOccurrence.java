package org.openmrs.module.chuschedules.generator;

import java.time.LocalDate;

import org.openmrs.module.chuschedules.ScheduleTemplateRange;

/**
 * One candidate day, and what the generator decided about it. Included occurrences become blocks;
 * skipped ones carry the reason, which is what the preview screen shows.
 */
public final class PlannedOccurrence {
	
	private final LocalDate date;
	
	private final ScheduleTemplateRange range;
	
	private final SkipReason skipReason;
	
	private final String detail;
	
	private PlannedOccurrence(LocalDate date, ScheduleTemplateRange range, SkipReason skipReason, String detail) {
		this.date = date;
		this.range = range;
		this.skipReason = skipReason;
		this.detail = detail;
	}
	
	public static PlannedOccurrence included(LocalDate date, ScheduleTemplateRange range) {
		return new PlannedOccurrence(date, range, null, null);
	}
	
	public static PlannedOccurrence skipped(LocalDate date, ScheduleTemplateRange range, SkipReason reason, String detail) {
		return new PlannedOccurrence(date, range, reason, detail);
	}
	
	public boolean isIncluded() {
		return skipReason == null;
	}
	
	public LocalDate getDate() {
		return date;
	}
	
	public ScheduleTemplateRange getRange() {
		return range;
	}
	
	public SkipReason getSkipReason() {
		return skipReason;
	}
	
	public String getDetail() {
		return detail;
	}
	
	@Override
	public String toString() {
		return date + (isIncluded() ? " included" : " skipped: " + skipReason + (detail == null ? "" : " (" + detail + ")"));
	}
}
