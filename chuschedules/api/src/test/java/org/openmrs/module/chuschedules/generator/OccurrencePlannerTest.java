package org.openmrs.module.chuschedules.generator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.sql.Time;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;
import org.openmrs.module.chuschedules.RecurrenceType;
import org.openmrs.module.chuschedules.ScheduleTemplate;
import org.openmrs.module.chuschedules.ScheduleTemplateRange;

/**
 * The invariants that matter clinically. September 2026 Tuesdays are 1, 8, 15, 22, 29.
 */
public class OccurrencePlannerTest {
	
	private static final int MONDAY = 2;
	
	private static final int TUESDAY = 3;
	
	private static final LocalDate TODAY = LocalDate.of(2026, 9, 16);
	
	// --- helpers ---
	
	private ScheduleTemplate template(LocalDate validFrom, LocalDate validTo, ScheduleTemplateRange... ranges) {
		ScheduleTemplate t = new ScheduleTemplate();
		t.setName("Consultation");
		t.setActive(Boolean.TRUE);
		t.setValidFrom(validFrom == null ? null : java.sql.Date.valueOf(validFrom));
		t.setValidTo(validTo == null ? null : java.sql.Date.valueOf(validTo));
		for (ScheduleTemplateRange r : ranges) {
			t.addRange(r);
		}
		return t;
	}
	
	private ScheduleTemplateRange weekly(int id, int dayOfWeek, int interval, LocalDate anchor) {
		ScheduleTemplateRange r = new ScheduleTemplateRange();
		r.setRangeId(id);
		r.setDayOfWeek(dayOfWeek);
		r.setStartTime(Time.valueOf("08:00:00"));
		r.setEndTime(Time.valueOf("12:00:00"));
		r.setRecurrenceType(RecurrenceType.WEEKLY);
		r.setWeekInterval(interval);
		r.setAnchorDate(anchor == null ? null : java.sql.Date.valueOf(anchor));
		return r;
	}
	
	private ScheduleTemplateRange monthly(int id, int dayOfWeek, int ordinal) {
		ScheduleTemplateRange r = new ScheduleTemplateRange();
		r.setRangeId(id);
		r.setDayOfWeek(dayOfWeek);
		r.setStartTime(Time.valueOf("14:00:00"));
		r.setEndTime(Time.valueOf("17:00:00"));
		r.setRecurrenceType(RecurrenceType.MONTHLY_NTH);
		r.setMonthOrdinal(ordinal);
		return r;
	}
	
	private List<LocalDate> includedDates(List<PlannedOccurrence> plan) {
		List<LocalDate> dates = new ArrayList<LocalDate>();
		for (PlannedOccurrence o : plan) {
			if (o.isIncluded()) {
				dates.add(o.getDate());
			}
		}
		return dates;
	}
	
	private List<PlannedOccurrence> skipped(List<PlannedOccurrence> plan, SkipReason reason) {
		List<PlannedOccurrence> out = new ArrayList<PlannedOccurrence>();
		for (PlannedOccurrence o : plan) {
			if (!o.isIncluded() && o.getSkipReason() == reason) {
				out.add(o);
			}
		}
		return out;
	}
	
	private Map<LocalDate, String> noExclusions() {
		return Collections.emptyMap();
	}
	
	private Set<RangeDate> nothingGenerated() {
		return Collections.emptySet();
	}
	
	// --- the core invariant: never write to the past ---
	
	@Test
	public void neverGeneratesOnOrBeforeToday() {
		ScheduleTemplate t = template(LocalDate.of(2026, 9, 1), null, weekly(1, TUESDAY, 1, null));
		List<PlannedOccurrence> plan = OccurrencePlanner.plan(t, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), TODAY,
		    noExclusions(), nothingGenerated());
		
