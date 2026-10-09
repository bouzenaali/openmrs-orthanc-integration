package org.openmrs.module.medreport.api.render;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * The presentation-ready description of one report, as posted to the Report Generation
 * Service. Mirrors that service's {@code DocumentContext} schema field for field.
 *
 * <p>By the time an instance of this exists, every access decision has already been made:
 * only data the current user is allowed to see is in it, and every label is already in the
 * requested language. The rendering service performs no filtering and no translation of
 * clinical content - it lays out exactly what it is given. Keeping that boundary sharp is
 * what allows privilege enforcement to live in exactly one place (ADR-3).
 *
 * <p>Nested as static inner classes rather than a file each: they are one wire format, have
 * no behaviour, and are never used apart from the enclosing document.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DocumentContext {

    private String language = "fr";

    private String title;

    private String subtitle;

    private String facility;

    private String department;

    private PatientRef patient = new PatientRef();

    private AuthorRef author = new AuthorRef();

    private List<Section> sections = new ArrayList<Section>();

    @JsonProperty("image_observations")
    private List<ImageObservation> imageObservations = new ArrayList<ImageObservation>();

    private Options options = new Options();

    @JsonProperty("generated_at")
    private String generatedAt;

    private String reference;

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
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

    public String getFacility() {
        return facility;
    }

    public void setFacility(String facility) {
        this.facility = facility;
    }

    public String getDepartment() {
        return department;
    }

    public void setDepartment(String department) {
        this.department = department;
    }

    public PatientRef getPatient() {
        return patient;
    }

    public void setPatient(PatientRef patient) {
        this.patient = patient;
    }

    public AuthorRef getAuthor() {
        return author;
    }

    public void setAuthor(AuthorRef author) {
        this.author = author;
    }

    public List<Section> getSections() {
        return sections;
    }

    public void setSections(List<Section> sections) {
        this.sections = sections;
    }

    public List<ImageObservation> getImageObservations() {
        return imageObservations;
    }

    public void setImageObservations(List<ImageObservation> imageObservations) {
        this.imageObservations = imageObservations;
    }

    public Options getOptions() {
        return options;
    }

    public void setOptions(Options options) {
        this.options = options;
    }

    public String getGeneratedAt() {
        return generatedAt;
    }

    public void setGeneratedAt(String generatedAt) {
        this.generatedAt = generatedAt;
    }

    public String getReference() {
        return reference;
    }

    public void setReference(String reference) {
        this.reference = reference;
    }

    // ------------------------------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PatientRef {

        @JsonProperty("family_name")
        private String familyName = "";

        @JsonProperty("given_name")
        private String givenName = "";

        private String birthdate;

        private String age;

        private String gender;

        private String identifier;

        public String getFamilyName() {
            return familyName;
        }

        public void setFamilyName(String familyName) {
            this.familyName = familyName;
        }

        public String getGivenName() {
            return givenName;
        }

        public void setGivenName(String givenName) {
            this.givenName = givenName;
        }

        public String getBirthdate() {
            return birthdate;
        }

        public void setBirthdate(String birthdate) {
            this.birthdate = birthdate;
        }

        public String getAge() {
            return age;
        }

        public void setAge(String age) {
            this.age = age;
        }

        public String getGender() {
            return gender;
        }

        public void setGender(String gender) {
            this.gender = gender;
        }

        public String getIdentifier() {
            return identifier;
        }

        public void setIdentifier(String identifier) {
            this.identifier = identifier;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AuthorRef {

        @JsonProperty("full_name")
        private String fullName = "";

        private String role;

        public AuthorRef() {
        }

        public AuthorRef(String fullName) {
            this.fullName = fullName;
        }

        public String getFullName() {
            return fullName;
        }

        public void setFullName(String fullName) {
            this.fullName = fullName;
        }

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Field {

        private String key;

        private String label;

        private String value;

        private String type = "text";

        private String unit;

        private boolean emphasis;

        public Field() {
        }

        public Field(String key, String label, String value, String type) {
            this.key = key;
            this.label = label;
            this.value = value;
            this.type = type;
        }

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
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
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Record {

        private String title;

        private String subtitle;

        private List<Field> fields = new ArrayList<Field>();

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

        public List<Field> getFields() {
            return fields;
        }

        public void setFields(List<Field> fields) {
            this.fields = fields;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Section {

        private String id;

        private String title;

        private String description;

        private List<Field> fields = new ArrayList<Field>();

        private List<Record> records = new ArrayList<Record>();

        private List<Section> subsections = new ArrayList<Section>();

        private String note;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public List<Field> getFields() {
            return fields;
        }

        public void setFields(List<Field> fields) {
            this.fields = fields;
        }

        public List<Record> getRecords() {
            return records;
        }

        public void setRecords(List<Record> records) {
            this.records = records;
        }

        public List<Section> getSubsections() {
            return subsections;
        }

        public void setSubsections(List<Section> subsections) {
            this.subsections = subsections;
        }

        public String getNote() {
            return note;
        }

        public void setNote(String note) {
            this.note = note;
        }

        /** True when nothing survived selection and privilege filtering. */
        public boolean isEmpty() {
            return fields.isEmpty() && records.isEmpty() && subsections.isEmpty();
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ImageObservation {

        @JsonProperty("image_label")
        private String imageLabel;

        @JsonProperty("study_uid")
        private String studyUid;

        @JsonProperty("series_uid")
        private String seriesUid;

        private String modality;

        @JsonProperty("study_date")
        private String studyDate;

        private String author;

        @JsonProperty("observed_at")
        private String observedAt;

        private String text = "";

        public String getImageLabel() {
            return imageLabel;
        }

        public void setImageLabel(String imageLabel) {
            this.imageLabel = imageLabel;
        }

        public String getStudyUid() {
            return studyUid;
        }

        public void setStudyUid(String studyUid) {
            this.studyUid = studyUid;
        }

        public String getSeriesUid() {
            return seriesUid;
        }

        public void setSeriesUid(String seriesUid) {
            this.seriesUid = seriesUid;
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

        public String getAuthor() {
            return author;
        }

        public void setAuthor(String author) {
            this.author = author;
        }

        public String getObservedAt() {
            return observedAt;
        }

        public void setObservedAt(String observedAt) {
            this.observedAt = observedAt;
        }

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }
    }

    public static class Options {

        @JsonProperty("show_empty_fields")
        private boolean showEmptyFields;

        @JsonProperty("include_table_of_contents")
        private boolean includeTableOfContents;

        @JsonProperty("include_signature_block")
        private boolean includeSignatureBlock = true;

        @JsonProperty("include_page_numbers")
        private boolean includePageNumbers = true;

        @JsonProperty("include_generation_footer")
        private boolean includeGenerationFooter = true;

        @JsonProperty("confidentiality_notice")
        private boolean confidentialityNotice = true;

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

        public boolean isIncludeGenerationFooter() {
            return includeGenerationFooter;
        }

        public void setIncludeGenerationFooter(boolean includeGenerationFooter) {
            this.includeGenerationFooter = includeGenerationFooter;
        }

        public boolean isConfidentialityNotice() {
            return confidentialityNotice;
        }

        public void setConfidentialityNotice(boolean confidentialityNotice) {
            this.confidentialityNotice = confidentialityNotice;
        }
    }
}
