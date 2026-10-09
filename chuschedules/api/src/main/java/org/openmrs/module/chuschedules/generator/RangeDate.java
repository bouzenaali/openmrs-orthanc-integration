package org.openmrs.module.chuschedules.generator;

import java.time.LocalDate;

/**
 * The identity of one generated occurrence: a range and the day it was generated for. Mirrors the
 * unique constraint that enforces idempotency in the database.
 */
public final class RangeDate {
	
	private final Integer rangeId;
	
	private final LocalDate date;
	
	public RangeDate(Integer rangeId, LocalDate date) {
		this.rangeId = rangeId;
		this.date = date;
	}
	
	public Integer getRangeId() {
		return rangeId;
	}
	
	public LocalDate getDate() {
		return date;
	}
	
	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof RangeDate)) {
			return false;
		}
		RangeDate other = (RangeDate) o;
		return (rangeId == null ? other.rangeId == null : rangeId.equals(other.rangeId))
		        && (date == null ? other.date == null : date.equals(other.date));
	}
	
	@Override
	public int hashCode() {
		return (rangeId == null ? 0 : rangeId.hashCode()) * 31 + (date == null ? 0 : date.hashCode());
	}
	
	@Override
	public String toString() {
		return "range " + rangeId + " on " + date;
	}
}
