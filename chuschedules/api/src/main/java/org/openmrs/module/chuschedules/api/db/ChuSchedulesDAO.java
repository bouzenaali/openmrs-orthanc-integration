package org.openmrs.module.chuschedules.api.db;

import java.util.Date;
import java.util.List;

import org.openmrs.Provider;
import org.openmrs.module.chuschedules.GeneratedBlock;
import org.openmrs.module.chuschedules.ScheduleException;
import org.openmrs.module.chuschedules.ScheduleTemplate;

public interface ChuSchedulesDAO {
	
	ScheduleTemplate saveTemplate(ScheduleTemplate template);
	
	ScheduleTemplate getTemplate(Integer templateId);
	
	ScheduleTemplate getTemplateByUuid(String uuid);
	
	List<ScheduleTemplate> getAllTemplates(boolean includeVoided);
	
	ScheduleException saveException(ScheduleException exception);
	
	ScheduleException getException(Integer exceptionId);
	
	ScheduleException getExceptionByUuid(String uuid);
	
	/** Global exclusions plus, when a provider is given, that provider's own. */
	List<ScheduleException> getExceptions(Date from, Date to, Provider provider);
	
	/** Every exclusion regardless of provider -- for the management screen, not generation. */
	List<ScheduleException> getAllExceptions(boolean includeVoided);
	
	GeneratedBlock saveGeneratedBlock(GeneratedBlock generatedBlock);
	
	List<GeneratedBlock> getGeneratedBlocks(ScheduleTemplate template, Date from, Date to);
}
