package org.openmrs.module.chuschedules.api.impl;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.Provider;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.api.impl.BaseOpenmrsService;
import org.openmrs.module.appointmentscheduling.AppointmentBlock;
import org.openmrs.module.appointmentscheduling.AppointmentType;
import org.openmrs.module.appointmentscheduling.TimeSlot;
import org.openmrs.module.appointmentscheduling.Appointment;
import org.openmrs.module.appointmentscheduling.api.AppointmentService;
import org.openmrs.module.chuschedules.GeneratedBlock;
import org.openmrs.module.chuschedules.ScheduleException;
import org.openmrs.module.chuschedules.ScheduleTemplate;
import org.openmrs.module.chuschedules.ScheduleTemplateRange;
import org.openmrs.module.chuschedules.api.ChuSchedulesService;
import org.openmrs.module.chuschedules.api.db.ChuSchedulesDAO;
import org.openmrs.module.chuschedules.generator.GenerationReport;
import org.openmrs.module.chuschedules.generator.OccurrencePlanner;
import org.openmrs.module.chuschedules.generator.PlannedOccurrence;
import org.openmrs.module.chuschedules.generator.RefreshDecision;
import org.openmrs.module.chuschedules.generator.RefreshPlanner;
import org.openmrs.module.chuschedules.generator.RefreshReport;
import org.openmrs.module.chuschedules.generator.RangeDate;
import org.openmrs.module.chuschedules.generator.SkipReason;

public class ChuSchedulesServiceImpl extends BaseOpenmrsService implements ChuSchedulesService {
	
	private static final Log log = LogFactory.getLog(ChuSchedulesServiceImpl.class);
	
	/**
	 * The furthest ahead a single run will generate. Neurosurgery books up to a year out, and an
	 * unbounded horizon is how a mistyped date turns into a decade of blocks.
	 */
	public static final int MAX_HORIZON_DAYS = 366;
	
	private ChuSchedulesDAO dao;
	
	public void setDao(ChuSchedulesDAO dao) {
		this.dao = dao;
	}
	
	// --- templates ---
	
	@Override
	public ScheduleTemplate saveTemplate(ScheduleTemplate template) {
		validate(template);
		ScheduleTemplate saved = dao.saveTemplate(template);
		// Flush now, not at request end. A database constraint violation raised during the
		// end-of-request flush reaches no one: the page has already rendered and the user
		// sees a normal screen with their edit silently discarded.
		Context.flushSession();
		return saved;
	}
	
	@Override
	public ScheduleTemplate getTemplate(Integer templateId) {
		return dao.getTemplate(templateId);
	}
	
	@Override
	public ScheduleTemplate getTemplateByUuid(String uuid) {
		return dao.getTemplateByUuid(uuid);
	}
	
	@Override
	public List<ScheduleTemplate> getAllTemplates(boolean includeVoided) {
		return dao.getAllTemplates(includeVoided);
	}
	
	@Override
	public ScheduleTemplate voidTemplate(ScheduleTemplate template, String reason) {
		// Voiding a template stops future generation. It deliberately does not remove
		// blocks that were already generated -- those may already have patients booked
		// into them, and deleting a booked clinic is the worst outcome this module could
		// produce. Blocks are removed one at a time, by a human, through the existing UI.
		template.setVoided(true);
		template.setVoidReason(reason);
		template.setDateVoided(new Date());
		template.setVoidedBy(Context.getAuthenticatedUser());
		return dao.saveTemplate(template);
	}
	
	private void validate(ScheduleTemplate template) {
		if (template.getProvider() == null) {
			throw new APIException("A recurring schedule needs a provider");
		}
		if (template.getLocation() == null) {
			throw new APIException("A recurring schedule needs a location");
		}
		if (template.getValidFrom() == null) {
			throw new APIException("A recurring schedule needs a start date");
		}
		if (template.getValidTo() != null && template.getValidTo().before(template.getValidFrom())) {
			throw new APIException("The schedule ends before it starts");
		}
		if (activeRanges(template).isEmpty()) {
			throw new APIException("A recurring schedule needs at least one weekly or monthly session");
		}
		for (ScheduleTemplateRange range : activeRanges(template)) {
			if (range.getDayOfWeek() == null || range.getDayOfWeek() < 1 || range.getDayOfWeek() > 7) {
				throw new APIException("Each session needs a day of the week");
			}
			if (range.getStartTime() == null || range.getEndTime() == null) {
				throw new APIException("Each session needs a start and end time");
			}
			if (!range.getEndTime().after(range.getStartTime())) {
				throw new APIException("A session ends at or before it starts");
			}
		}
	}
	
	/** Voided ranges are history, not schedule: they must not be validated or generated from. */
	public static List<ScheduleTemplateRange> activeRanges(ScheduleTemplate template) {
		List<ScheduleTemplateRange> live = new java.util.ArrayList<ScheduleTemplateRange>();
		if (template.getRanges() != null) {
			for (ScheduleTemplateRange r : template.getRanges()) {
				if (!Boolean.TRUE.equals(r.getVoided())) {
					live.add(r);
				}
			}
		}
		return live;
	}
	
