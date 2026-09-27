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
 * The 4 configurable lower bounds (inclusive) of the absolute {@link MatchTier} bands:
 * 
 * <pre>
 *   score &gt;= veryHigh                    -&gt; MatchTier.VERY_HIGH
 *   high  &lt;= score &lt;  veryHigh           -&gt; MatchTier.HIGH
 *   medium &lt;= score &lt; high               -&gt; MatchTier.MEDIUM
 *   low   &lt;= score &lt;  medium             -&gt; MatchTier.LOW
 *            score &lt;  low                -&gt; MatchTier.VERY_LOW
 * </pre>
 * 
 * Instances are immutable and always internally consistent: the constructor rejects any combination
 * that is out of the [0, 100] range or not strictly decreasing (veryHigh &gt; high &gt; medium &gt;
 * low &gt;= 0), so a caller holding a {@code MatchThresholds} never needs to re-check it before
 * using it to classify a score.
 */
public final class MatchThresholds {
	
	public static final int DEFAULT_VERY_HIGH = 95;
	
	public static final int DEFAULT_HIGH = 75;
	
	public static final int DEFAULT_MEDIUM = 50;
	
	public static final int DEFAULT_LOW = 25;
	
	private final int veryHigh;
	
	private final int high;
	
	private final int medium;
	
	private final int low;
	
	/**
	 * @param veryHigh lower bound (inclusive) of the VERY_HIGH band
	 * @param high lower bound (inclusive) of the HIGH band
	 * @param medium lower bound (inclusive) of the MEDIUM band
	 * @param low lower bound (inclusive) of the LOW band
	 * @throws IllegalArgumentException if any value is outside [0, 100], or if the values are not
	 *             strictly decreasing (veryHigh &gt; high &gt; medium &gt; low &gt;= 0)
	 */
	public MatchThresholds(int veryHigh, int high, int medium, int low) {
		requireInRange("veryHigh", veryHigh);
		requireInRange("high", high);
		requireInRange("medium", medium);
		requireInRange("low", low);
		if (!(veryHigh > high && high > medium && medium > low && low >= 0)) {
			throw new IllegalArgumentException("Match thresholds must be strictly decreasing and "
			        + "non-negative (veryHigh > high > medium > low >= 0), but got veryHigh=" + veryHigh + ", high=" + high
			        + ", medium=" + medium + ", low=" + low);
		}
		this.veryHigh = veryHigh;
		this.high = high;
		this.medium = medium;
		this.low = low;
	}
	
	private static void requireInRange(String name, int value) {
		if (value < 0 || value > 100) {
			throw new IllegalArgumentException("Match threshold '" + name + "' must be between 0 and 100, got " + value);
		}
	}
	
	/**
	 * @return the thresholds hard-coded default (95 / 75 / 50 / 25), used whenever the configured
	 *         global properties are missing, non-numeric, or inconsistent with each other.
	 */
	public static MatchThresholds defaults() {
		return new MatchThresholds(DEFAULT_VERY_HIGH, DEFAULT_HIGH, DEFAULT_MEDIUM, DEFAULT_LOW);
	}
	
	public int getVeryHigh() {
		return veryHigh;
	}
	
	public int getHigh() {
		return high;
	}
	
	public int getMedium() {
		return medium;
	}
	
	public int getLow() {
		return low;
	}
	
	@Override
	public String toString() {
		return "MatchThresholds{veryHigh=" + veryHigh + ", high=" + high + ", medium=" + medium + ", low=" + low + "}";
	}
}
