package org.openmrs.module.medreport.api.spi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything one contributing module offers to the report builder - the parsed form of a
 * {@code medreport-datasource.json} manifest, or of what a {@link ClinicalDataContributor}
 * returns.
 *
 * <p>This is the "hey medreport, here is the data I hold for patients" announcement. A module
 * declares its sets and fields, how to fetch them, and what privilege each requires;
 * medreport merges every declaration into one catalogue, filters it against the current
 * user's privileges, and renders the personalisation tree from what survives.
 */
public class DataSourceDescriptor {

    private String id;

    /** The OpenMRS module that contributed this, filled in by the registry during discovery. */
    private String moduleId;

    private Map<String, String> label = new HashMap<String, String>();

    /** Privilege gating the whole contribution; individual sections may add their own. */
    private String requiredPrivilege;

    private List<SectionDescriptor> sections = new ArrayList<SectionDescriptor>();

    private int sortWeight;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getModuleId() {
        return moduleId;
    }

    public void setModuleId(String moduleId) {
        this.moduleId = moduleId;
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

    public String getRequiredPrivilege() {
        return requiredPrivilege;
    }

    public void setRequiredPrivilege(String requiredPrivilege) {
        this.requiredPrivilege = requiredPrivilege;
    }

    public List<SectionDescriptor> getSections() {
        return sections;
    }

    public void setSections(List<SectionDescriptor> sections) {
        this.sections = sections != null ? sections : new ArrayList<SectionDescriptor>();
    }

    public int getSortWeight() {
        return sortWeight;
    }

    public void setSortWeight(int sortWeight) {
        this.sortWeight = sortWeight;
    }
}
