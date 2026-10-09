package org.openmrs.module.chuschedules;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.Privilege;
import org.openmrs.api.UserService;
import org.openmrs.api.context.Context;
import org.openmrs.module.BaseModuleActivator;
import org.openmrs.scheduler.SchedulerService;
import org.openmrs.scheduler.TaskDefinition;

/**
 * Stopping this module stops generation and nothing else. Blocks it has already created are
 * ordinary appointment blocks and stay valid, bookable and editable in the existing UI, so there is
 * never anything to migrate back.
 */
public class ChuSchedulesActivator extends BaseModuleActivator {
	
	private static final Log log = LogFactory.getLog(ChuSchedulesActivator.class);
	
	@Override
	public void started() {
		ensurePrivilegeExists();
		ensureRollingTaskRegistered();
		log.info("Started CHU Recurring Schedules");
	}
	
	@Override
	public void stopped() {
		log.info("Stopped CHU Recurring Schedules; generated blocks are unaffected");
	}
	
	/** The next occurrence of the given hour, local time. */
	private static java.util.Date nextAt(int hourOfDay) {
		java.time.LocalDateTime next = java.time.LocalDate.now().atTime(hourOfDay, 0);
		if (!next.isAfter(java.time.LocalDateTime.now())) {
			next = next.plusDays(1);
		}
		return java.util.Date.from(next.atZone(java.time.ZoneId.systemDefault()).toInstant());
	}
	
	/**
	 * Registers the nightly rolling-generation task, and leaves it STOPPED. Deliberate: filling a
	 * year of hospital-wide availability on a timer is a decision someone makes on purpose, in
	 * Administration → Planificateur. Installing a module must never silently start writing to the
	 * calendar. Registering it anyway means an admin can find and enable it without having to know
	 * the class name.
	 */
	private void ensureRollingTaskRegistered() {
		try {
			SchedulerService scheduler = Context.getSchedulerService();
			if (scheduler.getTaskByName(ChuSchedulesConstants.TASK_NAME) != null) {
				return;
			}
			TaskDefinition task = new TaskDefinition();
			task.setName(ChuSchedulesConstants.TASK_NAME);
			task.setDescription("Génère chaque nuit les tranches de rendez-vous des horaires "
			        + "récurrents actifs, jusqu'à l'horizon défini par " + ChuSchedulesConstants.GP_ROLLING_HORIZON_DAYS
			        + ". Idempotent : les dates " + "déjà générées ne sont jamais recréées.");
			task.setTaskClass(RollingGenerationTask.class.getName());
			task.setRepeatInterval(24 * 60 * 60L); // once a day
			// A start time is required, not cosmetic: a task registered without one does
			// nothing when an admin presses Start, with no error to explain why. 02:00 keeps
			// the write well away from clinic hours.
			task.setStartTime(nextAt(2));
			task.setStartOnStartup(Boolean.FALSE);
			task.setStarted(Boolean.FALSE);
			scheduler.saveTaskDefinition(task);
			log.info("Registered scheduled task (stopped): " + ChuSchedulesConstants.TASK_NAME);
		}
		catch (Exception e) {
			// A scheduler problem must never stop the module, or the application, from starting.
			log.error("Could not register the rolling generation task", e);
		}
	}
	
	/**
	 * Created here rather than in liquibase so that it is restored if someone deletes it, and so
	 * the description explains what granting it actually means.
	 */
	private void ensurePrivilegeExists() {
		UserService userService = Context.getUserService();
		Privilege existing = userService.getPrivilege(ChuSchedulesConstants.PRIVILEGE_MANAGE);
		if (existing != null) {
			return;
		}
		Context.addProxyPrivilege("Manage Privileges");
		try {
			Privilege privilege = new Privilege(ChuSchedulesConstants.PRIVILEGE_MANAGE,
			        "Create and generate recurring provider schedules. Grants the ability to set "
			                + "months of clinic availability in a single action.");
			userService.savePrivilege(privilege);
			log.info("Created privilege: " + ChuSchedulesConstants.PRIVILEGE_MANAGE);
		}
		catch (Exception e) {
			// Never prevent the module, or the application, from starting over a privilege.
			log.error("Could not create the " + ChuSchedulesConstants.PRIVILEGE_MANAGE + " privilege", e);
		}
		finally {
			Context.removeProxyPrivilege("Manage Privileges");
		}
	}
}
