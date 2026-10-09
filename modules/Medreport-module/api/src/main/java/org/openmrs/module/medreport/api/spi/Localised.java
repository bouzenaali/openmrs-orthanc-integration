package org.openmrs.module.medreport.api.spi;

import java.util.Map;

/**
 * Language lookup shared by every descriptor.
 *
 * <p>Contributors supply labels as a {@code {"fr": …, "en": …, "ar": …}} map rather than a
 * message-bundle key. A key would have to resolve inside <em>medreport's</em> bundle, which
 * would mean every contributing module shipping translations into this module's resource
 * files - exactly the coupling the manifest design avoids.
 */
public final class Localised {

    private Localised() {
    }

    /**
     * @return the label for {@code language}, else French, else any other language present,
     *         else {@code fallback}. Never null, so a missing translation degrades to a
     *         readable label instead of an empty cell in the document.
     */
    public static String pick(Map<String, String> labels, String language, String fallback) {
        if (labels == null || labels.isEmpty()) {
            return fallback;
        }
        String value = language != null ? labels.get(language) : null;
        if (isBlank(value)) {
            value = labels.get("fr");
        }
        if (isBlank(value)) {
            for (String candidate : labels.values()) {
                if (!isBlank(candidate)) {
                    value = candidate;
                    break;
                }
            }
        }
        return isBlank(value) ? fallback : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
