package org.openmrs.module.medreport.api.render;

import java.util.ArrayList;
import java.util.List;

/** What the Report Generation Service returns for one render. */
public class RenderedDocument {

    private String artifactId;

    private String filename;

    private String format;

    private long sizeBytes;

    private String previewFormat;

    private boolean previewAvailable;

    private boolean pdfAvailable;

    private String expiresAt;

    /**
     * Non-fatal notes from the renderer, e.g. "LibreOffice unavailable, PDF downgraded to
     * DOCX". Surfaced to the clinician rather than swallowed: silently handing someone a
     * different file format than they asked for is worse than telling them why.
     */
    private List<String> warnings = new ArrayList<String>();

    public String getArtifactId() {
        return artifactId;
    }

    public void setArtifactId(String artifactId) {
        this.artifactId = artifactId;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public String getPreviewFormat() {
        return previewFormat;
    }

    public void setPreviewFormat(String previewFormat) {
        this.previewFormat = previewFormat;
    }

    public boolean isPreviewAvailable() {
        return previewAvailable;
    }

    public void setPreviewAvailable(boolean previewAvailable) {
        this.previewAvailable = previewAvailable;
    }

    public boolean isPdfAvailable() {
        return pdfAvailable;
    }

    public void setPdfAvailable(boolean pdfAvailable) {
        this.pdfAvailable = pdfAvailable;
    }

    public String getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(String expiresAt) {
        this.expiresAt = expiresAt;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public void setWarnings(List<String> warnings) {
        this.warnings = warnings != null ? warnings : new ArrayList<String>();
    }
}
