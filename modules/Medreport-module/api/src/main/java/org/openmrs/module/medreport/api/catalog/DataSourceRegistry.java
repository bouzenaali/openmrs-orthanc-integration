package org.openmrs.module.medreport.api.catalog;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.Module;
import org.openmrs.module.ModuleFactory;
import org.openmrs.module.medreport.api.spi.ClinicalDataContributor;
import org.openmrs.module.medreport.api.spi.DataSource;
import org.openmrs.module.medreport.api.spi.DataSourceDescriptor;
import org.openmrs.module.medreport.api.spi.SectionDescriptor;
import org.openmrs.util.OpenmrsClassLoader;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Discovers what clinical data is available to put in a report, and fetches it - without
 * medreport having any compile-time knowledge of the modules that hold it.
 *
 * <h3>Discovery</h3>
 * Two sources, merged:
 * <ol>
 *   <li>Every started OpenMRS module whose classpath contains
 *       {@value #MANIFEST_RESOURCE}. The manifest declares the module's sets, fields,
 *       privileges, and which service method returns each set's values.</li>
 *   <li>Every Spring bean implementing {@link ClinicalDataContributor}, for contributors that
 *       need to compute their catalogue rather than declare it.</li>
 * </ol>
 *
 * <h3>Why a manifest rather than a Java interface</h3>
 * Making {@code patientview} implement a medreport interface would force it to depend on
 * medreport - inverting the intended direction and making the clinical record module unable
 * to start without the reporting module. Scanning for a JSON resource instead means the
 * contributor declares itself with a single file, keeps working unchanged when medreport is
 * absent, and shares no Java type with it. The only contract is the manifest schema and the
 * fact that the named service method returns {@code Map<String, Object>} - JDK types on both
 * sides of the boundary.
 *
 * <h3>Failure isolation</h3>
 * A contributor that is broken, half-upgraded, or throwing must not take report generation
 * with it. Every discovery and resolution step is individually guarded: a bad manifest is
 * logged and skipped, a failing service call yields no data for that section, and the rest
 * of the report still renders.
 */
public class DataSourceRegistry {

    private static final Log log = LogFactory.getLog(DataSourceRegistry.class);

    /** Classpath resource a contributing module ships to announce its data. */
    public static final String MANIFEST_RESOURCE = "medreport-datasource.json";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * Cached catalogue. Rebuilt on demand rather than per request: discovery walks every
     * started module's classloader, which is far too expensive to repeat on each dashboard
     * open. Invalidated by {@link #refresh()} when the module set changes.
     */
    private volatile List<DataSourceDescriptor> cache;

    // ------------------------------------------------------------------
    // Discovery
    // ------------------------------------------------------------------

    public List<DataSourceDescriptor> getDescriptors() {
        List<DataSourceDescriptor> current = cache;
        if (current == null) {
            synchronized (this) {
                current = cache;
                if (current == null) {
                    current = discover();
                    cache = current;
                }
            }
        }
        return current;
    }

    /** Drop the cached catalogue; the next read rediscovers. */
    public void refresh() {
        cache = null;
    }

    private List<DataSourceDescriptor> discover() {
        Map<String, DataSourceDescriptor> byId = new LinkedHashMap<String, DataSourceDescriptor>();

        for (DataSourceDescriptor descriptor : discoverFromManifests()) {
            byId.put(descriptor.getId(), descriptor);
        }
        // Typed contributors win on id collision: a module that went to the trouble of
        // implementing the interface meant to override its own static manifest.
        for (DataSourceDescriptor descriptor : discoverFromBeans()) {
            byId.put(descriptor.getId(), descriptor);
        }

        List<DataSourceDescriptor> descriptors = new ArrayList<DataSourceDescriptor>(byId.values());
        Collections.sort(descriptors, new Comparator<DataSourceDescriptor>() {
            public int compare(DataSourceDescriptor a, DataSourceDescriptor b) {
                return Integer.compare(a.getSortWeight(), b.getSortWeight());
            }
        });
        for (DataSourceDescriptor descriptor : descriptors) {
            sortSections(descriptor.getSections());
        }
        log.info("medreport: discovered " + descriptors.size() + " clinical data source(s): " + byId.keySet());
        return descriptors;
    }

    private void sortSections(List<SectionDescriptor> sections) {
        Collections.sort(sections, new Comparator<SectionDescriptor>() {
            public int compare(SectionDescriptor a, SectionDescriptor b) {
                return Integer.compare(a.getSortWeight(), b.getSortWeight());
            }
        });
        for (SectionDescriptor section : sections) {
            sortSections(section.getSubsections());
        }
    }

    private List<DataSourceDescriptor> discoverFromManifests() {
        List<DataSourceDescriptor> found = new ArrayList<DataSourceDescriptor>();
        Collection<Module> modules;
        try {
            modules = new ArrayList<Module>(ModuleFactory.getStartedModules());
        } catch (Exception e) {
            log.warn("medreport: could not enumerate started modules; "
                    + "no manifest-based data sources will be available.", e);
            return found;
        }

        for (Module module : modules) {
            InputStream stream = null;
            try {
                ClassLoader loader = ModuleFactory.getModuleClassLoader(module);
                if (loader == null) {
                    continue;
                }
                stream = loader.getResourceAsStream(MANIFEST_RESOURCE);
                if (stream == null) {
                    continue;
                }
                DataSourceDescriptor descriptor = MAPPER.readValue(stream, DataSourceDescriptor.class);
                if (descriptor.getId() == null || descriptor.getId().trim().isEmpty()) {
                    descriptor.setId(module.getModuleId());
                }
                descriptor.setModuleId(module.getModuleId());
                found.add(descriptor);
                log.info("medreport: loaded data source '" + descriptor.getId()
                        + "' from module '" + module.getModuleId() + "'");
            } catch (Exception e) {
                // A malformed manifest disables that contributor, nothing else.
                log.error("medreport: ignoring unreadable " + MANIFEST_RESOURCE
                        + " in module '" + module.getModuleId() + "'", e);
            } finally {
                closeQuietly(stream);
            }
        }
        return found;
    }

    private List<DataSourceDescriptor> discoverFromBeans() {
        List<DataSourceDescriptor> found = new ArrayList<DataSourceDescriptor>();
        List<ClinicalDataContributor> contributors;
        try {
            contributors = Context.getRegisteredComponents(ClinicalDataContributor.class);
        } catch (Exception e) {
            log.warn("medreport: could not look up ClinicalDataContributor beans.", e);
            return found;
        }
        for (ClinicalDataContributor contributor : contributors) {
            try {
                DataSourceDescriptor descriptor = contributor.describe();
                if (descriptor != null && descriptor.getId() != null) {
                    found.add(descriptor);
                }
            } catch (Exception e) {
                log.error("medreport: contributor " + contributor.getClass().getName()
                        + " failed to describe itself; skipping it.", e);
            }
        }
        return found;
    }

    // ------------------------------------------------------------------
    // Resolution
    // ------------------------------------------------------------------

    /**
     * Fetch the values for one section.
     *
     * <p>Never throws: a contributor that is unavailable or failing yields an empty result so
     * the rest of the report still renders. Returns one element for a flat section and one
     * per occurrence for a repeating one.
     */
    public List<Map<String, Object>> resolve(DataSourceDescriptor descriptor,
                                             SectionDescriptor section,
                                             Patient patient) {
        if (patient == null || section == null) {
            return Collections.emptyList();
        }
        try {
            ClinicalDataContributor bean = findContributorBean(descriptor);
            if (bean != null) {
                List<Map<String, Object>> resolved = bean.resolve(section.getId(), patient);
                return resolved != null ? resolved : Collections.<Map<String, Object>>emptyList();
            }
            return resolveReflectively(section, patient);
        } catch (Exception e) {
            log.error("medreport: could not resolve section '" + section.getId()
                    + "' of data source '" + (descriptor != null ? descriptor.getId() : "?")
                    + "'; it will be omitted from the report.", e);
            return Collections.emptyList();
        }
    }

    private ClinicalDataContributor findContributorBean(DataSourceDescriptor descriptor) {
        if (descriptor == null) {
            return null;
        }
        try {
            for (ClinicalDataContributor contributor
                    : Context.getRegisteredComponents(ClinicalDataContributor.class)) {
                DataSourceDescriptor described = contributor.describe();
                if (described != null && descriptor.getId().equals(described.getId())) {
                    return contributor;
                }
            }
        } catch (Exception e) {
            log.debug("medreport: no typed contributor for '" + descriptor.getId() + "'", e);
        }
        return null;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private List<Map<String, Object>> resolveReflectively(SectionDescriptor section, Patient patient)
            throws Exception {
        DataSource source = section.getSource();
        if (source == null || !source.isUsable()) {
            return Collections.emptyList();
        }

        // OpenmrsClassLoader delegates across every module classloader, so this resolves a
        // contributor's interface without medreport having it on its own classpath.
        Class<?> serviceClass = OpenmrsClassLoader.getInstance().loadClass(source.getServiceClass());

        // Going through the service registry (rather than a DAO) keeps the contributor's own
        // @Authorized advice in the call path - medreport can never be a way around it.
        Object service = Context.getService((Class) serviceClass);
        if (service == null) {
            return Collections.emptyList();
        }

        List<Class<?>> parameterTypes = new ArrayList<Class<?>>();
        List<Object> arguments = new ArrayList<Object>();
        parameterTypes.add(Patient.class);
        arguments.add(patient);

        for (String arg : source.getArgs()) {
            if (arg == null) {
                continue;
            }
            String token = arg.trim();
            if (token.equalsIgnoreCase("patient")) {
                continue; // the patient is always the first argument
            }
            if (token.toLowerCase().startsWith("limit:")) {
                parameterTypes.add(int.class);
                arguments.add(parseLimit(token.substring("limit:".length()), section.getRecordLimit()));
            }
        }

        Method method = serviceClass.getMethod(source.getMethod(),
                parameterTypes.toArray(new Class<?>[0]));
        Object result = method.invoke(service, arguments.toArray());
        return normalise(result, section);
    }

    private int parseLimit(String raw, int fallback) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback > 0 ? fallback : 20;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> normalise(Object result, SectionDescriptor section) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        if (result == null) {
            return rows;
        }
        if (result instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) result;
            if (!map.isEmpty()) {
                rows.add(map);
            }
            return rows;
        }
        if (result instanceof List) {
            for (Object element : (List<Object>) result) {
                if (element instanceof Map) {
                    rows.add((Map<String, Object>) element);
                }
            }
            int limit = section.getRecordLimit();
            if (limit > 0 && rows.size() > limit) {
                return new ArrayList<Map<String, Object>>(rows.subList(0, limit));
            }
            return rows;
        }
        log.warn("medreport: section '" + section.getId() + "' resolved to "
                + result.getClass().getName() + "; expected Map or List<Map>.");
        return rows;
    }

    private static void closeQuietly(InputStream stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch (Exception ignored) {
                // closing a classpath resource stream cannot fail meaningfully
            }
        }
    }
}
