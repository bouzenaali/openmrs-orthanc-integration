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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pure, framework-independent logic for turning a name-similarity score plus identity signals
 * (DICOM PatientID / PatientBirthDate vs. the OpenMRS patient) into a single 0-100 match score, and
 * for classifying a set of scores into {@link MatchTier}s.
 * <p>
 * Deliberately has no dependency on any OpenMRS class (no {@code Patient}, no {@code DicomStudy})
 * so that it can be unit tested in complete isolation from the OpenMRS runtime/database. The
 * OpenMRS-aware glue that extracts an {@link IdentityMatch} from a {@code Patient} and a
 * {@code DicomStudy} lives in {@code DicomStudyServiceImpl}.
 */
public final class MatchScoreCalculator {
	
	/**
	 * An exact DICOM PatientID match against one of the patient's identifiers is treated as
	 * conclusive: the resulting score is always 100, regardless of the name score.
	 */
	public static final int ID_MATCH_SCORE = 100;
	
	/**
	 * Penalty applied when the DICOM PatientID is present but matches none of the patient's
	 * identifiers. This is a strong negative signal (two different real people can share a name;
	 * they should not share an identifier) so the penalty is large enough to pull a same-name match
	 * out of the HIGH/VERY_HIGH range by itself in most cases.
	 */
	public static final int ID_MISMATCH_PENALTY = 40;
	
	/**
	 * Small bonus applied when the DICOM PatientBirthDate matches the patient's birthdate.
	 */
	public static final int DOB_MATCH_BONUS = 5;
	
	/**
	 * Penalty applied when the DICOM PatientBirthDate is present but does not match the patient's
	 * birthdate (e.g. a parent and child sharing a name).
	 */
	public static final int DOB_MISMATCH_PENALTY = 15;
	
	private MatchScoreCalculator() {
		// static utility class
	}
	
	/**
	 * Combines a name-similarity score with identity signals into a single 0-100 match score.
	 * <p>
	 * Rules, in order:
	 * <ol>
	 * <li>An identifier {@link IdentityMatch#MATCH} always yields {@link #ID_MATCH_SCORE} (100),
	 * regardless of the name score or the birthdate signal.</li>
	 * <li>Otherwise, start from {@code nameScore} and apply {@link #ID_MISMATCH_PENALTY} if the
	 * identifier is a {@link IdentityMatch#MISMATCH}, and {@link #DOB_MATCH_BONUS} /
	 * {@link #DOB_MISMATCH_PENALTY} depending on the birthdate signal.</li>
	 * <li>{@link IdentityMatch#UNKNOWN} signals never change the score (no data to reward or
	 * penalize).</li>
	 * </ol>
	 * The result is always clamped to [0, 100].
	 * 
	 * @param nameScore the 0-100 name-similarity score (e.g. from a fuzzy string match)
	 * @param idMatch result of comparing the DICOM PatientID against the patient's identifiers
	 * @param dobMatch result of comparing the DICOM PatientBirthDate against the patient's
	 *            birthdate
	 * @return the combined 0-100 match score
	 */
	public static int computeScore(int nameScore, IdentityMatch idMatch, IdentityMatch dobMatch) {
		if (idMatch == null) {
			idMatch = IdentityMatch.UNKNOWN;
		}
		if (dobMatch == null) {
			dobMatch = IdentityMatch.UNKNOWN;
		}
		
		if (idMatch == IdentityMatch.MATCH) {
			return ID_MATCH_SCORE;
		}
		
		int score = clamp(nameScore);
		if (idMatch == IdentityMatch.MISMATCH) {
			score -= ID_MISMATCH_PENALTY;
		}
		if (dobMatch == IdentityMatch.MATCH) {
			score += DOB_MATCH_BONUS;
		} else if (dobMatch == IdentityMatch.MISMATCH) {
			score -= DOB_MISMATCH_PENALTY;
		}
		return clamp(score);
	}
	
	/**
	 * Maps a single absolute score to its {@link MatchTier} band, using the given thresholds. Does
	 * not apply the relative {@link MatchTier#HIGHEST} marker - see {@link #classify} for that.
	 * 
	 * @param score a 0-100 match score (values outside this range are clamped)
	 * @param thresholds the configured band boundaries
	 * @return the absolute tier for this score
	 */
	public static MatchTier tierForScore(int score, MatchThresholds thresholds) {
		int clamped = clamp(score);
		if (clamped >= thresholds.getVeryHigh()) {
			return MatchTier.VERY_HIGH;
		}
		if (clamped >= thresholds.getHigh()) {
			return MatchTier.HIGH;
		}
		if (clamped >= thresholds.getMedium()) {
			return MatchTier.MEDIUM;
		}
		if (clamped >= thresholds.getLow()) {
			return MatchTier.LOW;
		}
		return MatchTier.VERY_LOW;
	}
	
	/**
	 * Classifies every score in {@code scoresByKey} into a {@link MatchTier}, additionally marking
	 * whichever entry (or entries, in case of a tie) holds the highest score in the map as
	 * {@link MatchTier#HIGHEST} - overriding whatever absolute band that score would otherwise have
	 * fallen into.
	 * <p>
	 * As a guard against a degenerate all-zero list (e.g. every candidate has an empty name and
	 * therefore a name score of 0) making every single row misleadingly show as "Highest", the
	 * HIGHEST marker is only applied when the maximum score in the map is strictly greater than 0;
	 * otherwise every entry falls back to its plain absolute tier (which will be
	 * {@link MatchTier#VERY_LOW} for all of them).
	 * 
	 * @param scoresByKey map of an arbitrary key (e.g. a study's StudyInstanceUID) to its 0-100
	 *            match score against one particular patient. A {@code null} score is treated as 0.
	 * @param thresholds the configured band boundaries
	 * @return a map, in the same iteration order as {@code scoresByKey}, of each key to its
	 *         {@link MatchTier}
	 */
	public static Map<String, MatchTier> classify(Map<String, Integer> scoresByKey, MatchThresholds thresholds) {
		Map<String, MatchTier> result = new LinkedHashMap<String, MatchTier>();
		if (scoresByKey == null || scoresByKey.isEmpty()) {
			return result;
		}
		
		int max = 0;
		for (Integer value : scoresByKey.values()) {
			max = Math.max(max, value == null ? 0 : value);
		}
		
		for (Map.Entry<String, Integer> entry : scoresByKey.entrySet()) {
			int score = entry.getValue() == null ? 0 : entry.getValue();
			MatchTier tier = (max > 0 && score == max) ? MatchTier.HIGHEST : tierForScore(score, thresholds);
			result.put(entry.getKey(), tier);
		}
		return result;
	}
	
	private static int clamp(int value) {
		return Math.max(0, Math.min(100, value));
	}
}