	// --- exceptions ---
	
	@Override
	public ScheduleException saveException(ScheduleException exception) {
		if (exception.getExceptionDate() == null) {
			throw new APIException("An exception needs a date");
		}
		if (exception.getReason() == null || exception.getReason().trim().isEmpty()) {
			throw new APIException("An exception needs a reason, so the report can explain the gap");
		}
		return dao.saveException(exception);
	}
	
	@Override
	public List<ScheduleException> getExceptions(Date from, Date to, Provider provider) {
		return dao.getExceptions(from, to, provider);
	}
	
	@Override
	public List<ScheduleException> getAllExceptions(boolean includeVoided) {
		return dao.getAllExceptions(includeVoided);
	}
	
	@Override
	public ScheduleException getException(Integer exceptionId) {
		return dao.getException(exceptionId);
	}
	
	@Override
	public ScheduleException voidException(ScheduleException exception, String reason) {
		exception.setVoided(true);
		exception.setVoidReason(reason);
		exception.setDateVoided(new Date());
		exception.setVoidedBy(Context.getAuthenticatedUser());
		return dao.saveException(exception);
	}
	
	@Override
	public List<GeneratedBlock> getGeneratedBlocks(ScheduleTemplate template, Date from, Date to) {
		return dao.getGeneratedBlocks(template, from, to);
	}
	
	// --- re-synchronising after a pattern change ---
	
	@Override
	public RefreshReport refreshFutureBlocks(ScheduleTemplate template, String reason) {
		if (template == null) {
			throw new APIException("Nothing to refresh");
		}
		RefreshReport report = new RefreshReport();
		AppointmentService appointmentService = Context.getService(AppointmentService.class);
		
		// Strictly future. Today's clinic may already be running and the past is a record.
		Date tomorrow = java.sql.Date.valueOf(LocalDate.now().plusDays(1));
		
		LocalDate today = LocalDate.now();
		
		for (GeneratedBlock record : dao.getGeneratedBlocks(template, tomorrow, null)) {
			AppointmentBlock block = appointmentService.getAppointmentBlockByUuid(record.getBlockUuid());
			LocalDate targetDate = toLocalDate(record.getTargetDate());
			
			// Gather the facts, then let the pure rule decide. The decision table is unit
			// tested exhaustively in RefreshPlannerTest; this loop only carries it out.
			RefreshDecision decision = RefreshPlanner.decide(targetDate, today, block != null,
			    block != null && Boolean.TRUE.equals(block.getVoided()),
			    block != null && hasLiveAppointments(appointmentService, block));
			
			switch (decision) {
				case LEAVE_PAST:
					break;
				case ALREADY_GONE:
					voidRecord(record, reason);
					report.recordAlreadyGone();
					break;
				case KEEP_BOOKED:
					report.recordKeptBooked(targetDate);
					break;
				case VOID:
					for (TimeSlot slot : appointmentService.getTimeSlotsInAppointmentBlock(block)) {
						appointmentService.voidTimeSlot(slot, reason);
					}
					appointmentService.voidAppointmentBlock(block, reason);
					voidRecord(record, reason);
					report.recordVoided(targetDate);
					break;
			}
		}
		
		log.info("chuschedules " + report + " for template " + template.getId());
		return report;
	}
	
	private boolean hasLiveAppointments(AppointmentService appointmentService, AppointmentBlock block) {
		for (TimeSlot slot : appointmentService.getTimeSlotsInAppointmentBlock(block)) {
			List<Appointment> appointments = appointmentService.getAppointmentsInTimeSlotThatAreNotCancelled(slot);
			if (appointments != null && !appointments.isEmpty()) {
				return true;
			}
		}
		return false;
	}
	
	private void voidRecord(GeneratedBlock record, String reason) {
		record.setVoided(true);
		record.setVoidReason(reason);
		record.setDateVoided(new Date());
		record.setVoidedBy(Context.getAuthenticatedUser());
		dao.saveGeneratedBlock(record);
	}
	
	// --- generation ---
	
