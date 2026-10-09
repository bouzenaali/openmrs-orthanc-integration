package org.openmrs.module.medreport.web.controller;

import org.openmrs.module.medreport.MedreportRequestContext;

import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

/**
 * Shared plumbing for medreport's REST controllers.
 *
 * <p>Its one real job is making sure the caller's IP reaches the audit log and is then
 * cleared. Application servers pool request threads, so a value left in the thread-local
 * would be attributed to whoever gets that thread next - an audit trail that misattributes
 * is worse than one with a blank column. Every handler therefore wraps its work in
 * {@link #begin}/{@link #end}.
 */
public abstract class MedreportBaseController {

    protected void begin(HttpServletRequest request) {
        MedreportRequestContext.setClientIp(clientIp(request));
    }

    protected void end() {
        MedreportRequestContext.clear();
    }

    /**
     * Prefer {@code X-Forwarded-For}'s first hop, since the deployment puts nginx in front of
     * OpenMRS and the socket address would otherwise always be the proxy. The header is
     * client-controllable, so this is a best-effort attribution for an audit trail, never an
     * access-control input.
     */
    private String clientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.trim().isEmpty()) {
            String first = forwarded.split(",")[0].trim();
            if (!first.isEmpty()) {
                return truncate(first);
            }
        }
        String real = request.getHeader("X-Real-IP");
        if (real != null && !real.trim().isEmpty()) {
            return truncate(real.trim());
        }
        return truncate(request.getRemoteAddr());
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 64 ? value : value.substring(0, 64);
    }

    protected Map<String, Object> ok() {
        Map<String, Object> response = new HashMap<String, Object>();
        response.put("success", true);
        return response;
    }

    protected Map<String, Object> fail(String message) {
        Map<String, Object> response = new HashMap<String, Object>();
        response.put("success", false);
        response.put("message", message);
        return response;
    }
}
