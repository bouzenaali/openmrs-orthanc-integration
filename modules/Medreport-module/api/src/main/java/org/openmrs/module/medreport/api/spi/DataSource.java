package org.openmrs.module.medreport.api.spi;

import java.util.ArrayList;
import java.util.List;

/**
 * Where a section's values come from, expressed without either module knowing the other's
 * Java types.
 *
 * <p>The contributor names an OpenMRS <em>service interface</em> and a method on it. medreport
 * resolves the class through OpenMRS's own classloader, fetches the service from the service
 * registry, and invokes the method reflectively. The method must take a {@code Patient} (and
 * optionally an {@code int} limit) and return either a {@code Map<String, Object>} or a
 * {@code List<Map<String, Object>>} - JDK types only.
 *
 * <p>That constraint is the whole trick. Because the contract is expressed in JDK types,
 * medreport needs no compile-time dependency on the contributor, and the contributor needs no
 * compile-time dependency on medreport. Nothing is shared but a JSON file and the shape of a
 * Map. It also happens to be the shape {@code patientview} already returns from every one of
 * its service methods, so contributing cost that module a single resource file and no code.
 *
 * <p>Calling the service through {@code Context.getService(...)} rather than a DAO is also
 * what keeps authorization honest: the contributor's own {@code @Authorized} advice still
 * runs, so medreport cannot be used as a way around a contributor's access rules.
 */
public class DataSource {

    /** Fully-qualified OpenMRS service interface, e.g. {@code org.openmrs.…PatientviewService}. */
    private String serviceClass;

    private String method;

    /**
     * Argument recipe. Supported tokens: {@code patient}, {@code limit:<n>}.
     * Empty means the method takes the patient alone.
     */
    private List<String> args = new ArrayList<String>();

    public String getServiceClass() {
        return serviceClass;
    }

    public void setServiceClass(String serviceClass) {
        this.serviceClass = serviceClass;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public List<String> getArgs() {
        return args;
    }

    public void setArgs(List<String> args) {
        this.args = args != null ? args : new ArrayList<String>();
    }

    public boolean isUsable() {
        return serviceClass != null && !serviceClass.trim().isEmpty()
                && method != null && !method.trim().isEmpty();
    }
}
