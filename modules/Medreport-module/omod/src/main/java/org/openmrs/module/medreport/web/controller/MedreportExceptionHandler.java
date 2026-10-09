package org.openmrs.module.medreport.web.controller;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.APIException;
import org.openmrs.module.medreport.api.render.RenderException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.HashMap;
import java.util.Map;

/**
 * Turns the exceptions this module throws into responses the UI can act on.
 *
 * <p>The three cases are kept distinct on purpose. Telling a clinician "you lack permission"
 * when in fact the rendering container is down sends them to the wrong person; telling them
 * "service unavailable" when they genuinely lack a privilege hides a real access decision.
 *
 * <p>The 403 message is deliberately generic. Which privilege was missing, and whose report
 * they tried to touch, goes to the audit log and the server log - not to the browser, where
 * it would confirm that a given report exists.
 */
@ControllerAdvice(basePackages = "org.openmrs.module.medreport.web.controller")
public class MedreportExceptionHandler {

    private static final Log log = LogFactory.getLog(MedreportExceptionHandler.class);

    @ExceptionHandler(APIAuthenticationException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    @ResponseBody
    public Map<String, Object> handleAuthorizationFailure(APIAuthenticationException e) {
        log.info("medreport: refused an action - " + e.getMessage());
        return body("Vous n'avez pas le privilège requis pour cette action.", "forbidden");
    }

    /** The rendering service is down, unreachable, or not configured. */
    @ExceptionHandler(RenderException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    @ResponseBody
    public Map<String, Object> handleRenderFailure(RenderException e) {
        log.error("medreport: report rendering failed.", e);
        return body(e.getMessage(), "renderUnavailable");
    }

    /** Validation and business-rule failures - safe to show verbatim. */
    @ExceptionHandler(APIException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ResponseBody
    public Map<String, Object> handleApiFailure(APIException e) {
        log.warn("medreport: rejected a request - " + e.getMessage());
        return body(e.getMessage(), "invalid");
    }

    private Map<String, Object> body(String message, String code) {
        Map<String, Object> response = new HashMap<String, Object>();
        response.put("success", false);
        response.put("code", code);
        response.put("message", message);
        return response;
    }
}
