package org.openmrs.module.medreport.api.spi;

import java.util.HashMap;
import java.util.Map;

/**
 * One selectable datum inside a {@link SectionDescriptor} - a leaf checkbox in the
 * personalisation tree, and one {@code Label : value} line in the rendered report.
 */
public class FieldDescriptor {

    private String id;

    /**
     * Key under which the contributing service returns this value in its
     * {@code Map<String, Object>}. Defaults to {@link #id} when unset, which is the common
     * case; it exists for contributors whose map keys differ from the ids they want exposed.
     */
    private String sourceKey;

    private Map<String, String> label = new HashMap<String, String>();

    /** text | longtext | number | date | boolean | list - drives rendering, not validation. */
    private String type = "text";

    private String unit;

    private boolean emphasis;

    /**
     * Privilege required to include this specific field, over and above its section's.
     * Optional: most fields inherit their section's privilege. Used for the occasional datum
     * that is more sensitive than the set it lives in.
     */
    private String requiredPrivilege;

    private int sortWeight;

    /** Selected by default the first time a user opens the personalisation window. */
    private boolean defaultSelected = true;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSourceKey() {
        return sourceKey != null && !sourceKey.isEmpty() ? sourceKey : id;
    }

    public void setSourceKey(String sourceKey) {
        this.sourceKey = sourceKey;
    }

    public Map<String, String> getLabel() {
        return label;
    }

    public void setLabel(Map<String, String> label) {
        this.label = label != null ? label : new HashMap<String, String>();
    }

    /** Localised label, falling back through French to the raw id so nothing renders blank. */
    public String getLabel(String language) {
        return Localised.pick(label, language, id);
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type != null ? type : "text";
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public boolean isEmphasis() {
        return emphasis;
    }

    public void setEmphasis(boolean emphasis) {
        this.emphasis = emphasis;
    }

    public String getRequiredPrivilege() {
        return requiredPrivilege;
    }

    public void setRequiredPrivilege(String requiredPrivilege) {
        this.requiredPrivilege = requiredPrivilege;
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
