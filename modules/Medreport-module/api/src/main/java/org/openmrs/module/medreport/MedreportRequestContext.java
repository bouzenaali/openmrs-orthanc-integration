package org.openmrs.module.medreport;

/**
 * Carries the caller's IP address from the web layer down to the audit log.
 *
 * <p>The service layer has no access to {@code HttpServletRequest}, and threading one through
 * every service signature would put a web concern into the API for the sake of one audit
 * column. A thread-local set by the web layer keeps the signatures clean.
 *
 * <p>The web layer must always {@link #clear()} in a {@code finally}: application servers
 * pool threads, so a value left behind would be attributed to whoever gets that thread next -
 * an audit trail that lies is worse than one with a missing field.
 */
public final class MedreportRequestContext {

    private static final ThreadLocal<String> CLIENT_IP = new ThreadLocal<String>();

    private MedreportRequestContext() {
    }

    public static void setClientIp(String ip) {
        CLIENT_IP.set(ip);
    }

    public static String getClientIp() {
        return CLIENT_IP.get();
    }

    public static void clear() {
        CLIENT_IP.remove();
    }
}
