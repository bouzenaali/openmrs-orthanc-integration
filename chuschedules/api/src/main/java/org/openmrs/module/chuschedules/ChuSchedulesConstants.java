package org.openmrs.module.chuschedules;

/**
 * A template sets months of hospital-wide availability in one action, so it gets its own privilege
 * rather than riding on the existing appointment-scheduling ones.
 */
public final class ChuSchedulesConstants {
	
	public static final String PRIVILEGE_MANAGE = "Manage Recurring Schedules";
	
	public static final String MODULE_ID = "chuschedules";
	
	/** How far ahead the nightly task keeps the calendar filled. */
	public static final String GP_ROLLING_HORIZON_DAYS = "chuschedules.rollingHorizonDays";
	
	public static final int DEFAULT_ROLLING_HORIZON_DAYS = 365;
	
	public static final String TASK_NAME = "CHU Recurring Schedules - génération glissante";
	
	private ChuSchedulesConstants() {
	}
}
