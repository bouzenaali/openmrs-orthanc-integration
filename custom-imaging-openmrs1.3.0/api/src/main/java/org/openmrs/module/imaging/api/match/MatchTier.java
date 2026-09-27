/**
 * The contents of this file are subject to the OpenMRS Public License
 * Version 1.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://license.openmrs.org
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations
 * under the License.
 *
 * Copyright (C) OpenMRS, LLC.  All Rights Reserved.
 */
package org.openmrs.module.imaging.api.match;

/**
 * Confidence tier shown next to a patient/study match score on the "Get studies" and worklist
 * matching screens, so a clinician can tell at a glance how trustworthy a match is instead of
 * relying only on the raw percentage.
 * <p>
 * Five of the six tiers are absolute bands over the 0-100 match score, with the three interior
 * boundaries ({@link #VERY_HIGH}, {@link #HIGH}, {@link #MEDIUM}, {@link #LOW}) configurable via
 * global properties (see {@link MatchThresholds}). {@link #HIGHEST} is different: it is a
 * <em>relative</em> marker applied to whichever candidate(s) have the highest score within the
 * specific list being displayed (e.g. all studies being matched against one patient), regardless of
 * the absolute value of that score. See {@link MatchScoreCalculator#classify} for how it is
 * computed.
 */
public enum MatchTier {
	
	/**
	 * The best-scoring candidate(s) in the current list. This is a relative marker, not an absolute
	 * confidence level: if every candidate in a list is a poor match, the least-bad one is still
	 * labelled HIGHEST. Always show the raw percentage alongside this label so a clinician cannot
	 * mistake "best available" for "reliable".
	 */
	HIGHEST("imaging.match.tier.highest", "#0d6efd"),
	
	/** Absolute score at or above the "very high" threshold (default 95). */
	VERY_HIGH("imaging.match.tier.veryHigh", "#1e7e34"),
	
	/** Absolute score at or above the "high" threshold (default 75) but below "very high". */
	HIGH("imaging.match.tier.high", "#28a745"),
	
	/** Absolute score at or above the "medium" threshold (default 50) but below "high". */
	MEDIUM("imaging.match.tier.medium", "#ffc107"),
	
	/** Absolute score at or above the "low" threshold (default 25) but below "medium". */
	LOW("imaging.match.tier.low", "#dc3545"),
	
	/** Absolute score below the "low" threshold (default 25). */
	VERY_LOW("imaging.match.tier.veryLow", "#7a1f1f");
	
	private final String messageKey;
	
	private final String colorHex;
	
	MatchTier(String messageKey, String colorHex) {
		this.messageKey = messageKey;
		this.colorHex = colorHex;
	}
	
	/**
	 * @return the message key to resolve a localized, human-readable label for this tier (e.g. via
	 *         {@code ui.message(tier.getMessageKey())} in a GSP). Kept separate from
	 *         {@link #name()} because {@link #name()} is used as the stable, non-localized value
	 *         returned by the REST API.
	 */
	public String getMessageKey() {
		return messageKey;
	}
	
	/**
	 * @return the hex color (e.g. "#28a745") used to render this tier's badge in the UI.
	 */
	public String getColorHex() {
		return colorHex;
	}
}
