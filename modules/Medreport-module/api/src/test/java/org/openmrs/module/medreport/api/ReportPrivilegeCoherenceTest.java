package org.openmrs.module.medreport.api;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openmrs.Patient;
import org.openmrs.PersonName;
import org.openmrs.User;
import org.openmrs.api.AdministrationService;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.context.Context;
import org.openmrs.module.medreport.MedreportPrivileges;
import org.openmrs.module.medreport.api.catalog.DataSourceRegistry;
import org.openmrs.module.medreport.api.dao.MedreportDao;
import org.openmrs.module.medreport.api.impl.PatientReportServiceImpl;
import org.openmrs.module.medreport.api.render.DocumentContext;
import org.openmrs.module.medreport.api.render.RenderedDocument;
import org.openmrs.module.medreport.api.render.ReportRenderClient;
import org.openmrs.module.medreport.api.report.ReportRequest;
import org.openmrs.module.medreport.api.spi.DataSource;
import org.openmrs.module.medreport.api.spi.DataSourceDescriptor;
import org.openmrs.module.medreport.api.spi.FieldDescriptor;
import org.openmrs.module.medreport.api.spi.SectionDescriptor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * "If a user cannot view a datum, it must not be possible to put it in a report."
 *
 * <p>That rule is enforced in two independent places, and both are tested here: the
 * catalogue never offers a set the user cannot read, and generation re-applies the same
 * filter to whatever selection actually arrives - so hand-crafting a request, or replaying a
 * preference saved before a role change, gains the caller nothing.
 */
public class ReportPrivilegeCoherenceTest {

    private static final String NEURO_PRIVILEGE = "App: patientview.neurosurgeryDashboard";

    private static final String ONCOLOGY_PRIVILEGE = "App: oncology.view";

    private MedreportDao dao;

    private DataSourceRegistry registry;

    private ReportRenderClient renderClient;

    private MedreportAuditService audit;

    private ImageReportService imageReports;

    private PatientReportServiceImpl service;

    private MockedStatic<Context> context;

    private Patient patient;

