package org.openmrs.module.medreport.api;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openmrs.Patient;
import org.openmrs.User;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.module.medreport.MedreportConstants;
import org.openmrs.module.medreport.MedreportPrivileges;
import org.openmrs.module.medreport.api.dao.MedreportDao;
import org.openmrs.module.medreport.api.impl.ImageReportServiceImpl;
import org.openmrs.module.medreport.api.model.ImageReport;
import org.openmrs.module.medreport.api.model.ImageReportVersion;
import org.openmrs.module.medreport.api.model.ReportImageLink;
import org.openmrs.module.medreport.api.render.ReportRenderClient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The access-control, versioning and data-model rules (RP1-RP9), each asserted directly.
 *
 * <p>These are unit tests over the service with a mocked DAO and a mocked static
 * {@code Context}: they exercise the decision logic, which is where the rules live, without
 * needing a database. The rendering call is stubbed to fail, which doubles as a check that a
 * report is still stored when the rendering service is unavailable.
 */
public class ImageReportServiceRulesTest {

    private static final int AUTHOR_ID = 11;

    private static final int OTHER_ID = 22;

    private MedreportDao dao;

    private MedreportAuditService audit;

    private ReportRenderClient renderClient;

    private ImageReportServiceImpl service;

    private MockedStatic<Context> context;

    private User author;

    private User other;

    private Patient patient;

    @Before
    public void setUp() {
        dao = mock(MedreportDao.class);
        audit = mock(MedreportAuditService.class);
        renderClient = mock(ReportRenderClient.class);

        service = new ImageReportServiceImpl();
        service.setDao(dao);
        service.setAuditService(audit);
        service.setRenderClient(renderClient);

        author = new User();
        author.setUserId(AUTHOR_ID);
        author.setUsername("dr.kaci");

        other = new User();
        other.setUserId(OTHER_ID);
        other.setUsername("dr.belkacem");

        patient = new Patient();
        patient.setPatientId(500);

        context = Mockito.mockStatic(Context.class);
        // Persisting the render is deliberately best-effort; failing it here keeps these
        // tests focused on the rules and proves the report survives a renderer outage.
        when(renderClient.render(any(), anyString(), anyString(), Mockito.anyBoolean(), anyString()))
                .thenThrow(new org.openmrs.module.medreport.api.render.RenderException("no renderer in tests"));
    }