	@Override
	public GenerationReport generate(ScheduleTemplate template, Date from, Date to, boolean dryRun) {
		if (template == null) {
			throw new APIException("Nothing to generate from");
		}
		LocalDate start = toLocalDate(from);
		LocalDate end = toLocalDate(to);
		LocalDate today = LocalDate.now();
		
		if (start == null || end == null) {
			throw new APIException("Generation needs both a start and an end date");
		}
		if (end.isBefore(start)) {
			throw new APIException("The generation range ends before it starts");
		}
		if (start.plusDays(MAX_HORIZON_DAYS).isBefore(end)) {
			throw new APIException("Generation is limited to " + MAX_HORIZON_DAYS + " days at a time; asked for " + start
			        + " to " + end);
		}
		
		Map<LocalDate, String> exclusions = exclusionsFor(template, from, to);
		Set<RangeDate> alreadyGenerated = alreadyGeneratedFor(template, from, to);
		
		List<PlannedOccurrence> plan = OccurrencePlanner.plan(template, start, end, today, exclusions, alreadyGenerated);
		
		GenerationReport report = new GenerationReport(dryRun);
		AppointmentService appointmentService = Context.getService(AppointmentService.class);
		
		for (PlannedOccurrence occurrence : plan) {
			if (!occurrence.isIncluded()) {
				report.add(occurrence);
				continue;
			}
			
			AppointmentBlock candidate = buildBlock(template, occurrence);
			
			List<AppointmentBlock> overlapping = appointmentService.getOverlappingAppointmentBlocks(candidate);
			if (overlapping != null && !overlapping.isEmpty()) {
				// Whatever is already in the calendar stays authoritative. We never shadow
				// or duplicate a block a clerk entered by hand.
				report.add(PlannedOccurrence.skipped(occurrence.getDate(), occurrence.getRange(),
				    SkipReason.OVERLAPS_EXISTING, describe(overlapping)));
				continue;
			}
			
			if (!dryRun) {
				AppointmentBlock saved = appointmentService.saveAppointmentBlock(candidate);
				
				// One time slot spanning the whole block, which is exactly what the
				// appointment UI's own resource creates. Generated availability is then
				// indistinguishable from hand-made availability everywhere downstream.
				appointmentService.saveTimeSlot(new TimeSlot(saved, saved.getStartDate(), saved.getEndDate()));
				
				GeneratedBlock record = new GeneratedBlock();
				record.setTemplate(template);
				record.setRange(occurrence.getRange());
				record.setTargetDate(java.sql.Date.valueOf(occurrence.getDate()));
				record.setBlockUuid(saved.getUuid());
				dao.saveGeneratedBlock(record);
				
				report.recordCreatedBlock(saved.getUuid());
			}
			report.add(occurrence);
		}
		
		log.info("chuschedules generation for template " + template.getId() + " (" + start + " to " + end + "): " + report);
		return report;
	}
	
	private AppointmentBlock buildBlock(ScheduleTemplate template, PlannedOccurrence occurrence) {
		ScheduleTemplateRange range = occurrence.getRange();
		AppointmentBlock block = new AppointmentBlock();
		block.setProvider(template.getProvider());
		block.setLocation(template.getLocation());
		block.setTypes(new LinkedHashSet<AppointmentType>(template.getTypes()));
		block.setStartDate(at(occurrence.getDate(), range.getStartTime()));
		block.setEndDate(at(occurrence.getDate(), range.getEndTime()));
		return block;
	}
	
	private Map<LocalDate, String> exclusionsFor(ScheduleTemplate template, Date from, Date to) {
		Map<LocalDate, String> exclusions = new HashMap<LocalDate, String>();
		for (ScheduleException e : dao.getExceptions(from, to, template.getProvider())) {
			LocalDate date = toLocalDate(e.getExceptionDate());
			// A provider-specific reason is more informative than "public holiday", so let
			// it win when a date carries both.
			if (!exclusions.containsKey(date) || !e.isGlobal()) {
				exclusions.put(date, e.getReason());
			}
		}
		return exclusions;
	}
	
	private Set<RangeDate> alreadyGeneratedFor(ScheduleTemplate template, Date from, Date to) {
		Set<RangeDate> keys = new HashSet<RangeDate>();
		for (GeneratedBlock g : dao.getGeneratedBlocks(template, from, to)) {
			keys.add(new RangeDate(g.getRange().getRangeId(), toLocalDate(g.getTargetDate())));
		}
		return keys;
	}
	
	private String describe(List<AppointmentBlock> blocks) {
		StringBuilder sb = new StringBuilder();
		for (AppointmentBlock b : blocks) {
			if (sb.length() > 0) {
				sb.append(", ");
			}
			sb.append(b.getUuid());
		}
		return sb.toString();
	}
	
	/**
	 * Combines a date with a wall-clock time. Safe because the server is pinned to Africa/Algiers,
	 * which is UTC+1 all year with no daylight saving, so a local time maps to exactly one instant.
	 * If this system is ever deployed somewhere that observes DST, this method is the one to
	 * revisit: the whole class of "the clinic gained or lost an hour" bugs enters here.
	 */
	private static Date at(LocalDate date, java.sql.Time time) {
		LocalTime localTime = time.toLocalTime();
		return Date.from(date.atTime(localTime).atZone(ZoneId.systemDefault()).toInstant());
	}
	
	private static LocalDate toLocalDate(Date date) {
		if (date == null) {
			return null;
		}
		if (date instanceof java.sql.Date) {
			return ((java.sql.Date) date).toLocalDate();
		}
		return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
	}
}
