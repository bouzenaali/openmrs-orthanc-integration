package org.openmrs.module.medreport.api;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.openmrs.module.medreport.api.spi.DataSourceDescriptor;
import org.openmrs.module.medreport.api.spi.FieldDescriptor;
import org.openmrs.module.medreport.api.spi.Localised;
import org.openmrs.module.medreport.api.spi.SectionDescriptor;

import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The manifest format is the entire contract between medreport and a contributing module,
 * and it is validated at runtime by nothing but Jackson. These tests pin the shape against a
 * copy of the real {@code patientview} manifest, so a change to the descriptor classes that
 * would silently stop parsing real manifests fails the build instead.
 */
public class DataSourceManifestTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private DataSourceDescriptor load() throws Exception {
        InputStream stream = getClass().getClassLoader()
                .getResourceAsStream("example-datasource.json");
        assertNotNull("example-datasource.json fixture is missing", stream);
        try {
            return MAPPER.readValue(stream, DataSourceDescriptor.class);
        } finally {
            stream.close();
        }
    }

    @Test
    public void aRealManifestParsesIntoDescriptors() throws Exception {
        DataSourceDescriptor descriptor = load();

        assertEquals("patientview", descriptor.getId());
        assertEquals("App: patientview.neurosurgeryDashboard", descriptor.getRequiredPrivilege());
        assertFalse("the manifest must declare sets", descriptor.getSections().isEmpty());
    }

    /**
     * A documentation key in the manifest must not break parsing - contributors are
     * encouraged to explain themselves in the file, and an unknown-property failure would
     * turn a comment into an outage.
     */
    @Test
    public void unknownKeysAreIgnoredRatherThanFatal() throws Exception {
        DataSourceDescriptor descriptor = MAPPER.readValue(
                "{\"id\":\"x\",\"_comment\":\"hello\",\"somethingNew\":42,\"sections\":[]}",
                DataSourceDescriptor.class);
        assertEquals("x", descriptor.getId());
    }

    @Test
    public void everySetAndFieldCarriesTheThreeSupportedLanguages() throws Exception {
        DataSourceDescriptor descriptor = load();
        for (SectionDescriptor section : descriptor.getSections()) {
            for (String language : new String[] { "fr", "en", "ar" }) {
                assertTrue("set " + section.getId() + " has no " + language + " label",
                        section.getLabel().containsKey(language));
            }
            for (FieldDescriptor field : section.getFields()) {
                for (String language : new String[] { "fr", "en", "ar" }) {
                    assertTrue("field " + section.getId() + "." + field.getId()
                                    + " has no " + language + " label",
                            field.getLabel().containsKey(language));
                }
            }
        }
    }

    @Test
    public void everySetDeclaresAUsableSource() throws Exception {
        DataSourceDescriptor descriptor = load();
        for (SectionDescriptor section : descriptor.getSections()) {
            assertNotNull("set " + section.getId() + " has no source", section.getSource());
            assertTrue("set " + section.getId() + " has an unusable source",
                    section.getSource().isUsable());
            assertTrue("the source must be an OpenMRS service interface",
                    section.getSource().getServiceClass().startsWith("org.openmrs."));
        }
    }

    /** Ids end up in saved preferences and in the audit log, so they must be unique. */
    @Test
    public void setAndFieldIdsAreUnique() throws Exception {
        DataSourceDescriptor descriptor = load();
        Set<String> sectionIds = new HashSet<String>();
        for (SectionDescriptor section : descriptor.getSections()) {
            assertTrue("duplicate set id: " + section.getId(), sectionIds.add(section.getId()));
            Set<String> fieldIds = new HashSet<String>();
            for (FieldDescriptor field : section.getFields()) {
                assertTrue("duplicate field id " + field.getId() + " in " + section.getId(),
                        fieldIds.add(field.getId()));
            }
        }
    }

    /** Only these render meaningfully; an unknown type would silently fall back to text. */
    @Test
    public void everyFieldTypeIsOneTheRendererUnderstands() throws Exception {
        Set<String> known = new HashSet<String>();
        known.add("text");
        known.add("longtext");
        known.add("number");
        known.add("date");
        known.add("boolean");
        known.add("list");

        for (SectionDescriptor section : load().getSections()) {
            for (FieldDescriptor field : section.getFields()) {
                assertTrue("unknown field type '" + field.getType() + "' on "
                        + section.getId() + "." + field.getId(), known.contains(field.getType()));
            }
        }
    }

    /** A repeating set without a title key renders records as "#1, #2" instead of dates. */
    @Test
    public void repeatingSetsNameTheirRecords() throws Exception {
        for (SectionDescriptor section : load().getSections()) {
            if (section.isRepeating()) {
                assertNotNull("repeating set " + section.getId() + " needs a recordTitleKey",
                        section.getRecordTitleKey());
            }
        }
    }

    /**
     * A record title key that is not also a declared field would read a map entry the
     * manifest never described - workable, but a sign the two have drifted apart.
     */
    @Test
    public void recordTitleKeysMatchADeclaredField() throws Exception {
        for (SectionDescriptor section : load().getSections()) {
            if (!section.isRepeating()) {
                continue;
            }
            Set<String> keys = new HashSet<String>();
            for (FieldDescriptor field : section.getFields()) {
                keys.add(field.getSourceKey());
            }
            assertTrue("recordTitleKey '" + section.getRecordTitleKey() + "' of "
                            + section.getId() + " is not a declared field",
                    keys.contains(section.getRecordTitleKey()));
        }
    }

    /** sourceKey defaults to the id, which is the common case and must not be null. */
    @Test
    public void sourceKeyFallsBackToTheFieldId() {
        FieldDescriptor field = new FieldDescriptor();
        field.setId("diagnosis");
        assertEquals("diagnosis", field.getSourceKey());
        field.setSourceKey("dx");
        assertEquals("dx", field.getSourceKey());
    }

    // ------------------------------------------------------------------
    // label fallback
    // ------------------------------------------------------------------

    @Test
    public void aMissingTranslationFallsBackInsteadOfRenderingBlank() {
        Map<String, String> labels = new HashMap<String, String>();
        labels.put("fr", "Diagnostic");

        assertEquals("Diagnostic", Localised.pick(labels, "fr", "id"));
        assertEquals("Diagnostic", Localised.pick(labels, "ar", "id"));
        assertEquals("Diagnostic", Localised.pick(labels, null, "id"));
    }

    @Test
    public void anEmptyLabelMapFallsBackToTheSuppliedDefault() {
        assertEquals("my.id", Localised.pick(new HashMap<String, String>(), "fr", "my.id"));
        assertEquals("my.id", Localised.pick(null, "fr", "my.id"));
        assertNull(Localised.pick(null, "fr", null));
    }

    @Test
    public void aBlankTranslationIsTreatedAsMissing() {
        Map<String, String> labels = new HashMap<String, String>();
        labels.put("ar", "   ");
        labels.put("en", "Diagnosis");
        assertEquals("Diagnosis", Localised.pick(labels, "ar", "id"));
    }
}