    @After
    public void tearDown() {
        context.close();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private void loginAs(User user, String... privileges) {
        context.when(Context::getAuthenticatedUser).thenReturn(user);
        context.when(() -> Context.hasPrivilege(anyString())).thenReturn(false);
        for (String privilege : privileges) {
            context.when(() -> Context.hasPrivilege(privilege)).thenReturn(true);
        }
    }

    private ImageReport existingReport(User reportAuthor, boolean voided) {
        ImageReport report = new ImageReport();
        report.setId(1);
        report.setUuid("report-uuid");
        report.setPatient(patient);
        report.setAuthor(reportAuthor);
        report.setDateCreated(new Date());
        report.setVoided(voided);

        ImageReportVersion current = new ImageReportVersion();
        current.setId(10);
        current.setUuid("version-uuid");
        current.setReport(report);
        current.setVersionNumber(1);
        current.setObservationText("Original text.");
        current.setChangeType(MedreportConstants.CHANGE_CREATE);
        current.setCurrent(true);
        current.setDateCreated(new Date());
        current.setCreatedBy(reportAuthor);
        ReportImageLink link = new ReportImageLink("study-1");
        link.setVersion(current);
        link.setReport(report);
        current.getImages().add(link);

        report.setCurrentVersion(current);
        when(dao.getReportByUuid("report-uuid")).thenReturn(report);
        when(dao.getCurrentVersion(report)).thenReturn(current);
        when(dao.getNextVersionNumber(report)).thenReturn(2);
        when(dao.getVersions(report)).thenReturn(Arrays.asList(current));
        return report;
    }

    private List<ReportImageLink> images(String... studyUids) {
        List<ReportImageLink> links = new ArrayList<ReportImageLink>();
        for (String uid : studyUids) {
            links.add(new ReportImageLink(uid));
        }
        return links;
    }

    // ==================================================================
    // RP1 - create requires the manage privilege
    // ==================================================================

    @Test
    public void rp1_createIsRefusedWithoutTheManagePrivilege() {
        loginAs(author, MedreportPrivileges.APP_IMAGING_VIEW);
        try {
            service.createReport(patient, "T", "text", images("study-1"));
            fail("expected the create to be refused");
        } catch (APIAuthenticationException expected) {
            assertTrue(expected.getMessage().contains(MedreportPrivileges.APP_IMAGING_MANAGE));
        }
        verify(dao, never()).saveReport(any(ImageReport.class));
    }

    @Test
    public void rp1_createSucceedsWithTheManagePrivilegeAndSetsTheAuthor() {
        loginAs(author, MedreportPrivileges.APP_IMAGING_MANAGE);

        ImageReport report = service.createReport(patient, "Compte rendu", "IRM normale.",
                images("study-1"));

        assertNotNull(report);
        assertEquals(author, report.getAuthor());
        assertFalse(report.isVoided());
        assertNotNull(report.getUuid());
        assertEquals(Integer.valueOf(1), report.getCurrentVersion().getVersionNumber());
        assertEquals(MedreportConstants.CHANGE_CREATE, report.getCurrentVersion().getChangeType());
        assertTrue(report.getCurrentVersion().isCurrent());
    }

    @Test
    public void rp1_anEmptyObservationIsRejected() {
        loginAs(author, MedreportPrivileges.APP_IMAGING_MANAGE);
        try {
            service.createReport(patient, "T", "   ", images("study-1"));
            fail("expected an empty observation to be rejected");
        } catch (APIException expected) {
            assertTrue(expected.getMessage().toLowerCase().contains("empty"));
        }
    }

    // ==================================================================
    // RP2 - reading anyone's report requires the view privilege
    // ==================================================================

    @Test
    public void rp2_readIsRefusedWithoutTheViewPrivilege() {
        loginAs(other, MedreportPrivileges.APP_IMAGING_MANAGE);
        existingReport(author, false);
        try {
            service.getReport("report-uuid");
            fail("expected the read to be refused");
        } catch (APIAuthenticationException expected) {
            assertTrue(expected.getMessage().contains(MedreportPrivileges.APP_IMAGING_VIEW));
        }
    }

    @Test
    public void rp2_anotherUsersReportIsReadableWithTheViewPrivilege() {
        loginAs(other, MedreportPrivileges.APP_IMAGING_VIEW);
        existingReport(author, false);

        ImageReport report = service.getReport("report-uuid");

        assertNotNull(report);
        // The author travels with the report so every view can show who wrote it.
        assertEquals(author, report.getAuthor());
    }

    // ==================================================================
    // RP3 - update requires manage AND authorship, and archives a version
    // ==================================================================

    @Test
    public void rp3_updateIsRefusedForAReportTheUserDidNotAuthor() {
        loginAs(other, MedreportPrivileges.APP_IMAGING_MANAGE, MedreportPrivileges.APP_IMAGING_VIEW);
        ImageReport report = existingReport(author, false);
        try {
            service.updateReport("report-uuid", "T", "new text", null, "typo");
            fail("expected the update to be refused");
        } catch (APIAuthenticationException expected) {
            assertTrue(expected.getMessage().toLowerCase().contains("author"));
        }
        verify(dao, never()).saveVersion(any(ImageReportVersion.class));
        // RP7: a refused attempt is itself audited.
        verify(audit).logFailure(eq(MedreportConstants.ACTION_UPDATE), eq(report), anyString());
    }

    @Test
    public void rp3_updateIsRefusedWithoutTheManagePrivilegeEvenForTheAuthor() {
        loginAs(author, MedreportPrivileges.APP_IMAGING_VIEW);
        existingReport(author, false);
        try {
            service.updateReport("report-uuid", "T", "new text", null, null);
            fail("expected the update to be refused");
        } catch (APIAuthenticationException expected) {
            assertTrue(expected.getMessage().contains(MedreportPrivileges.APP_IMAGING_MANAGE));
        }
    }

    @Test
    public void rp3_updateArchivesTheOldVersionInsteadOfOverwritingIt() {
        loginAs(author, MedreportPrivileges.APP_IMAGING_MANAGE);
        ImageReport report = existingReport(author, false);
        ImageReportVersion previous = report.getCurrentVersion();

        service.updateReport("report-uuid", "Corrigé", "Texte corrigé.", null, "erreur de frappe");

        // Exactly one version is current at a time: the flag is cleared before the new row
        // is written.
        verify(dao).clearCurrentFlag(report);

        ArgumentCaptor<ImageReportVersion> saved = ArgumentCaptor.forClass(ImageReportVersion.class);
        verify(dao, atLeastOnce()).saveVersion(saved.capture());
        ImageReportVersion created = saved.getAllValues().get(0);

        assertEquals(Integer.valueOf(2), created.getVersionNumber());
        assertEquals(MedreportConstants.CHANGE_UPDATE, created.getChangeType());
        assertTrue(created.isCurrent());
        assertEquals("Texte corrigé.", created.getObservationText());
        assertEquals("erreur de frappe", created.getChangeReason());
        // The chain is what makes the archive traversable and immutable.
        assertEquals(previous, created.getPreviousVersion());
        // The previous row is untouched - not edited, not deleted.
        assertEquals("Original text.", previous.getObservationText());
    }

    @Test
    public void rp3_updateKeepsTheCoveredImagesWhenNoNewListIsGiven() {
        loginAs(author, MedreportPrivileges.APP_IMAGING_MANAGE);
        ImageReport report = existingReport(author, false);

        service.updateReport("report-uuid", null, "Texte corrigé.", null, null);

        ArgumentCaptor<ImageReportVersion> saved = ArgumentCaptor.forClass(ImageReportVersion.class);
        verify(dao, atLeastOnce()).saveVersion(saved.capture());
        ImageReportVersion created = saved.getAllValues().get(0);

        assertEquals(1, created.getImages().size());
        assertEquals("study-1", created.getImages().get(0).getOrthancStudyUid());
        // Fresh rows, not the previous version's: an archived version must keep its own
        // record of what it covered.
        assertEquals(created, created.getImages().get(0).getVersion());
    }

    @Test
    public void rp3_aRemovedReportCannotBeEdited() {
        loginAs(author, MedreportPrivileges.APP_IMAGING_MANAGE);
        existingReport(author, true);
        try {
            service.updateReport("report-uuid", null, "text", null, null);
            fail("expected editing a removed report to be refused");
        } catch (APIException expected) {
            assertTrue(expected.getMessage().toLowerCase().contains("removed"));
        }
    }

    // ==================================================================
    // RP4 - version history is administrator-only
    // ==================================================================

    @Test
    public void rp4_theAuthorCannotBrowseTheVersionHistory() {
        loginAs(author, MedreportPrivileges.APP_IMAGING_VIEW, MedreportPrivileges.APP_IMAGING_MANAGE);
        ImageReport report = existingReport(author, false);
        try {
            service.getVersionHistory("report-uuid");
            fail("expected the history read to be refused for a non-administrator");
        } catch (APIAuthenticationException expected) {
            assertTrue(expected.getMessage().contains(MedreportPrivileges.APP_ADMIN));
        }
        verify(audit).logFailure(eq(MedreportConstants.ACTION_VIEW_HISTORY), eq(report), anyString());
    }

    @Test
    public void rp4_anAdministratorSeesTheFullChainAndTheAccessIsAudited() {
        loginAs(other, MedreportPrivileges.APP_ADMIN);
        ImageReport report = existingReport(author, false);

        List<ImageReportVersion> versions = service.getVersionHistory("report-uuid");

        assertEquals(1, versions.size());
        verify(audit).log(eq(MedreportConstants.ACTION_VIEW_HISTORY), eq(report),
                any(), anyString());
    }

    @Test
    public void rp4_aSupersededVersionsDocumentIsNotDownloadableByANonAdministrator() {
        loginAs(author, MedreportPrivileges.APP_IMAGING_VIEW);
        ImageReport report = existingReport(author, false);
        ImageReportVersion superseded = report.getCurrentVersion();
        superseded.setCurrent(false);
        when(dao.getVersionByUuid("version-uuid")).thenReturn(superseded);

        try {
            service.getDocument("version-uuid");
            fail("expected the superseded-version download to be refused");
        } catch (APIAuthenticationException expected) {
            assertTrue(expected.getMessage().contains(MedreportPrivileges.APP_ADMIN));
        }
    }

    // ==================================================================
    // RP5 - remove is a soft delete, restricted to the author
    // ==================================================================

    @Test
    public void rp5_removeIsRefusedForAReportTheUserDidNotAuthor() {
        loginAs(other, MedreportPrivileges.APP_IMAGING_MANAGE);
        ImageReport report = existingReport(author, false);
        try {
            service.removeReport("report-uuid", "oops");
            fail("expected the removal to be refused");
        } catch (APIAuthenticationException expected) {
            assertTrue(expected.getMessage().toLowerCase().contains("author"));
        }
        assertFalse(report.isVoided());
        verify(audit).logFailure(eq(MedreportConstants.ACTION_REMOVE), eq(report), anyString());
    }

    @Test
    public void rp5_removeIsSoftAndKeepsEverythingStored() {
        loginAs(author, MedreportPrivileges.APP_IMAGING_MANAGE);
        ImageReport report = existingReport(author, false);
        ImageReportVersion previous = report.getCurrentVersion();

        service.removeReport("report-uuid", "doublon");

        assertTrue(report.isVoided());
        assertEquals(author, report.getVoidedBy());
        assertNotNull(report.getDateVoided());
        assertEquals("doublon", report.getVoidReason());

        // RP8: the removal is itself an archived version, carrying the content forward.
        ArgumentCaptor<ImageReportVersion> saved = ArgumentCaptor.forClass(ImageReportVersion.class);
        verify(dao).saveVersion(saved.capture());
        ImageReportVersion marker = saved.getValue();
        assertEquals(MedreportConstants.CHANGE_REMOVE, marker.getChangeType());
        assertEquals(previous, marker.getPreviousVersion());
        assertEquals("Original text.", marker.getObservationText());

        // Nothing is physically deleted - the DAO exposes no delete at all, and the
        // previous version object is untouched.
        assertEquals("Original text.", previous.getObservationText());
    }

    @Test
    public void rp5_aRemovedReportIsInvisibleToEveryoneButAnAdministrator() {
        existingReport(author, true);

        loginAs(other, MedreportPrivileges.APP_IMAGING_VIEW);
        assertNull("a removed report must not be readable", service.getReport("report-uuid"));

        // Even its own author does not see it once removed.
        loginAs(author, MedreportPrivileges.APP_IMAGING_VIEW, MedreportPrivileges.APP_IMAGING_MANAGE);
        assertNull("the author must not see their own removed report",
                service.getReport("report-uuid"));

        loginAs(other, MedreportPrivileges.APP_IMAGING_VIEW, MedreportPrivileges.APP_ADMIN);
        assertNotNull("an administrator must still see it", service.getReport("report-uuid"));
    }

    @Test
    public void rp5_removingTwiceIsANoOp() {
        loginAs(author, MedreportPrivileges.APP_IMAGING_MANAGE);
        existingReport(author, true);

        service.removeReport("report-uuid", "again");

        verify(dao, never()).saveVersion(any(ImageReportVersion.class));
    }

    // ==================================================================
    // Restore - administrator only
    // ==================================================================

    @Test
    public void restoreIsRefusedForANonAdministrator() {
        loginAs(author, MedreportPrivileges.APP_IMAGING_MANAGE, MedreportPrivileges.APP_IMAGING_VIEW);
        existingReport(author, true);
        try {
            service.restoreReport("report-uuid", "mistake");
            fail("expected the restore to be refused");
        } catch (APIAuthenticationException expected) {
            assertTrue(expected.getMessage().contains(MedreportPrivileges.APP_ADMIN));
        }
    }

    @Test
    public void restoreByAnAdministratorClearsTheSoftDeleteAndAppendsAVersion() {
        loginAs(other, MedreportPrivileges.APP_ADMIN);
        ImageReport report = existingReport(author, true);

        service.restoreReport("report-uuid", "supprimé par erreur");

        assertFalse(report.isVoided());
        assertNull(report.getVoidedBy());
        assertNull(report.getDateVoided());

        ArgumentCaptor<ImageReportVersion> saved = ArgumentCaptor.forClass(ImageReportVersion.class);
        verify(dao).saveVersion(saved.capture());
        assertEquals(MedreportConstants.CHANGE_RESTORE, saved.getValue().getChangeType());
        verify(audit).log(eq(MedreportConstants.ACTION_RESTORE), eq(report), any(), anyString());
    }

    // ==================================================================
    // RP7 - every action is audited
    // ==================================================================

    @Test
    public void rp7_createIsAudited() {
        loginAs(author, MedreportPrivileges.APP_IMAGING_MANAGE);
        service.createReport(patient, "T", "texte", images("study-1"));
        verify(audit).log(eq(MedreportConstants.ACTION_CREATE), any(ImageReport.class),
                any(ImageReportVersion.class), anyString());
    }

    @Test
    public void rp7_aFailedRenderIsAuditedButDoesNotLoseTheReport() {
        loginAs(author, MedreportPrivileges.APP_IMAGING_MANAGE);

        ImageReport report = service.createReport(patient, "T", "texte", images("study-1"));

        // The clinician's text is the clinical record; a renderer outage must not discard it.
        assertNotNull(report);
        assertEquals("texte", report.getCurrentVersion().getObservationText());
        verify(audit).logFailure(eq(MedreportConstants.ACTION_GENERATE), any(ImageReport.class),
                anyString());
    }

    // ==================================================================
    // RP9 - a report covers many images
    // ==================================================================

    @Test
    public void rp9_oneReportCanCoverSeveralStudies() {
        loginAs(author, MedreportPrivileges.APP_IMAGING_MANAGE);

        ImageReport report = service.createReport(patient, "Synthèse",
                "Comparaison des trois examens.", images("study-1", "study-2", "study-3"));

        List<ReportImageLink> links = report.getCurrentVersion().getImages();
        assertEquals(3, links.size());
        assertEquals("study-1", links.get(0).getOrthancStudyUid());
        assertEquals("study-3", links.get(2).getOrthancStudyUid());
        // Ordering is preserved so the document lists images as the author arranged them.
        assertEquals(Integer.valueOf(0), links.get(0).getSortWeight());
        assertEquals(Integer.valueOf(2), links.get(2).getSortWeight());
        for (ReportImageLink link : links) {
            // Both ends of the association are populated: report_id answers "which images
            // does this report cover", version_id keeps each archived version's set intact.
            assertEquals(report, link.getReport());
            assertEquals(report.getCurrentVersion(), link.getVersion());
        }
    }

    @Test
    public void rp9_aReportMustReferenceAtLeastOneImage() {
        loginAs(author, MedreportPrivileges.APP_IMAGING_MANAGE);
        try {
            service.createReport(patient, "T", "texte", Collections.<ReportImageLink>emptyList());
            fail("expected a report with no image to be rejected");
        } catch (APIException expected) {
            assertTrue(expected.getMessage().toLowerCase().contains("image"));
        }
    }

    @Test
    public void rp9_theSameStudyCanBeCoveredBySeveralReports() {
        loginAs(author, MedreportPrivileges.APP_IMAGING_MANAGE);

        ImageReport first = service.createReport(patient, "Lecture initiale", "A.", images("study-1"));
        ImageReport second = service.createReport(patient, "Contrôle", "B.", images("study-1"));

        assertFalse(first.getUuid().equals(second.getUuid()));
        assertEquals("study-1", first.getCurrentVersion().getImages().get(0).getOrthancStudyUid());
        assertEquals("study-1", second.getCurrentVersion().getImages().get(0).getOrthancStudyUid());

        // Two distinct reports now cover the same study - the other direction of the
        // many-to-many. (Each create saves its report twice: once to obtain an id, then
        // again to point at its first version, so assert on identity, not on call count.)
        ArgumentCaptor<ImageReport> saved = ArgumentCaptor.forClass(ImageReport.class);
        verify(dao, atLeastOnce()).saveReport(saved.capture());
        java.util.Set<String> uuids = new java.util.HashSet<String>();
        for (ImageReport report : saved.getAllValues()) {
            uuids.add(report.getUuid());
        }
        assertEquals(2, uuids.size());
    }

    // ==================================================================
    // RP6 - the UI-facing capability flags agree with the enforced rules
    // ==================================================================

    @Test
    public void rp6_capabilityFlagsMatchWhatTheServiceActuallyEnforces() {
        ImageReport report = existingReport(author, false);

        loginAs(author, MedreportPrivileges.APP_IMAGING_MANAGE);
        assertTrue("the author with manage may edit", service.canEdit(report));
        assertTrue("the author with manage may remove", service.canRemove(report));
        assertFalse("the author is not an administrator", service.canViewHistory());

        loginAs(other, MedreportPrivileges.APP_IMAGING_MANAGE);
        assertFalse("another user may not edit", service.canEdit(report));
        assertFalse("another user may not remove", service.canRemove(report));

        loginAs(author, MedreportPrivileges.APP_IMAGING_VIEW);
        assertFalse("the author without manage may not edit", service.canEdit(report));

        loginAs(other, MedreportPrivileges.APP_ADMIN);
        assertTrue("an administrator may browse history", service.canViewHistory());
    }

    @Test
    public void rp6_aRemovedReportIsNotEditableEvenByItsAuthor() {
        ImageReport report = existingReport(author, true);
        loginAs(author, MedreportPrivileges.APP_IMAGING_MANAGE);
        assertFalse(service.canEdit(report));
        assertFalse(service.canRemove(report));
    }
}
