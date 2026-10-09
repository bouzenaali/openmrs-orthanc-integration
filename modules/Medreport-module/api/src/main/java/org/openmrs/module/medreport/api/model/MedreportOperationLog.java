package org.openmrs.module.medreport.api.model;

import java.util.Date;

/**
 * One append-only audit entry: who did what, to which report, when (RP7).
 *
 * <p>Mirrors the {@code agent_operation_log} pattern used on the Agent side, scoped to
 * medreport. Rows are inserted and never updated or deleted - there is deliberately no setter
 * path in the service that revises an existing entry.
 *
 * <p>The table intentionally carries {@code reportId} as a plain integer rather than a
 * foreign key, and denormalises {@code username}: an audit trail has to stay readable and
 * self-contained even if the referenced row or user account later becomes unreachable.
 *
 * <p>Refused attempts are logged too ({@code DENIED}), not just successful ones - a record of
 * who tried to read someone else's report is exactly what an audit log is for.
 */
public class MedreportOperationLog {

    private Integer id;

    private String uuid;

    /** CREATE / UPDATE / REMOVE / RESTORE / VIEW_HISTORY / GENERATE / DENIED. */
    private String action;

    private Integer reportId;

    private String reportUuid;

    private Integer reportVersionId;

    private Integer versionNumber;

    private Integer patientId;

    private Integer userId;

    private String username;

    private String ipAddress;

    private Date dateCreated;

    private boolean success = true;

    /** Free-form context: which images, which privilege was missing, which template. */
    private String details;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public Integer getReportId() {
        return reportId;
    }

    public void setReportId(Integer reportId) {
        this.reportId = reportId;
    }

    public String getReportUuid() {
        return reportUuid;
    }

    public void setReportUuid(String reportUuid) {
        this.reportUuid = reportUuid;
    }

    public Integer getReportVersionId() {
        return reportVersionId;
    }

    public void setReportVersionId(Integer reportVersionId) {
        this.reportVersionId = reportVersionId;
    }

    public Integer getVersionNumber() {
        return versionNumber;
    }

    public void setVersionNumber(Integer versionNumber) {
        this.versionNumber = versionNumber;
    }

    public Integer getPatientId() {
        return patientId;
    }

    public void setPatientId(Integer patientId) {
        this.patientId = patientId;
    }

    public Integer getUserId() {
        return userId;
    }

    public void setUserId(Integer userId) {
        this.userId = userId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public Date getDateCreated() {
        return dateCreated;
    }

    public void setDateCreated(Date dateCreated) {
        this.dateCreated = dateCreated;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }
}
