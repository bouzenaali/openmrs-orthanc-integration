package org.openmrs.module.medreport.api.report;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What the personalisation window asks for: language, format, template, which data, and the
 * presentation switches. This is also exactly what gets persisted as a user preference, so
 * reopening the window restores the previous choices.
 *
 * <h3>Selection semantics</h3>
 * {@link #sections} lists the ticked set ids. {@link #fields} optionally narrows a set to
 * specific fields - so a set that appears in {@code sections} with no entry in {@code fields}
 * means "all of it", which is what ticking the parent checkbox without unfolding it does.
 *
 * <p>The selection is a <em>request</em>, never an authorisation. Everything in it is
 * re-checked against the caller's live privileges during assembly, so a preference saved
 * before a role change, or an id typed straight into the REST call, cannot widen access.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReportRequest {

    private String language;

    private String format = "pdf";

    private String template;

    private List<String> sections = new ArrayList<String>();

    private Map<String, List<String>> fields = new HashMap<String, List<String>>();

    private boolean includeImageObservations = true;

    private boolean showEmptyFields;

    private boolean includeTableOfContents;

    private boolean includeSignatureBlock = true;

    private boolean includePageNumbers = true;

    private boolean confidentialityNotice = true;

    private String title;

    private String subtitle;

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public String getTemplate() {
        return template;
    }

    public void setTemplate(String template) {
        this.template = template;
    }

    public List<String> getSections() {
        return sections;
    }

    public void setSections(List<String> sections) {
        this.sections = sections != null ? sections : new ArrayList<String>();
    }

    public Map<String, List<String>> getFields() {
        return fields;
    }

    public void setFields(Map<String, List<String>> fields) {
        this.fields = fields != null ? fields : new HashMap<String, List<String>>();
    }

    public boolean isIncludeImageObservations() {
        return includeImageObservations;
    }

    public void setIncludeImageObservations(boolean includeImageObservations) {
        this.includeImageObservations = includeImageObservations;
    }

    public boolean isShowEmptyFields() {
        return showEmptyFields;
    }

    public void setShowEmptyFields(boolean showEmptyFields) {
        this.showEmptyFields = showEmptyFields;
    }

    public boolean isIncludeTableOfContents() {
        return includeTableOfContents;
    }

    public void setIncludeTableOfContents(boolean includeTableOfContents) {
        this.includeTableOfContents = includeTableOfContents;
    }

    public boolean isIncludeSignatureBlock() {
        return includeSignatureBlock;
    }

    public void setIncludeSignatureBlock(boolean includeSignatureBlock) {
        this.includeSignatureBlock = includeSignatureBlock;
    }

    public boolean isIncludePageNumbers() {
        return includePageNumbers;
    }

    public void setIncludePageNumbers(boolean includePageNumbers) {
        this.includePageNumbers = includePageNumbers;
    }

    public boolean isConfidentialityNotice() {
        return confidentialityNotice;
    }

    public void setConfidentialityNotice(boolean confidentialityNotice) {
        this.confidentialityNotice = confidentialityNotice;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSubtitle() {
        return subtitle;
    }

    public void setSubtitle(String subtitle) {
        this.subtitle = subtitle;
    }

    /** True when the user ticked this set (or its parent, handled by the caller). */
    public boolean isSectionSelected(String sectionId) {
        return sections.contains(sectionId);
    }

    /**
     * Whether a specific field was ticked. A ticked set with no field list means the whole
     * set, which is what the parent checkbox does when it is not unfolded.
     */
    public boolean isFieldSelected(String sectionId, String fieldId) {
        List<String> selected = fields.get(sectionId);
        return selected == null || selected.isEmpty() || selected.contains(fieldId);
    }
}
