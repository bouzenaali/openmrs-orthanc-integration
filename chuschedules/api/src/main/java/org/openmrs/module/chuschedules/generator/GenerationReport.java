package org.openmrs.module.chuschedules.generator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * What a generation run did, or -- in dry-run mode -- would have done. Dry run and real run walk
 * identical code paths; only the final write is suppressed. That is what makes the preview
 * trustworthy: it is the generator itself reporting, not a separate estimate that could quietly
 * disagree with the write.
 */
public class GenerationReport {
	
	private final boolean dryRun;
	
	private final List<PlannedOccurrence> occurrences = new ArrayList<PlannedOccurrence>();
	
	private final List<String> createdBlockUuids = new ArrayList<String>();
	
	public GenerationReport(boolean dryRun) {
		this.dryRun = dryRun;
	}
	
	public boolean isDryRun() {
		return dryRun;
	}
	
	public void add(PlannedOccurrence occurrence) {
		occurrences.add(occurrence);
	}
	
	public void recordCreatedBlock(String uuid) {
		createdBlockUuids.add(uuid);
	}
	
	public List<PlannedOccurrence> getOccurrences() {
		return Collections.unmodifiableList(occurrences);
	}
	
	/** The authoritative list of what to undo if a run turns out to be wrong. */
	public List<String> getCreatedBlockUuids() {
		return Collections.unmodifiableList(createdBlockUuids);
	}
	
	public List<PlannedOccurrence> getCreated() {
		List<PlannedOccurrence> created = new ArrayList<PlannedOccurrence>();
		for (PlannedOccurrence o : occurrences) {
			if (o.isIncluded()) {
				created.add(o);
			}
		}
		return created;
	}
	
	public int getCreatedCount() {
		return getCreated().size();
	}
	
	public int getSkippedCount() {
		return occurrences.size() - getCreatedCount();
	}
	
	/**
	 * Skips a person may need to act on, listed individually: holiday and leave clashes, and
	 * collisions with blocks that already exist.
	 */
	public List<PlannedOccurrence> getSkippedNeedingAttention() {
		List<PlannedOccurrence> out = new ArrayList<PlannedOccurrence>();
		for (PlannedOccurrence o : occurrences) {
			if (!o.isIncluded() && o.getSkipReason().warrantsItsOwnRow()) {
				out.add(o);
			}
		}
		return out;
	}
	
	/**
	 * Counts of the bulk skips, to be shown as one line each rather than one row per date.
	 */
	public Map<SkipReason, Integer> getBulkSkipCounts() {
		Map<SkipReason, Integer> counts = new EnumMap<SkipReason, Integer>(SkipReason.class);
		for (PlannedOccurrence o : occurrences) {
			if (!o.isIncluded() && !o.getSkipReason().warrantsItsOwnRow()) {
				Integer n = counts.get(o.getSkipReason());
				counts.put(o.getSkipReason(), n == null ? 1 : n + 1);
			}
		}
		return counts;
	}
	
	public Map<SkipReason, Integer> getSkipCounts() {
		Map<SkipReason, Integer> counts = new EnumMap<SkipReason, Integer>(SkipReason.class);
		for (PlannedOccurrence o : occurrences) {
			if (!o.isIncluded()) {
				Integer n = counts.get(o.getSkipReason());
				counts.put(o.getSkipReason(), n == null ? 1 : n + 1);
			}
		}
		return counts;
	}
	
	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append(dryRun ? "DRY RUN: would create " : "Created ").append(getCreatedCount()).append(" block(s); ")
		        .append(getSkippedCount()).append(" skipped");
		Map<SkipReason, Integer> counts = getSkipCounts();
		if (!counts.isEmpty()) {
			sb.append(" (");
			boolean first = true;
			for (Map.Entry<SkipReason, Integer> e : counts.entrySet()) {
				if (!first) {
					sb.append(", ");
				}
				sb.append(e.getKey()).append(": ").append(e.getValue());
				first = false;
			}
			sb.append(")");
		}
		return sb.toString();
	}
}
