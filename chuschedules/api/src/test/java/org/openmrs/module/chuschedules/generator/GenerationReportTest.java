package org.openmrs.module.chuschedules.generator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.time.LocalDate;

import org.junit.Test;
import org.openmrs.module.chuschedules.ScheduleTemplateRange;

/**
 * The report's job is to put the two skips a person can act on in front of them, and to stop the
 * hundreds they cannot act on from burying those two.
 */
public class GenerationReportTest {
	
	private ScheduleTemplateRange range() {
		ScheduleTemplateRange r = new ScheduleTemplateRange();
		r.setRangeId(1);
		return r;
	}
	
	private PlannedOccurrence skip(int day, SkipReason reason, String detail) {
		return PlannedOccurrence.skipped(LocalDate.of(2026, 10, day), range(), reason, detail);
	}
	
	@Test
	public void holidaysAndOverlapsAreListedIndividually() {
		GenerationReport r = new GenerationReport(true);
		r.add(skip(1, SkipReason.EXCEPTION, "Aid el-Fitr"));
		r.add(skip(2, SkipReason.OVERLAPS_EXISTING, "uuid-1234"));
		
		assertEquals(2, r.getSkippedNeedingAttention().size());
		assertTrue(r.getBulkSkipCounts().isEmpty());
	}
	
	@Test
	public void outOfValidityCollapsesToACountInsteadOfHundredsOfRows() {
		GenerationReport r = new GenerationReport(true);
		for (int day = 1; day <= 25; day++) {
			r.add(skip(day, SkipReason.OUTSIDE_VALIDITY, null));
		}
		assertTrue(r.getSkippedNeedingAttention().isEmpty());
		assertEquals(Integer.valueOf(25), r.getBulkSkipCounts().get(SkipReason.OUTSIDE_VALIDITY));
	}
	
	@Test
	public void theTwoActionableRowsSurviveAmongstHundredsOfBulkSkips() {
		// The case that motivated this: 224 identical rows hiding the ones that matter.
		GenerationReport r = new GenerationReport(true);
		for (int day = 1; day <= 28; day++) {
			r.add(skip(day, SkipReason.OUTSIDE_VALIDITY, null));
		}
		r.add(skip(5, SkipReason.EXCEPTION, "Aid el-Fitr"));
		r.add(skip(6, SkipReason.OVERLAPS_EXISTING, "uuid-1234"));
		
		assertEquals(2, r.getSkippedNeedingAttention().size());
		assertEquals("Aid el-Fitr", r.getSkippedNeedingAttention().get(0).getDetail());
		assertEquals(Integer.valueOf(28), r.getBulkSkipCounts().get(SkipReason.OUTSIDE_VALIDITY));
	}
	
	@Test
	public void everyBulkReasonIsCountedSeparately() {
		GenerationReport r = new GenerationReport(true);
		r.add(skip(1, SkipReason.PAST, null));
		r.add(skip(2, SkipReason.PAST, null));
		r.add(skip(3, SkipReason.ALREADY_GENERATED, null));
		r.add(skip(4, SkipReason.TEMPLATE_INACTIVE, null));
		
		assertEquals(Integer.valueOf(2), r.getBulkSkipCounts().get(SkipReason.PAST));
		assertEquals(Integer.valueOf(1), r.getBulkSkipCounts().get(SkipReason.ALREADY_GENERATED));
		assertEquals(Integer.valueOf(1), r.getBulkSkipCounts().get(SkipReason.TEMPLATE_INACTIVE));
	}
	
	@Test
	public void bulkAndDetailedTogetherAccountForEverySkip() {
		GenerationReport r = new GenerationReport(true);
		for (int day = 1; day <= 10; day++) {
			r.add(skip(day, SkipReason.OUTSIDE_VALIDITY, null));
		}
		r.add(skip(11, SkipReason.EXCEPTION, "congé"));
		r.add(PlannedOccurrence.included(LocalDate.of(2026, 10, 12), range()));
		
		int bulk = 0;
		for (Integer n : r.getBulkSkipCounts().values()) {
			bulk += n;
		}
		assertEquals(r.getSkippedCount(), bulk + r.getSkippedNeedingAttention().size());
		assertEquals(1, r.getCreatedCount());
	}
	
	@Test
	public void onlyExceptionAndOverlapWarrantTheirOwnRow() {
		assertTrue(SkipReason.EXCEPTION.warrantsItsOwnRow());
		assertTrue(SkipReason.OVERLAPS_EXISTING.warrantsItsOwnRow());
		for (SkipReason r : new SkipReason[] { SkipReason.PAST, SkipReason.OUTSIDE_VALIDITY, SkipReason.TEMPLATE_INACTIVE,
		        SkipReason.ALREADY_GENERATED }) {
			assertTrue(r + " should not get its own row", !r.warrantsItsOwnRow());
		}
	}
}