		// Tuesdays 1 and 15 are in the past relative to 16 Sept; 8 too.
		assertEquals(3, skipped(plan, SkipReason.PAST).size());
		assertEquals(java.util.Arrays.asList(LocalDate.of(2026, 9, 22), LocalDate.of(2026, 9, 29)), includedDates(plan));
	}
	
	@Test
	public void todayItselfIsNeverGenerated() {
		// 16 Sept 2026 is a Wednesday; generate Wednesdays and confirm today is skipped.
		ScheduleTemplate t = template(LocalDate.of(2026, 9, 1), null, weekly(1, 4 /* Wednesday */, 1, null));
		List<PlannedOccurrence> plan = OccurrencePlanner.plan(t, TODAY, TODAY, TODAY, noExclusions(), nothingGenerated());
		assertTrue(includedDates(plan).isEmpty());
		assertEquals(1, skipped(plan, SkipReason.PAST).size());
	}
	
	// --- rotation ---
	
	@Test
	public void everyOtherTuesday_producesAlternatingDates() {
		ScheduleTemplate t = template(LocalDate.of(2026, 9, 1), null, weekly(1, TUESDAY, 2, LocalDate.of(2026, 9, 1)));
		List<PlannedOccurrence> plan = OccurrencePlanner.plan(t, LocalDate.of(2026, 9, 17), LocalDate.of(2026, 10, 31),
		    TODAY, noExclusions(), nothingGenerated());
		
		// From the 1 Sept anchor the cycle lands on 29 Sept, 13 Oct, 27 Oct.
		assertEquals(
		    java.util.Arrays.asList(LocalDate.of(2026, 9, 29), LocalDate.of(2026, 10, 13), LocalDate.of(2026, 10, 27)),
		    includedDates(plan));
	}
	
	@Test
	public void rotatingRuleWithoutAnchorCountsFromTemplateValidity() {
		ScheduleTemplate t = template(LocalDate.of(2026, 9, 1), null, weekly(1, TUESDAY, 2, null));
		List<PlannedOccurrence> plan = OccurrencePlanner.plan(t, LocalDate.of(2026, 9, 17), LocalDate.of(2026, 10, 31),
		    TODAY, noExclusions(), nothingGenerated());
		assertEquals(
		    java.util.Arrays.asList(LocalDate.of(2026, 9, 29), LocalDate.of(2026, 10, 13), LocalDate.of(2026, 10, 27)),
		    includedDates(plan));
	}
	
	@Test
	public void firstMondayOfTheMonth() {
		ScheduleTemplate t = template(LocalDate.of(2026, 9, 1), null, monthly(1, MONDAY, 1));
		List<PlannedOccurrence> plan = OccurrencePlanner.plan(t, LocalDate.of(2026, 9, 17), LocalDate.of(2026, 12, 31),
		    TODAY, noExclusions(), nothingGenerated());
		assertEquals(
		    java.util.Arrays.asList(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 11, 2), LocalDate.of(2026, 12, 7)),
		    includedDates(plan));
	}
	
	@Test
	public void weeklyAndMonthlyRangesCoexistInOneTemplate() {
		ScheduleTemplate t = template(LocalDate.of(2026, 9, 1), null, weekly(1, TUESDAY, 1, null), monthly(2, MONDAY, 1));
		List<PlannedOccurrence> plan = OccurrencePlanner.plan(t, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31),
		    TODAY, noExclusions(), nothingGenerated());
		// Tuesdays 6, 13, 20, 27 plus the first Monday, 5 Oct.
		assertEquals(java.util.Arrays.asList(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 6),
		    LocalDate.of(2026, 10, 13), LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 27)), includedDates(plan));
	}
	
	@Test
	public void twoRangesOnTheSameDayBothGenerate() {
		// A morning and an afternoon clinic: two blocks, not one.
		ScheduleTemplateRange morning = weekly(1, TUESDAY, 1, null);
		ScheduleTemplateRange afternoon = weekly(2, TUESDAY, 1, null);
		afternoon.setStartTime(Time.valueOf("13:00:00"));
		afternoon.setEndTime(Time.valueOf("16:00:00"));
		
		ScheduleTemplate t = template(LocalDate.of(2026, 9, 1), null, morning, afternoon);
		List<PlannedOccurrence> plan = OccurrencePlanner.plan(t, LocalDate.of(2026, 9, 22), LocalDate.of(2026, 9, 22),
		    TODAY, noExclusions(), nothingGenerated());
		assertEquals(2, includedDates(plan).size());
	}
	
	// --- exclusions ---
	
	@Test
	public void holidaysAreSkippedWithTheirReason() {
		Map<LocalDate, String> exclusions = new HashMap<LocalDate, String>();
		exclusions.put(LocalDate.of(2026, 9, 22), "Aid el-Fitr");
		
		ScheduleTemplate t = template(LocalDate.of(2026, 9, 1), null, weekly(1, TUESDAY, 1, null));
		List<PlannedOccurrence> plan = OccurrencePlanner.plan(t, LocalDate.of(2026, 9, 17), LocalDate.of(2026, 9, 30),
		    TODAY, exclusions, nothingGenerated());
		
		assertEquals(java.util.Collections.singletonList(LocalDate.of(2026, 9, 29)), includedDates(plan));
		List<PlannedOccurrence> holidays = skipped(plan, SkipReason.EXCEPTION);
		assertEquals(1, holidays.size());
		assertEquals("Aid el-Fitr", holidays.get(0).getDetail());
	}
	
	// --- idempotency ---
	
	@Test
	public void alreadyGeneratedOccurrencesAreNotGeneratedAgain() {
		Set<RangeDate> already = new HashSet<RangeDate>();
		already.add(new RangeDate(1, LocalDate.of(2026, 9, 22)));
		
		ScheduleTemplate t = template(LocalDate.of(2026, 9, 1), null, weekly(1, TUESDAY, 1, null));
		List<PlannedOccurrence> plan = OccurrencePlanner.plan(t, LocalDate.of(2026, 9, 17), LocalDate.of(2026, 9, 30),
		    TODAY, noExclusions(), already);
		
		assertEquals(java.util.Collections.singletonList(LocalDate.of(2026, 9, 29)), includedDates(plan));
		assertEquals(1, skipped(plan, SkipReason.ALREADY_GENERATED).size());
	}
	
	@Test
	public void rerunningOverTheSameRangeProducesNothingNew() {
		ScheduleTemplate t = template(LocalDate.of(2026, 9, 1), null, weekly(1, TUESDAY, 1, null));
		List<PlannedOccurrence> first = OccurrencePlanner.plan(t, LocalDate.of(2026, 9, 17), LocalDate.of(2026, 9, 30),
		    TODAY, noExclusions(), nothingGenerated());
		
		Set<RangeDate> already = new HashSet<RangeDate>();
		for (LocalDate d : includedDates(first)) {
			already.add(new RangeDate(1, d));
		}
		
		List<PlannedOccurrence> second = OccurrencePlanner.plan(t, LocalDate.of(2026, 9, 17), LocalDate.of(2026, 9, 30),
		    TODAY, noExclusions(), already);
		assertTrue(includedDates(second).isEmpty());
	}
	
	// --- validity and activation ---
	
	@Test
	public void datesOutsideValidityAreSkipped() {
		ScheduleTemplate t = template(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 15), weekly(1, TUESDAY, 1, null));
		List<PlannedOccurrence> plan = OccurrencePlanner.plan(t, LocalDate.of(2026, 9, 17), LocalDate.of(2026, 10, 31),
		    TODAY, noExclusions(), nothingGenerated());
		assertEquals(java.util.Arrays.asList(LocalDate.of(2026, 10, 6), LocalDate.of(2026, 10, 13)), includedDates(plan));
		assertFalse(skipped(plan, SkipReason.OUTSIDE_VALIDITY).isEmpty());
	}
	
	@Test
	public void openEndedValidityKeepsGenerating() {
		ScheduleTemplate t = template(LocalDate.of(2026, 9, 1), null, weekly(1, TUESDAY, 1, null));
		List<PlannedOccurrence> plan = OccurrencePlanner.plan(t, LocalDate.of(2027, 8, 1), LocalDate.of(2027, 8, 31), TODAY,
		    noExclusions(), nothingGenerated());
		assertFalse(includedDates(plan).isEmpty());
	}
	
	@Test
	public void inactiveTemplateGeneratesNothing() {
		ScheduleTemplate t = template(LocalDate.of(2026, 9, 1), null, weekly(1, TUESDAY, 1, null));
		t.setActive(Boolean.FALSE);
		List<PlannedOccurrence> plan = OccurrencePlanner.plan(t, LocalDate.of(2026, 9, 17), LocalDate.of(2026, 9, 30),
		    TODAY, noExclusions(), nothingGenerated());
		assertTrue(includedDates(plan).isEmpty());
		assertEquals(2, skipped(plan, SkipReason.TEMPLATE_INACTIVE).size());
	}
	
	// --- the horizon you asked for ---
	
	@Test
	public void aFullYearHorizonGeneratesEveryOccurrence() {
		ScheduleTemplate t = template(LocalDate.of(2026, 9, 1), null, weekly(1, TUESDAY, 1, null));
		List<PlannedOccurrence> plan = OccurrencePlanner.plan(t, TODAY.plusDays(1), TODAY.plusYears(1), TODAY,
		    noExclusions(), nothingGenerated());
		// 17 Sept 2026 .. 16 Sept 2027 inclusive contains 52 Tuesdays.
		assertEquals(52, includedDates(plan).size());
	}
	
	// --- input validation ---
	
	@Test(expected = IllegalArgumentException.class)
	public void rejectsBackwardsRange() {
		ScheduleTemplate t = template(LocalDate.of(2026, 9, 1), null, weekly(1, TUESDAY, 1, null));
		OccurrencePlanner.plan(t, LocalDate.of(2026, 9, 30), LocalDate.of(2026, 9, 1), TODAY, noExclusions(),
		    nothingGenerated());
	}
	
	@Test
	public void templateWithNoRangesPlansNothing() {
		ScheduleTemplate t = template(LocalDate.of(2026, 9, 1), null);
		assertTrue(OccurrencePlanner.plan(t, LocalDate.of(2026, 9, 17), LocalDate.of(2026, 9, 30), TODAY, noExclusions(),
		    nothingGenerated()).isEmpty());
	}
}
