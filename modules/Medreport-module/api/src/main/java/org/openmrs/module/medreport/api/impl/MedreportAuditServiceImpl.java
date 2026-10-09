package org.openmrs.module.medreport.api.impl;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.User;
import org.openmrs.api.context.Context;
import org.openmrs.api.impl.BaseOpenmrsService;
import org.openmrs.module.medreport.MedreportRequestContext;
import org.openmrs.module.medreport.api.MedreportAuditService;
import org.openmrs.module.medreport.api.dao.MedreportDao;
import org.openmrs.module.medreport.api.model.ImageReport;
import org.openmrs.module.medreport.api.model.ImageReportVersion;
import org.openmrs.module.medreport.api.model.MedreportOperationLog;

import java.util.Date;
import java.util.List;
import java.util.UUID;

public class MedreportAuditServiceImpl extends BaseOpenmrsService implements MedreportAuditService {

    private static final Log log = LogFactory.getLog(MedreportAuditServiceImpl.class);

    private static final int MAX_DETAILS = 4000;

    private MedreportDao dao;

    public void setDao(MedreportDao dao) {
        this.dao = dao;
    }

    public MedreportOperationLog log(String action, ImageReport report,
                                     ImageReportVersion version, String details) {
        return write(action, report, version, null, true, details);
    }

    public MedreportOperationLog logFailure(String action, ImageReport report, String details) {
        return write(action, report, null, null, false, details);
    }

    public MedreportOperationLog logForPatient(String action, Integer patientId,
                                               boolean success, String details) {
        return write(action, null, null, patientId, success, details);
    }

    private MedreportOperationLog write(String action, ImageReport report,
                                        ImageReportVersion version, Integer patientId,
                                        boolean success, String details) {
        MedreportOperationLog entry = new MedreportOperationLog();
        entry.setUuid(UUID.randomUUID().toString());
        entry.setAction(action);
        entry.setDateCreated(new Date());
        entry.setSuccess(success);
        entry.setDetails(truncate(details));
        entry.setIpAddress(MedreportRequestContext.getClientIp());

        if (report != null) {
            entry.setReportId(report.getId());
            entry.setReportUuid(report.getUuid());
            if (report.getPatient() != null) {
                entry.setPatientId(report.getPatient().getPatientId());
            }
        }
        if (patientId != null) {
            entry.setPatientId(patientId);
        }
        if (version != null) {
            entry.setReportVersionId(version.getId());
            entry.setVersionNumber(version.getVersionNumber());
        }

        User user = Context.getAuthenticatedUser();
        if (user != null) {
            entry.setUserId(user.getUserId());
            entry.setUsername(user.getUsername() != null ? user.getUsername()
                    : user.getSystemId());
        }

        try {
            return dao.saveLog(entry);
        } catch (Exception e) {
            // An audit write must never be the reason a clinical action fails. Losing the
            // row is bad, so it is escalated to the server log where it will be noticed.
            log.error("medreport: FAILED TO WRITE AUDIT ENTRY action=" + action
                    + " user=" + entry.getUsername() + " report=" + entry.getReportUuid(), e);
            return entry;
        }
    }

    private String truncate(String details) {
        if (details == null) {
            return null;
        }
        return details.length() <= MAX_DETAILS ? details : details.substring(0, MAX_DETAILS);
    }

    public List<MedreportOperationLog> getLogsForReport(Integer reportId) {
        return dao.getLogsForReport(reportId);
    }

    public List<MedreportOperationLog> getRecentLogs(int limit) {
        return dao.getRecentLogs(limit);
    }
}
