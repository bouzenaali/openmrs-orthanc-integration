package org.openmrs.module.chuschedules;

import java.time.LocalDate;
import java.util.Date;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.api.context.Context;
import org.openmrs.module.chuschedules.api.ChuSchedulesService;
import org.openmrs.module.chuschedules.api.impl.ChuSchedulesServiceImpl;
import org.openmrs.module.chuschedules.generator.GenerationReport;
import org.openmrs.scheduler.tasks.AbstractTask;

/**
 * Keeps the calendar filled to a rolling horizon, so nobody has to remember to press Générer. It
 * adds only the days that have newly come within the horizon since the last run, because generation
 * is idempotent: every date already generated is skipped at the database level, not re-created. A
 * nightly run on a settled schedule therefore creates a handful of blocks and reports the rest as
 * already generated. The task is registered on module start but deliberately left STOPPED. Filling
 * a year of hospital-wide availability on a timer is a decision someone should make on purpose, in
 * Administration → Planificateur, not something that begins the moment the module is installed.
 */
public class RollingGenerationTask extends AbstractTask {
	
	private static final Log log = LogFactory.getLog(RollingGenerationTask.class);
	
	@Override
	public void execute() {
		if (isExecuting) {
			log.warn("chuschedules rolling generation is already running; skipping this tick");
			return;
		}
		startExecuting();
		try {
			// No authenticate() call: the scheduler already runs tasks inside an authenticated
			// daemon context (Daemon.executeScheduledTask), and AbstractTask has no such method
			// on this platform version.
			
			// The proxy privilege is belt-and-braces: the daemon context is already privileged,
			// but the service is @Authorized and should not depend on who happens to call it.
			Context.addProxyPrivilege(ChuSchedulesConstants.PRIVILEGE_MANAGE);
			try {
				run(Context.getService(ChuSchedulesService.class));
			}
			finally {
				Context.removeProxyPrivilege(ChuSchedulesConstants.PRIVILEGE_MANAGE);
			}
		}
		catch (Exception e) {
			// Never let a scheduled task die silently, and never let one template's bad data
			// stop the scheduler.
			log.error("chuschedules rolling generation failed", e);
		}
		finally {
			stopExecuting();
		}
	}
	
	private void run(ChuSchedulesService service) {
		int horizon = horizonDays();
		LocalDate from = LocalDate.now().plusDays(1);
		LocalDate to = LocalDate.now().plusDays(horizon);
		
		int templates = 0;
		int created = 0;
		
		for (ScheduleTemplate template : service.getAllTemplates(false)) {
			if (!Boolean.TRUE.equals(template.getActive())) {
				continue;
			}
			try {
				GenerationReport report = service.generate(template, toDate(from), toDate(to), false);
				templates++;
				created += report.getCreatedCount();
				log.info("chuschedules rolling generation, template " + template.getId() + " (" + template.getName() + "): "
				        + report);
			}
			catch (Exception e) {
				// One unusable template must not stop the others from being topped up.
				log.error("chuschedules rolling generation skipped template " + template.getId() + " (" + template.getName()
				        + ")", e);
			}
		}
		log.info("chuschedules rolling generation finished: " + created + " block(s) created across " + templates
		        + " active template(s), horizon " + horizon + " days");
	}
	
	/**
	 * Clamped to what a single generation run accepts, so a mistyped global property degrades to
	 * the maximum rather than throwing on every template, every night.
	 */
	private int horizonDays() {
		String value = Context.getAdministrationService().getGlobalProperty(ChuSchedulesConstants.GP_ROLLING_HORIZON_DAYS);
		int days = ChuSchedulesConstants.DEFAULT_ROLLING_HORIZON_DAYS;
		if (value != null && !value.trim().isEmpty()) {
			try {
				days = Integer.parseInt(value.trim());
			}
			catch (NumberFormatException e) {
				log.warn("chuschedules: " + ChuSchedulesConstants.GP_ROLLING_HORIZON_DAYS + " is not a number ('" + value
				        + "'); using " + days);
			}
		}
		if (days < 1) {
			days = 1;
		}
		if (days > ChuSchedulesServiceImpl.MAX_HORIZON_DAYS - 1) {
			days = ChuSchedulesServiceImpl.MAX_HORIZON_DAYS - 1;
		}
		return days;
	}
	
	private static Date toDate(LocalDate date) {
		return java.sql.Date.valueOf(date);
	}
}
