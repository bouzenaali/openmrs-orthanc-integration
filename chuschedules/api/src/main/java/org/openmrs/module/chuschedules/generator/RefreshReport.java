package org.openmrs.module.chuschedules.generator;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What happened when a template's future blocks were re-synchronised after its pattern changed. The
 * number that matters is {@link #getKeptBooked()}: those are future clinics whose hours the user
 * just changed but which already have patients booked. They are deliberately left standing at the
 * old times, and a human has to decide what to tell those patients. A silently moved or deleted
 * booked clinic is the worst outcome this module could produce, so they are counted, listed, and
 * put in front of whoever made the change.
 */
public class RefreshReport {
	
	private final List<LocalDate> voided = new ArrayList<LocalDate>();
	
	private final List<LocalDate> keptBooked = new ArrayList<LocalDate>();
	
	private int alreadyGone;
	
	public void recordVoided(LocalDate date) {
		voided.add(date);
	}
	
	public void recordKeptBooked(LocalDate date) {
		keptBooked.add(date);
	}
	
	public void recordAlreadyGone() {
		alreadyGone++;
	}
	
	public List<LocalDate> getVoided() {
		return Collections.unmodifiableList(voided);
	}
	
	/** Future dates left untouched because a patient is booked into them. */
	public List<LocalDate> getKeptBooked() {
		return Collections.unmodifiableList(keptBooked);
	}
	
	/** Provenance rows whose block had already been removed by hand. Expected, not an error. */
	public int getAlreadyGone() {
		return alreadyGone;
	}
	
	public int getVoidedCount() {
		return voided.size();
	}
	
	public int getKeptBookedCount() {
		return keptBooked.size();
	}
	
	public boolean isEmpty() {
		return voided.isEmpty() && keptBooked.isEmpty() && alreadyGone == 0;
	}
	
	@Override
	public String toString() {
		return "refresh: " + getVoidedCount() + " future empty block(s) voided, " + getKeptBookedCount()
		        + " kept because booked, " + alreadyGone + " already gone";
	}
}
