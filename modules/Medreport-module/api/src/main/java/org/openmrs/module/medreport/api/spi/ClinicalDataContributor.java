package org.openmrs.module.medreport.api.spi;

import org.openmrs.Patient;

import java.util.List;
import java.util.Map;

/**
 * Optional typed alternative to the {@code medreport-datasource.json} manifest.
 *
 * <p>Two ways exist to contribute data on purpose, and they suit different modules:
 *
 * <ul>
 *   <li><b>Manifest (preferred).</b> A JSON resource on the contributor's classpath. Zero
 *       compile-time dependency in either direction - the contributor does not depend on
 *       medreport, and works unchanged whether medreport is installed or not. This is how
 *       {@code patientview} contributes, and it cost that module one resource file.</li>
 *   <li><b>This interface.</b> For a contributor that needs to compute sections dynamically,
 *       resolve values itself, or vary the catalogue per patient - things a static manifest
 *       cannot express. The cost is a {@code provided}-scope dependency on {@code
 *       medreport-api} and a hard requirement on medreport being installed.</li>
 * </ul>
 *
 * <p>Implementations are discovered as Spring beans through
 * {@code Context.getRegisteredComponents(ClinicalDataContributor.class)}; declare one in the
 * contributing module's {@code moduleApplicationContext.xml} and it is picked up on startup.
 *
 * <p>Both routes are filtered identically against the current user's privileges before
 * anything reaches the personalisation tree or a document.
 */
public interface ClinicalDataContributor {

    /**
     * Declare what this module offers. Called when the catalogue is built; keep it cheap and
     * free of patient data.
     */
    DataSourceDescriptor describe();

    /**
     * Resolve the values for one section of {@link #describe()}'s output.
     *
     * <p>Return a single-element list for a flat section, one element per occurrence for a
     * repeating one, and an empty list when there is nothing recorded. Keys must match the
     * declared fields' {@code sourceKey}. Values should be plain JDK types.
     *
     * <p>Implementations must apply their own access rules; medreport's filtering is an
     * additional layer, never a substitute for the contributor's own checks.
     */
    List<Map<String, Object>> resolve(String sectionId, Patient patient);
}
