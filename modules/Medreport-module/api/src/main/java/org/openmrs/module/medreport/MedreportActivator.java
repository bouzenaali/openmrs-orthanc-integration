package org.openmrs.module.medreport;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.Concept;
import org.openmrs.ConceptClass;
import org.openmrs.ConceptComplex;
import org.openmrs.ConceptDatatype;
import org.openmrs.ConceptName;
import org.openmrs.EncounterType;
import org.openmrs.api.context.Context;
import org.openmrs.module.BaseModuleActivator;
import org.openmrs.module.medreport.api.catalog.DataSourceRegistry;
import org.openmrs.module.medreport.api.obs.MedreportComplexObsHandler;
import org.openmrs.util.PrivilegeConstants;

import java.util.Locale;

/**
 * Module lifecycle.
 *
 * <p>Everything done here is idempotent and self-healing: a fresh install needs no manual
 * dictionary or administration work before the first report can be written, and a restart
 * repairs anything an administrator removed by accident.
 */
public class MedreportActivator extends BaseModuleActivator {

    private static final Log log = LogFactory.getLog(MedreportActivator.class);

    @Override
    public void started() {
        log.info("Starting medreport module");
        registerComplexObsHandler();
        ensureDocumentConcept();
        ensureEncounterType();
        refreshDataSources();
        log.info("medreport module started");
    }

    @Override
    public void stopped() {
        log.info("medreport module stopped");
    }

    /**
     * Register the handler that writes report documents into OpenMRS's complex-obs
     * directory. Re-registering on every start is deliberate: the map lives in the running
     * ObsService, so a module restart without this leaves stored documents unreadable.
     */
    private void registerComplexObsHandler() {
        try {
            Context.getObsService().registerHandler(
                    MedreportConstants.COMPLEX_OBS_HANDLER, new MedreportComplexObsHandler());
            log.info("medreport: registered complex obs handler '"
                    + MedreportConstants.COMPLEX_OBS_HANDLER + "'");
        } catch (Exception e) {
            log.error("medreport: could not register the complex obs handler. Report documents "
                    + "will not be storable until this is resolved.", e);
        }
    }

    /**
     * Create the Complex-datatype concept the rendered .docx hangs off, if it is not there.
     * Without it the module would need a documented manual dictionary step before first use.
     */
    private void ensureDocumentConcept() {
        Context.addProxyPrivilege(PrivilegeConstants.GET_CONCEPTS);
        Context.addProxyPrivilege(PrivilegeConstants.MANAGE_CONCEPTS);
        try {
            String uuid = Context.getAdministrationService().getGlobalProperty(
                    MedreportConstants.GP_IMAGE_REPORT_CONCEPT_UUID,
                    MedreportConstants.IMAGE_REPORT_CONCEPT_UUID);
            Concept existing = Context.getConceptService().getConceptByUuid(uuid);
            if (existing != null) {
                return;
            }

            ConceptDatatype complex = Context.getConceptService().getConceptDatatypeByName("Complex");
            if (complex == null) {
                log.error("medreport: OpenMRS has no 'Complex' concept datatype; "
                        + "cannot create the report-document concept.");
                return;
            }
            ConceptClass conceptClass =
                    Context.getConceptService().getConceptClassByName("Medical record observation");
            if (conceptClass == null) {
                conceptClass = Context.getConceptService().getConceptClassByName("Misc");
            }
            if (conceptClass == null) {
                log.error("medreport: no suitable concept class found; "
                        + "cannot create the report-document concept.");
                return;
            }

            // ConceptComplex, not Concept: only the complex subclass carries the handler
            // name, which is how OpenMRS routes an obs on this concept to our handler.
            ConceptComplex concept = new ConceptComplex();
            concept.setUuid(uuid);
            concept.setDatatype(complex);
            concept.setConceptClass(conceptClass);
            concept.addName(new ConceptName("Medreport Imaging Report Document", Locale.ENGLISH));
            concept.setHandler(MedreportConstants.COMPLEX_OBS_HANDLER);
            Context.getConceptService().saveConcept(concept);
            log.info("medreport: created the report-document concept " + uuid);
        } catch (Exception e) {
            log.error("medreport: could not create the report-document concept. "
                    + "Reports will save their text but not their rendered document.", e);
        } finally {
            Context.removeProxyPrivilege(PrivilegeConstants.MANAGE_CONCEPTS);
            Context.removeProxyPrivilege(PrivilegeConstants.GET_CONCEPTS);
        }
    }

    /** Encounter type reports are filed under, so each report is tied to an encounter (ADR-6). */
    private void ensureEncounterType() {
        Context.addProxyPrivilege(PrivilegeConstants.GET_ENCOUNTER_TYPES);
        Context.addProxyPrivilege(PrivilegeConstants.MANAGE_ENCOUNTER_TYPES);
        try {
            String uuid = Context.getAdministrationService().getGlobalProperty(
                    MedreportConstants.GP_IMAGE_REPORT_ENCOUNTER_TYPE_UUID,
                    MedreportConstants.IMAGE_REPORT_ENCOUNTER_TYPE_UUID);
            if (Context.getEncounterService().getEncounterTypeByUuid(uuid) != null) {
                return;
            }
            EncounterType type = new EncounterType();
            type.setUuid(uuid);
            type.setName("Medreport Imaging Report");
            type.setDescription("Encounter recording a radiology/surgery observation report "
                    + "written against a DICOM study.");
            Context.getEncounterService().saveEncounterType(type);
            log.info("medreport: created encounter type " + uuid);
        } catch (Exception e) {
            // Not fatal: reports still work, their obs is simply patient-level.
            log.warn("medreport: could not create the report encounter type; "
                    + "reports will not be tied to an encounter.", e);
        } finally {
            Context.removeProxyPrivilege(PrivilegeConstants.MANAGE_ENCOUNTER_TYPES);
            Context.removeProxyPrivilege(PrivilegeConstants.GET_ENCOUNTER_TYPES);
        }
    }

    /**
     * Rescan for contributing modules. Necessary because module start order is not fixed: a
     * contributor may have started before medreport, or may start later, and the cached
     * catalogue must not be a snapshot of whichever happened first.
     */
    private void refreshDataSources() {
        try {
            for (DataSourceRegistry registry
                    : Context.getRegisteredComponents(DataSourceRegistry.class)) {
                registry.refresh();
                log.info("medreport: clinical data sources: " + registry.getDescriptors().size());
            }
        } catch (Exception e) {
            log.warn("medreport: could not refresh the clinical data source registry.", e);
        }
    }
}
