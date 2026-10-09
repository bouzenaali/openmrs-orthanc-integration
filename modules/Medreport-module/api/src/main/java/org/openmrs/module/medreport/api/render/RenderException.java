package org.openmrs.module.medreport.api.render;

import org.openmrs.api.APIException;

/**
 * The Report Generation Service could not be reached, was misconfigured, or refused the
 * request.
 *
 * <p>Distinct from an authorization failure on purpose: the web layer maps this to 502/503
 * with an operator-facing message, whereas a privilege failure is a 403 aimed at the user.
 * Conflating them would tell a clinician they lack permission when in fact a container is
 * down.
 */
public class RenderException extends APIException {

    private static final long serialVersionUID = 1L;

    public RenderException(String message) {
        super(message);
    }

    public RenderException(String message, Throwable cause) {
        super(message, cause);
    }
}
