package org.openmrs.module.medreport.api.model;

import org.openmrs.Obs;
import org.openmrs.User;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * One immutable, timestamped state of an {@link ImageReport} (RP8).
 *
 * <p>Every transition - create, update, remove, admin restore - inserts a new row here and
 * moves the {@code isCurrent} flag; no row is ever mutated after insert apart from that flag,
 * and none is ever deleted. Chaining each version to its {@link #previousVersion} gives the
 * immutable audit chain RP3 requires.
 *
 * <p>Non-admin readers only ever see the row where {@code isCurrent} is true on a non-voided
 * report; traversing the chain is admin-only (RP4).
 */
public class ImageReportVersion {

    private Integer id;

    private String uuid;

    private ImageReport report;

    private Integer versionNumber;

    private String title;

    /** The clinician's free text, stored verbatim and rendered into the document as-is. */
    private String observationText;

    /** Complex Obs holding the rendered .docx for this version (ADR-6). */
    private Obs documentObs;

    private String documentFilename;

    /** CREATE / UPDATE / REMOVE / RESTORE - see MedreportConstants. */
    private String changeType;

    private String changeReason;

    private User createdBy;

    private Date dateCreated;

    private ImageReportVersion previousVersion;

    private boolean current;

    /** The images this particular version covers (RP9). */
    private List<ReportImageLink> images = new ArrayList<ReportImageLink>();

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

    public ImageReport getReport() {
        return report;
    }

    public void setReport(ImageReport report) {
        this.report = report;
    }

    public Integer getVersionNumber() {
        return versionNumber;
    }

    public void setVersionNumber(Integer versionNumber) {
        this.versionNumber = versionNumber;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getObservationText() {
        return observationText;
    }

    public void setObservationText(String observationText) {
        this.observationText = observationText;
    }

    public Obs getDocumentObs() {
        return documentObs;
    }

    public void setDocumentObs(Obs documentObs) {
        this.documentObs = documentObs;
    }

    public String getDocumentFilename() {
        return documentFilename;
    }

    public void setDocumentFilename(String documentFilename) {
        this.documentFilename = documentFilename;
    }

    public String getChangeType() {
        return changeType;
    }

    public void setChangeType(String changeType) {
        this.changeType = changeType;
    }

    public String getChangeReason() {
        return changeReason;
    }

    public void setChangeReason(String changeReason) {
        this.changeReason = changeReason;
    }

    public User getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(User createdBy) {
        this.createdBy = createdBy;
    }

    public Date getDateCreated() {
        return dateCreated;
    }

    public void setDateCreated(Date dateCreated) {
        this.dateCreated = dateCreated;
    }

    public ImageReportVersion getPreviousVersion() {
        return previousVersion;
    }

    public void setPreviousVersion(ImageReportVersion previousVersion) {
        this.previousVersion = previousVersion;
    }

    public boolean isCurrent() {
        return current;
    }

    public void setCurrent(boolean current) {
        this.current = current;
    }

    public List<ReportImageLink> getImages() {
        return images;
    }

    public void setImages(List<ReportImageLink> images) {
        this.images = images;
    }

    public void addImage(ReportImageLink link) {
        link.setVersion(this);
        link.setReport(this.report);
        this.images.add(link);
    }
}
