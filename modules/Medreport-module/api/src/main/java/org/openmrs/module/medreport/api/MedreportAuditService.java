package org.openmrs.module.medreport.api;

import org.openmrs.api.OpenmrsService;
import org.openmrs.module.medreport.api.model.ImageReport;
import org.openmrs.module.medreport.api.model.ImageReportVersion;
import org.openmrs.module.medreport.api.model.MedreportOperationLog;

import java.util.List;

/**
 * Append-only audit trail for every action on a report (RP7).
 *
 * <p>Log writes run in their own transaction ({@code REQUIRES_NEW}, wired in
 * {@code moduleApplicationContext.xml}). If the business transaction rolls back, the record
 * that the attempt happened must survive - especially for a refused or failed attempt, which
 * is exactly the case a shared transaction would erase.
 */
public interface MedreportAuditService extends OpenmrsService {

    /** Record a successful action. */
    MedreportOperationLog log(String action, ImageReport report, ImageReportVersion version,
                              String details);

    /**
     * Record a refused or failed attempt - a missing privilege, an edit of someone else's
     * report. Logged as {@code success = false} so the trail shows what was tried, not only
     * what succeeded.
     */
    MedreportOperationLog logFailure(String action, ImageReport report, String details);

    /** Record an action that is not tied to a stored report, e.g. generating a patient report. */
    MedreportOperationLog logForPatient(String action, Integer patientId, boolean success,
                                        String details);

    List<MedreportOperationLog> getLogsForReport(Integer reportId);

    List<MedreportOperationLog> getRecentLogs(int limit);
}
