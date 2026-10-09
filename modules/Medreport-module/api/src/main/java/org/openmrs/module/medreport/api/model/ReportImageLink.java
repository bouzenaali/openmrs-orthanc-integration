package org.openmrs.module.medreport.api.model;

/**
 * The report &harr; image association row (RP9, architecture §4.3).
 *
 * <p>SF3's original wording ("associated with that patient and that study") reads as one
 * report per study. RP9 requires a genuine many-to-many instead, and this table is it: one
 * report can reference several studies or series - a synthesis over a whole imaging series,
 * or over several follow-up scans - and one study can be referenced by any number of reports,
 * such as an initial read and a later follow-up both citing the same prior scan. Neither side
 * holds a foreign key to the other; the association lives here.
 *
 * <p>Each row carries <em>both</em> {@code report} and {@code version}. {@code report} gives
 * the plain many-to-many the architecture asks for ("which reports cover this study"), while
 * {@code version} keeps each archived version's image set immutable (RP8) - editing a report
 * to add or drop an image must not silently rewrite what an earlier version covered. Queries
 * for the live association therefore filter on the current version; queries for coverage
 * history do not.
 *
 * <p>The image is identified by its Orthanc/DICOM UIDs rather than by a foreign key into the
 * imaging module's tables. That is deliberate: Orthanc is the source of truth for images, and
 * a UID reference means this module neither depends on the imaging module's schema nor breaks
 * if a study is re-synced and gets a new local row.
 */
public class ReportImageLink {

    private Integer id;

    private ImageReport report;

    private ImageReportVersion version;

    /** Orthanc's own study identifier - what the viewer URL is built from. */
    private String orthancStudyUid;

    /** Optional: set when the observation targets one series rather than the whole study. */
    private String orthancSeriesUid;

    /** DICOM (0020,000D) StudyInstanceUID - the globally stable identifier. */
    private String studyInstanceUid;

    private String modality;

    private String studyDate;

    private String studyDescription;

    private Integer sortWeight;

    public ReportImageLink() {
    }

    public ReportImageLink(String orthancStudyUid) {
        this.orthancStudyUid = orthancStudyUid;
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public ImageReport getReport() {
        return report;
    }

    public void setReport(ImageReport report) {
        this.report = report;
    }

    public ImageReportVersion getVersion() {
        return version;
    }

    public void setVersion(ImageReportVersion version) {
        this.version = version;
    }

    public String getOrthancStudyUid() {
        return orthancStudyUid;
    }

    public void setOrthancStudyUid(String orthancStudyUid) {
        this.orthancStudyUid = orthancStudyUid;
    }

    public String getOrthancSeriesUid() {
        return orthancSeriesUid;
    }

    public void setOrthancSeriesUid(String orthancSeriesUid) {
        this.orthancSeriesUid = orthancSeriesUid;
    }

    public String getStudyInstanceUid() {
        return studyInstanceUid;
    }

    public void setStudyInstanceUid(String studyInstanceUid) {
        this.studyInstanceUid = studyInstanceUid;
    }

    public String getModality() {
        return modality;
    }

    public void setModality(String modality) {
        this.modality = modality;
    }

    public String getStudyDate() {
        return studyDate;
    }

    public void setStudyDate(String studyDate) {
        this.studyDate = studyDate;
    }

    public String getStudyDescription() {
        return studyDescription;
    }

    public void setStudyDescription(String studyDescription) {
        this.studyDescription = studyDescription;
    }

    public Integer getSortWeight() {
        return sortWeight;
    }

    public void setSortWeight(Integer sortWeight) {
        this.sortWeight = sortWeight;
    }

    /** Human-readable label used in report headings and in the imaging tab's list. */
    public String getDisplayLabel() {
        StringBuilder label = new StringBuilder();
        if (modality != null && !modality.trim().isEmpty()) {
            label.append(modality.trim());
        }
        if (studyDescription != null && !studyDescription.trim().isEmpty()) {
            if (label.length() > 0) {
                label.append(" — ");
            }
            label.append(studyDescription.trim());
        }
        if (studyDate != null && !studyDate.trim().isEmpty()) {
            if (label.length() > 0) {
                label.append(" (").append(studyDate.trim()).append(")");
            } else {
                label.append(studyDate.trim());
            }
        }
        if (label.length() == 0) {
            label.append(orthancSeriesUid != null && !orthancSeriesUid.isEmpty()
                    ? orthancSeriesUid : String.valueOf(orthancStudyUid));
        }
        return label.toString();
    }
}
