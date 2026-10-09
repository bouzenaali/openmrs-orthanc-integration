package org.openmrs.module.medreport.api.model;

import org.openmrs.Encounter;
import org.openmrs.Patient;
import org.openmrs.User;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * The stable identity of an imaging observation report.
 *
 * <p>This row never carries report <em>content</em>. Text, the rendered document and the set
 * of images covered all live on {@link ImageReportVersion}, so an update can archive a new
 * version instead of overwriting anything (RP3, RP8). What lives here is what must not change
 * across versions: which patient the report is about, and who authored it - the author being
 * the ownership check for "may update/remove only their own report" (RP3, RP5).
 *
 * <p>Removal is a soft delete: {@code voided} hides the report from everyone else while the
 * row and its whole version chain stay in the database (RP5). Nothing in this module ever
 * issues a physical delete.
 */
public class ImageReport {

    private Integer id;

    private String uuid;

    private Patient patient;

    /** Ties the report to a clinical encounter, per ADR-6. */
    private Encounter encounter;

    /** The original author. Ownership is fixed at creation and never reassigned. */
    private User author;

    private Date dateCreated;

    private ImageReportVersion currentVersion;

    private boolean voided;

    private User voidedBy;

    private Date dateVoided;

    private String voidReason;

    private List<ImageReportVersion> versions = new ArrayList<ImageReportVersion>();

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

    public Patient getPatient() {
        return patient;
    }

    public void setPatient(Patient patient) {
        this.patient = patient;
    }

    public Encounter getEncounter() {
        return encounter;
    }

    public void setEncounter(Encounter encounter) {
        this.encounter = encounter;
    }

    public User getAuthor() {
        return author;
    }

    public void setAuthor(User author) {
        this.author = author;
    }

    public Date getDateCreated() {
        return dateCreated;
    }

    public void setDateCreated(Date dateCreated) {
        this.dateCreated = dateCreated;
    }

    public ImageReportVersion getCurrentVersion() {
        return currentVersion;
    }

    public void setCurrentVersion(ImageReportVersion currentVersion) {
        this.currentVersion = currentVersion;
    }

    public boolean isVoided() {
        return voided;
    }

    public void setVoided(boolean voided) {
        this.voided = voided;
    }

    public User getVoidedBy() {
        return voidedBy;
    }

    public void setVoidedBy(User voidedBy) {
        this.voidedBy = voidedBy;
    }

    public Date getDateVoided() {
        return dateVoided;
    }

    public void setDateVoided(Date dateVoided) {
        this.dateVoided = dateVoided;
    }

    public String getVoidReason() {
        return voidReason;
    }

    public void setVoidReason(String voidReason) {
        this.voidReason = voidReason;
    }

    public List<ImageReportVersion> getVersions() {
        return versions;
    }

    public void setVersions(List<ImageReportVersion> versions) {
        this.versions = versions;
    }

    /**
     * True when {@code user} authored this report. The ownership half of RP3/RP5 - holding
     * the manage privilege is necessary but not sufficient to edit or remove a report.
     */
    public boolean isAuthoredBy(User user) {
        return user != null && author != null && author.getUserId() != null
                && author.getUserId().equals(user.getUserId());
    }
}
