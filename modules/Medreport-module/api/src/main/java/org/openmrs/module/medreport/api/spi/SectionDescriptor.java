package org.openmrs.module.medreport.api.spi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A <em>set</em> of clinical information - "Démographiques", "Antécédents", "Diagnostic
 * neurochirurgical" - and the parent checkbox in the personalisation tree. Ticking the set
 * includes all of it; unfolding it exposes each {@link FieldDescriptor} to be ticked
 * individually.
 */
public class SectionDescriptor {

    private String id;

    private Map<String, String> label = new HashMap<String, String>();

    private Map<String, String> description = new HashMap<String, String>();

    /**
     * Privilege required to see, select, or include this set. A user without it never sees
     * the checkbox and cannot obtain the data even by posting the id directly - the server
     * re-filters on generation. This is what makes "if a user cannot view a datum they cannot
     * put it in a report" true rather than a UI convention.
     */
    private String requiredPrivilege;

    /**
     * True when the source returns a list: each element becomes its own titled record in the
     * document (a lab draw, a follow-up visit) instead of one flat block.
     */
    private boolean repeating;

    /** Map key whose value titles each record of a repeating section (usually a date). */
    private String recordTitleKey;

    /** How many of the most recent records to include; 0 means all. */
    private int recordLimit;

    private DataSource source;

    private List<FieldDescriptor> fields = new ArrayList<FieldDescriptor>();

    private List<SectionDescriptor> subsections = new ArrayList<SectionDescriptor>();

    private int sortWeight;

    private boolean defaultSelected = true;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Map<String, String> getLabel() {
        return label;
    }

    public void setLabel(Map<String, String> label) {
        this.label = label != null ? label : new HashMap<String, String>();
    }

    public String getLabel(String language) {
        return Localised.pick(label, language, id);
    }

    public Map<String, String> getDescription() {
        return description;
    }

    public void setDescription(Map<String, String> description) {
        this.description = description != null ? description : new HashMap<String, String>();
    }

    public String getDescription(String language) {
        return description.isEmpty() ? null : Localised.pick(description, language, null);
    }

    public String getRequiredPrivilege() {
        return requiredPrivilege;
    }

    public void setRequiredPrivilege(String requiredPrivilege) {
        this.requiredPrivilege = requiredPrivilege;
    }

    public boolean isRepeating() {
        return repeating;
    }

    public void setRepeating(boolean repeating) {
        this.repeating = repeating;
    }

    public String getRecordTitleKey() {
        return recordTitleKey;
    }

    public void setRecordTitleKey(String recordTitleKey) {
        this.recordTitleKey = recordTitleKey;
    }

    public int getRecordLimit() {
        return recordLimit;
    }

    public void setRecordLimit(int recordLimit) {
        this.recordLimit = recordLimit;
    }

    public DataSource getSource() {
        return source;
    }

    public void setSource(DataSource source) {
        this.source = source;
    }

    public List<FieldDescriptor> getFields() {
        return fields;
    }

    public void setFields(List<FieldDescriptor> fields) {
        this.fields = fields != null ? fields : new ArrayList<FieldDescriptor>();
    }

    public List<SectionDescriptor> getSubsections() {
        return subsections;
    }

    public void setSubsections(List<SectionDescriptor> subsections) {
        this.subsections = subsections != null ? subsections : new ArrayList<SectionDescriptor>();
    }

    public int getSortWeight() {
        return sortWeight;
    }

    public void setSortWeight(int sortWeight) {
        this.sortWeight = sortWeight;
    }

    public boolean isDefaultSelected() {
        return defaultSelected;
    }

    public void setDefaultSelected(boolean defaultSelected) {
        this.defaultSelected = defaultSelected;
    }
}