    @Before
    public void setUp() {
        dao = mock(MedreportDao.class);
        registry = mock(DataSourceRegistry.class);
        renderClient = mock(ReportRenderClient.class);
        audit = mock(MedreportAuditService.class);
        imageReports = mock(ImageReportService.class);

        service = new PatientReportServiceImpl();
        service.setDao(dao);
        service.setRegistry(registry);
        service.setRenderClient(renderClient);
        service.setAuditService(audit);
        service.setImageReportService(imageReports);

        patient = new Patient();
        patient.setPatientId(7);
        patient.addName(new PersonName("Yacine", null, "BENALI"));
        patient.setGender("M");
        patient.setBirthdate(new Date());

        context = Mockito.mockStatic(Context.class);

        AdministrationService admin = mock(AdministrationService.class);
        when(admin.getGlobalProperty(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        context.when(Context::getAdministrationService).thenReturn(admin);

        User user = new User();
        user.setUserId(3);
        user.setUsername("dr.kaci");
        context.when(Context::getAuthenticatedUser).thenReturn(user);

        when(registry.getDescriptors()).thenReturn(Arrays.asList(neuroSource(), oncologySource()));

        RenderedDocument rendered = new RenderedDocument();
        rendered.setArtifactId("0123456789abcdef0123456789abcdef");
        rendered.setFilename("rapport.pdf");
        rendered.setFormat("pdf");
        when(renderClient.render(any(), anyString(), anyString(), anyBoolean(), anyString()))
                .thenReturn(rendered);
        when(imageReports.getReportsForPatient(any())).thenReturn(Collections.emptyList());
    }

    @After
    public void tearDown() {
        context.close();
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    /** A contribution the test user may read, with one extra-sensitive field inside it. */
    private DataSourceDescriptor neuroSource() {
        DataSourceDescriptor descriptor = new DataSourceDescriptor();
        descriptor.setId("patientview");
        descriptor.setModuleId("patientview");
        descriptor.setLabel(labels("Dossier neurochirurgical", "Neurosurgery record"));
        descriptor.setRequiredPrivilege(NEURO_PRIVILEGE);

        SectionDescriptor diagnosis = new SectionDescriptor();
        diagnosis.setId("patientview.diagnosis");
        diagnosis.setLabel(labels("Diagnostic neurochirurgical", "Neurosurgical diagnosis"));
        diagnosis.setSource(source("getNeurosurgicalDiagnoses"));
        diagnosis.setFields(Arrays.asList(
                field("diagnosis", "Diagnostic", null),
                field("laterality", "Latéralité", null),
                // More sensitive than the set it lives in - its own privilege on top.
                field("whoDiagnosis", "Classification OMS", ONCOLOGY_PRIVILEGE)));
        descriptor.setSections(Collections.singletonList(diagnosis));
        return descriptor;
    }

    /** A whole contribution the test user may not read. */
    private DataSourceDescriptor oncologySource() {
        DataSourceDescriptor descriptor = new DataSourceDescriptor();
        descriptor.setId("oncology");
        descriptor.setModuleId("oncology");
        descriptor.setLabel(labels("Oncologie", "Oncology"));
        descriptor.setRequiredPrivilege(ONCOLOGY_PRIVILEGE);

        SectionDescriptor protocols = new SectionDescriptor();
        protocols.setId("oncology.protocols");
        protocols.setLabel(labels("Protocoles", "Protocols"));
        protocols.setSource(source("getProtocols"));
        protocols.setFields(Collections.singletonList(field("protocol", "Protocole", null)));
        descriptor.setSections(Collections.singletonList(protocols));
        return descriptor;
    }

    private DataSource source(String method) {
        DataSource source = new DataSource();
        source.setServiceClass("org.example.SomeService");
        source.setMethod(method);
        return source;
    }

    private FieldDescriptor field(String id, String label, String privilege) {
        FieldDescriptor descriptor = new FieldDescriptor();
        descriptor.setId(id);
        descriptor.setLabel(labels(label, label));
        descriptor.setRequiredPrivilege(privilege);
        return descriptor;
    }

    private Map<String, String> labels(String fr, String en) {
        Map<String, String> labels = new HashMap<String, String>();
        labels.put("fr", fr);
        labels.put("en", en);
        return labels;
    }

    private void loginWith(String... privileges) {
        context.when(() -> Context.hasPrivilege(anyString())).thenReturn(false);
        for (String privilege : privileges) {
            context.when(() -> Context.hasPrivilege(privilege)).thenReturn(true);
        }
    }

    private void resolvesTo(Map<String, Object> row) {
        when(registry.resolve(any(), any(), any()))
                .thenReturn(Collections.singletonList(row));
    }

    private Map<String, Object> diagnosisRow() {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("diagnosis", "Méningiome frontal");
        row.put("laterality", "Gauche");
        row.put("whoDiagnosis", "Grade I");
        return row;
    }

    private DocumentContext capturedDocument() {
        ArgumentCaptor<DocumentContext> captor = ArgumentCaptor.forClass(DocumentContext.class);
        Mockito.verify(renderClient)
                .render(captor.capture(), anyString(), anyString(), anyBoolean(), anyString());
        return captor.getValue();
    }

    private DocumentContext.Section sectionOf(DocumentContext document, String id) {
        for (DocumentContext.Section section : document.getSections()) {
            if (id.equals(section.getId())) {
                return section;
            }
        }
        return null;
    }

    private List<String> fieldKeys(DocumentContext.Section section) {
        List<String> keys = new ArrayList<String>();
        if (section != null) {
            for (DocumentContext.Field field : section.getFields()) {
                keys.add(field.getKey());
            }
        }
        return keys;
    }

    // ==================================================================
    // The catalogue only offers what the user may read
    // ==================================================================

    @Test
    public void generatingAtAllRequiresTheDashboardPrivilege() {
        loginWith(NEURO_PRIVILEGE);
        try {
            service.getCatalog("fr");
            fail("expected the catalogue to be refused");
        } catch (APIAuthenticationException expected) {
            assertTrue(expected.getMessage().contains(MedreportPrivileges.APP_DASHBOARD_GENERATE));
        }
    }

    @Test
    public void theCatalogueOmitsContributionsTheUserCannotRead() {
        loginWith(MedreportPrivileges.APP_DASHBOARD_GENERATE, NEURO_PRIVILEGE);

        List<Map<String, Object>> catalogue = service.getCatalog("fr");

        List<String> ids = new ArrayList<String>();
        for (Map<String, Object> group : catalogue) {
            ids.add((String) group.get("id"));
        }
        assertTrue("the permitted source must be offered", ids.contains("patientview"));
        // Absent rather than disabled: a greyed-out "Oncologie" would itself disclose that
        // this patient has an oncology record.
        assertFalse("the forbidden source must not be offered at all", ids.contains("oncology"));
    }

    @Test
    public void theCatalogueOmitsIndividualFieldsTheUserCannotRead() {
        loginWith(MedreportPrivileges.APP_DASHBOARD_GENERATE, NEURO_PRIVILEGE);

        List<Map<String, Object>> catalogue = service.getCatalog("fr");
        Map<String, Object> section = firstSection(catalogue, "patientview");
        List<String> fieldIds = fieldIds(section);

        assertTrue(fieldIds.contains("diagnosis"));
        assertTrue(fieldIds.contains("laterality"));
        assertFalse("a field with its own privilege must be hidden without it",
                fieldIds.contains("whoDiagnosis"));
    }

    @Test
    public void theCatalogueShowsTheExtraFieldOnceTheUserHoldsItsPrivilege() {
        loginWith(MedreportPrivileges.APP_DASHBOARD_GENERATE, NEURO_PRIVILEGE, ONCOLOGY_PRIVILEGE);

        Map<String, Object> section = firstSection(service.getCatalog("fr"), "patientview");

        assertTrue(fieldIds(section).contains("whoDiagnosis"));
    }

    @Test
    public void catalogueLabelsFollowTheRequestedLanguage() {
        loginWith(MedreportPrivileges.APP_DASHBOARD_GENERATE, NEURO_PRIVILEGE);

        assertEquals("Dossier neurochirurgical", service.getCatalog("fr").get(0).get("label"));
        assertEquals("Neurosurgery record", service.getCatalog("en").get(0).get("label"));
        // Arabic is not in this fixture's labels, so it falls back to French rather than
        // rendering an empty heading.
        assertEquals("Dossier neurochirurgical", service.getCatalog("ar").get(0).get("label"));
    }

    // ==================================================================
    // Generation re-applies the same filter to the incoming selection
    // ==================================================================

    @Test
    public void aForgedSelectionCannotPullInAForbiddenSource() {
        loginWith(MedreportPrivileges.APP_DASHBOARD_GENERATE, NEURO_PRIVILEGE);
        resolvesTo(diagnosisRow());

        ReportRequest request = new ReportRequest();
        // Both ids posted directly, as a hand-crafted request would.
        request.setSections(Arrays.asList("patientview.diagnosis", "oncology.protocols"));
        request.setFormat("pdf");

        service.generate(patient, request);

        DocumentContext document = capturedDocument();
        assertNotNull("the permitted section is included",
                sectionOf(document, "patientview.diagnosis"));
        assertEquals("the forbidden section must not reach the document",
                null, sectionOf(document, "oncology.protocols"));
    }

    @Test
    public void aForgedSelectionCannotPullInAForbiddenField() {
        loginWith(MedreportPrivileges.APP_DASHBOARD_GENERATE, NEURO_PRIVILEGE);
        resolvesTo(diagnosisRow());

        ReportRequest request = new ReportRequest();
        request.setSections(Collections.singletonList("patientview.diagnosis"));
        Map<String, List<String>> fields = new HashMap<String, List<String>>();
        fields.put("patientview.diagnosis",
                Arrays.asList("diagnosis", "laterality", "whoDiagnosis"));
        request.setFields(fields);

        service.generate(patient, request);

        List<String> keys = fieldKeys(sectionOf(capturedDocument(), "patientview.diagnosis"));
        assertTrue(keys.contains("diagnosis"));
        assertTrue(keys.contains("laterality"));
        assertFalse("the privileged field must be stripped even when explicitly requested",
                keys.contains("whoDiagnosis"));
    }

    @Test
    public void tickingASetWithoutUnfoldingItIncludesEveryFieldOfThatSet() {
        loginWith(MedreportPrivileges.APP_DASHBOARD_GENERATE, NEURO_PRIVILEGE, ONCOLOGY_PRIVILEGE);
        resolvesTo(diagnosisRow());

        ReportRequest request = new ReportRequest();
        request.setSections(Collections.singletonList("patientview.diagnosis"));
        // No entry in `fields` - the parent checkbox alone means "all of it".

        service.generate(patient, request);

        List<String> keys = fieldKeys(sectionOf(capturedDocument(), "patientview.diagnosis"));
        assertEquals(3, keys.size());
    }

    @Test
    public void unfoldingASetAndPickingFieldsIncludesOnlyThose() {
        loginWith(MedreportPrivileges.APP_DASHBOARD_GENERATE, NEURO_PRIVILEGE, ONCOLOGY_PRIVILEGE);
        resolvesTo(diagnosisRow());

        ReportRequest request = new ReportRequest();
        request.setSections(Collections.singletonList("patientview.diagnosis"));
        Map<String, List<String>> fields = new HashMap<String, List<String>>();
        fields.put("patientview.diagnosis", Collections.singletonList("diagnosis"));
        request.setFields(fields);

        service.generate(patient, request);

        assertEquals(Collections.singletonList("diagnosis"),
                fieldKeys(sectionOf(capturedDocument(), "patientview.diagnosis")));
    }

    @Test
    public void anUnselectedSetIsNotIncluded() {
        loginWith(MedreportPrivileges.APP_DASHBOARD_GENERATE, NEURO_PRIVILEGE);
        resolvesTo(diagnosisRow());

        ReportRequest request = new ReportRequest();
        request.setSections(Collections.<String>emptyList());

        service.generate(patient, request);

        assertTrue(capturedDocument().getSections().isEmpty());
    }

    @Test
    public void imagingObservationsAreOnlyIncludedWithTheImagingViewPrivilege() {
        loginWith(MedreportPrivileges.APP_DASHBOARD_GENERATE, NEURO_PRIVILEGE);
        resolvesTo(diagnosisRow());

        ReportRequest request = new ReportRequest();
        request.setSections(Collections.singletonList("medreport.imaging.observations"));
        request.setIncludeImageObservations(true);

        service.generate(patient, request);

        // Without medreport.imaging.view the imaging service is never even consulted.
        Mockito.verify(imageReports, Mockito.never()).getReportsForPatient(any());
        assertTrue(capturedDocument().getImageObservations().isEmpty());
    }

    @Test
    public void generationIsAudited() {
        loginWith(MedreportPrivileges.APP_DASHBOARD_GENERATE, NEURO_PRIVILEGE);
        resolvesTo(diagnosisRow());

        ReportRequest request = new ReportRequest();
        request.setSections(Collections.singletonList("patientview.diagnosis"));

        service.generate(patient, request);

        Mockito.verify(audit).logForPatient(anyString(), Mockito.eq(7),
                Mockito.eq(true), anyString());
    }

    // ==================================================================
    // Value handling
    // ==================================================================

    @Test
    public void emptyValuesAreDroppedUnlessTheUserAsksToSeeThem() {
        loginWith(MedreportPrivileges.APP_DASHBOARD_GENERATE, NEURO_PRIVILEGE, ONCOLOGY_PRIVILEGE);
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("diagnosis", "Méningiome frontal");
        row.put("laterality", null);
        // The literal four-character string "null" reaches columns through some legacy
        // write paths; it must be treated as missing, not printed.
        row.put("whoDiagnosis", "null");
        resolvesTo(row);

        ReportRequest request = new ReportRequest();
        request.setSections(Collections.singletonList("patientview.diagnosis"));

        service.generate(patient, request);

        assertEquals(Collections.singletonList("diagnosis"),
                fieldKeys(sectionOf(capturedDocument(), "patientview.diagnosis")));
    }

    @Test
    public void showEmptyFieldsKeepsThePlaceholders() {
        loginWith(MedreportPrivileges.APP_DASHBOARD_GENERATE, NEURO_PRIVILEGE, ONCOLOGY_PRIVILEGE);
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("diagnosis", "Méningiome frontal");
        resolvesTo(row);

        ReportRequest request = new ReportRequest();
        request.setSections(Collections.singletonList("patientview.diagnosis"));
        request.setShowEmptyFields(true);

        service.generate(patient, request);

        assertEquals(3, fieldKeys(sectionOf(capturedDocument(), "patientview.diagnosis")).size());
    }

    @Test
    public void unsupportedLanguagesAndFormatsFallBackInsteadOfFailing() {
        loginWith(MedreportPrivileges.APP_DASHBOARD_GENERATE, NEURO_PRIVILEGE);
        resolvesTo(diagnosisRow());

        ReportRequest request = new ReportRequest();
        request.setSections(Collections.singletonList("patientview.diagnosis"));
        request.setLanguage("de");
        request.setFormat("exe");

        service.generate(patient, request);

        assertEquals("fr", capturedDocument().getLanguage());
        ArgumentCaptor<String> format = ArgumentCaptor.forClass(String.class);
        Mockito.verify(renderClient).render(any(), anyString(), format.capture(),
                anyBoolean(), anyString());
        assertEquals("pdf", format.getValue());
    }

    @Test
    public void theDocumentCarriesThePatientAndTheAuthor() {
        loginWith(MedreportPrivileges.APP_DASHBOARD_GENERATE, NEURO_PRIVILEGE);
        resolvesTo(diagnosisRow());

        ReportRequest request = new ReportRequest();
        request.setSections(Collections.singletonList("patientview.diagnosis"));

        service.generate(patient, request);

        DocumentContext document = capturedDocument();
        assertEquals("BENALI", document.getPatient().getFamilyName());
        assertEquals("Yacine", document.getPatient().getGivenName());
        assertEquals("dr.kaci", document.getAuthor().getFullName());
    }

    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstSection(List<Map<String, Object>> catalogue, String sourceId) {
        for (Map<String, Object> group : catalogue) {
            if (sourceId.equals(group.get("id"))) {
                List<Map<String, Object>> sections =
                        (List<Map<String, Object>>) group.get("sections");
                return sections.isEmpty() ? null : sections.get(0);
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<String> fieldIds(Map<String, Object> section) {
        List<String> ids = new ArrayList<String>();
        if (section == null) {
            return ids;
        }
        for (Map<String, Object> field : (List<Map<String, Object>>) section.get("fields")) {
            ids.add((String) field.get("id"));
        }
        return ids;
    }
}
