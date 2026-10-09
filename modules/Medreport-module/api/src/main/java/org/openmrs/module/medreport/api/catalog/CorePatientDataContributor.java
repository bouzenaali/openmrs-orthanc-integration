package org.openmrs.module.medreport.api.catalog;

import org.openmrs.Patient;
import org.openmrs.PatientIdentifier;
import org.openmrs.PersonAddress;
import org.openmrs.PersonAttribute;
import org.openmrs.module.medreport.api.spi.ClinicalDataContributor;
import org.openmrs.module.medreport.api.spi.DataSourceDescriptor;
import org.openmrs.module.medreport.api.spi.FieldDescriptor;
import org.openmrs.module.medreport.api.spi.SectionDescriptor;
import org.openmrs.util.PrivilegeConstants;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * medreport's own contribution: patient demographics straight from OpenMRS core.
 *
 * <p>Exists for two reasons. It makes the "Démographiques" set available on any install, with
 * no contributing module at all - a report of nothing but a name and an identifier is still a
 * report. And it is the worked example of the typed {@link ClinicalDataContributor} route, so
 * the interface is exercised by the module that defines it rather than only by third parties.
 *
 * <p>Everything here comes from {@code org.openmrs.Patient}, which the caller already holds:
 * no query, no privilege beyond core's own patient-read check.
 */
public class CorePatientDataContributor implements ClinicalDataContributor {

    public static final String SOURCE_ID = "core";

    public static final String SECTION_DEMOGRAPHICS = "core.demographics";

    public DataSourceDescriptor describe() {
        DataSourceDescriptor descriptor = new DataSourceDescriptor();
        descriptor.setId(SOURCE_ID);
        descriptor.setModuleId("medreport");
        descriptor.setLabel(labels("Dossier administratif", "Administrative record", "الملف الإداري"));
        descriptor.setSortWeight(0);

        SectionDescriptor demographics = new SectionDescriptor();
        demographics.setId(SECTION_DEMOGRAPHICS);
        demographics.setLabel(labels("Données démographiques", "Demographics", "البيانات الديموغرافية"));
        demographics.setDescription(labels(
                "Identité et coordonnées du patient, issues du dossier OpenMRS.",
                "Patient identity and contact details, from the OpenMRS record.",
                "هوية المريض وبيانات الاتصال من سجل OpenMRS."));
        demographics.setRequiredPrivilege(PrivilegeConstants.GET_PATIENTS);
        demographics.setSortWeight(0);

        List<FieldDescriptor> fields = new ArrayList<FieldDescriptor>();
        fields.add(field("familyName", "Nom", "Family name", "اللقب", "text", 0));
        fields.add(field("givenName", "Prénom", "Given name", "الاسم", "text", 1));
        fields.add(field("identifier", "Identifiant", "Identifier", "المعرّف", "text", 2));
        fields.add(field("gender", "Sexe", "Sex", "الجنس", "text", 3));
        fields.add(field("birthdate", "Date de naissance", "Date of birth", "تاريخ الميلاد", "date", 4));
        fields.add(field("age", "Âge", "Age", "العمر", "number", 5));
        fields.add(field("address", "Adresse", "Address", "العنوان", "text", 6));
        fields.add(field("city", "Commune / Ville", "City", "البلدية / المدينة", "text", 7));
        fields.add(field("phone", "Téléphone", "Phone", "الهاتف", "text", 8));
        fields.add(field("dead", "Décédé", "Deceased", "متوفى", "boolean", 9));
        fields.add(field("deathDate", "Date de décès", "Date of death", "تاريخ الوفاة", "date", 10));
        demographics.setFields(fields);

        descriptor.setSections(Collections.singletonList(demographics));
        return descriptor;
    }

    public List<Map<String, Object>> resolve(String sectionId, Patient patient) {
        if (!SECTION_DEMOGRAPHICS.equals(sectionId) || patient == null) {
            return Collections.emptyList();
        }

        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("familyName", patient.getFamilyName());
        row.put("givenName", patient.getGivenName());
        row.put("gender", patient.getGender());
        row.put("birthdate", patient.getBirthdate());
        row.put("age", patient.getAge());
        row.put("dead", patient.isDead());
        row.put("deathDate", patient.getDeathDate());

        PatientIdentifier identifier = patient.getPatientIdentifier();
        row.put("identifier", identifier != null ? identifier.getIdentifier() : null);

        PersonAddress address = patient.getPersonAddress();
        if (address != null) {
            StringBuilder lines = new StringBuilder();
            appendIfPresent(lines, address.getAddress1());
            appendIfPresent(lines, address.getAddress2());
            row.put("address", lines.length() > 0 ? lines.toString() : null);
            row.put("city", address.getCityVillage());
        } else {
            row.put("address", null);
            row.put("city", null);
        }

        // Phone is a person attribute rather than a column, and installs name it
        // differently; check the usual spellings rather than assuming one.
        row.put("phone", firstAttribute(patient, "Telephone Number", "Phone Number", "Téléphone"));

        return Collections.singletonList(row);
    }

    private static String firstAttribute(Patient patient, String... names) {
        for (String name : names) {
            PersonAttribute attribute = patient.getAttribute(name);
            if (attribute != null && attribute.getValue() != null
                    && !attribute.getValue().trim().isEmpty()) {
                return attribute.getValue();
            }
        }
        return null;
    }

    private static void appendIfPresent(StringBuilder builder, String value) {
        if (value != null && !value.trim().isEmpty()) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(value.trim());
        }
    }

    private static FieldDescriptor field(String id, String fr, String en, String ar,
                                         String type, int weight) {
        FieldDescriptor descriptor = new FieldDescriptor();
        descriptor.setId(id);
        descriptor.setLabel(labels(fr, en, ar));
        descriptor.setType(type);
        descriptor.setSortWeight(weight);
        return descriptor;
    }

    private static Map<String, String> labels(String fr, String en, String ar) {
        Map<String, String> labels = new HashMap<String, String>();
        labels.put("fr", fr);
        labels.put("en", en);
        labels.put("ar", ar);
        return labels;
    }
}
