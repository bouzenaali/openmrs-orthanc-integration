package org.openmrs.module.medreport.api.render;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.api.context.Context;
import org.openmrs.module.medreport.MedreportConstants;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP client for the Report Generation Service.
 *
 * <p>Deliberately built on {@code HttpURLConnection} rather than Apache HttpClient or
 * OkHttp. OpenMRS modules share one classloader hierarchy with every other installed module,
 * and adding an HTTP library is a well-known way to collide with whatever version another
 * module already loaded. The JDK client is always present, always the same version, and this
 * client makes a handful of simple calls where a richer API buys nothing.
 *
 * <p>Configuration is read per call rather than cached, so an administrator changing the
 * service URL or rotating the shared token in the global-property screen takes effect
 * immediately, without restarting OpenMRS.
 */
public class ReportRenderClient {

    private static final Log log = LogFactory.getLog(ReportRenderClient.class);

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** Refuse to stream a document larger than this into memory. */
    private static final int MAX_DOCUMENT_BYTES = 64 * 1024 * 1024;

    // ------------------------------------------------------------------
    // Configuration
    // ------------------------------------------------------------------

    private String baseUrl() {
        String url = Context.getAdministrationService().getGlobalProperty(
                MedreportConstants.GP_RENDER_BASE_URL, MedreportConstants.GP_RENDER_BASE_URL_DEFAULT);
        if (url == null || url.trim().isEmpty()) {
            url = MedreportConstants.GP_RENDER_BASE_URL_DEFAULT;
        }
        url = url.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        String lower = url.toLowerCase();
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw new RenderException("Global property " + MedreportConstants.GP_RENDER_BASE_URL
                    + " must be an http:// or https:// URL, but is '" + url + "'.");
        }
        return url;
    }

    private String token() {
        String token = Context.getAdministrationService()
                .getGlobalProperty(MedreportConstants.GP_RENDER_TOKEN, "");
        return token != null ? token.trim() : "";
    }

    private int timeoutMillis() {
        String raw = Context.getAdministrationService()
                .getGlobalProperty(MedreportConstants.GP_RENDER_TIMEOUT_SECONDS, "");
        int seconds = MedreportConstants.RENDER_TIMEOUT_SECONDS_DEFAULT;
        try {
            if (raw != null && !raw.trim().isEmpty()) {
                seconds = Integer.parseInt(raw.trim());
            }
        } catch (NumberFormatException e) {
            log.warn("medreport: " + MedreportConstants.GP_RENDER_TIMEOUT_SECONDS
                    + " is not a number ('" + raw + "'); using " + seconds + "s.");
        }
        return Math.max(5, seconds) * 1000;
    }

    // ------------------------------------------------------------------
    // Operations
    // ------------------------------------------------------------------

    /**
     * Render a document.
     *
     * @param template      registered template profile id
     * @param format        docx | pdf | html | odt
     * @param filenameHint  sanitised by the service; safe to pass a patient name
     */
    public RenderedDocument render(DocumentContext context, String template, String format,
                                   boolean includePreview, String filenameHint) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("template", template);
        body.put("format", format);
        body.put("include_preview", includePreview);
        if (filenameHint != null && !filenameHint.trim().isEmpty()) {
            body.put("filename_hint", filenameHint);
        }
        body.set("document", MAPPER.valueToTree(context));

        JsonNode response = postJson("/render", body);

        RenderedDocument document = new RenderedDocument();
        document.setArtifactId(text(response, "artifact_id"));
        document.setFilename(text(response, "filename"));
        document.setFormat(text(response, "format"));
        document.setSizeBytes(response.path("size_bytes").asLong(0));
        document.setPreviewFormat(text(response, "preview_format"));
        document.setPreviewAvailable(response.hasNonNull("preview_url"));
        document.setPdfAvailable(response.path("pdf_available").asBoolean(false));
        document.setExpiresAt(text(response, "expires_at"));

        List<String> warnings = new ArrayList<String>();
        JsonNode warningsNode = response.path("warnings");
        if (warningsNode.isArray()) {
            for (JsonNode warning : warningsNode) {
                warnings.add(warning.asText());
            }
        }
        document.setWarnings(warnings);
        return document;
    }

    public byte[] download(String artifactId) {
        return getBinary("/artifacts/" + encodeSegment(artifactId) + "/download");
    }

    public byte[] preview(String artifactId) {
        return getBinary("/artifacts/" + encodeSegment(artifactId) + "/preview");
    }

    /**
     * Drop an artifact as soon as the authoritative copy has been persisted in OpenMRS,
     * rather than leaving rendered PHI sitting in the service until its TTL expires.
     * Best-effort: a failure here is logged, never propagated.
     */
    public void discard(String artifactId) {
        if (artifactId == null || artifactId.trim().isEmpty()) {
            return;
        }
        try {
            request("DELETE", "/artifacts/" + encodeSegment(artifactId), null, false);
        } catch (Exception e) {
            log.warn("medreport: could not discard render artifact " + artifactId
                    + "; it will expire on its own.", e);
        }
    }

    /** Template profiles offered by the rendering service, for the personalisation window. */
    public List<Map<String, Object>> listTemplates() {
        List<Map<String, Object>> templates = new ArrayList<Map<String, Object>>();
        JsonNode response = getJson("/templates");
        for (JsonNode node : response.path("templates")) {
            Map<String, Object> template = new LinkedHashMap<String, Object>();
            template.put("id", node.path("id").asText());
            template.put("label", toStringMap(node.path("label")));
            template.put("description", toStringMap(node.path("description")));
            template.put("engine", node.path("engine").asText("builtin"));
            template.put("builtin", node.path("builtin").asBoolean(false));
            template.put("accent", node.path("accent").asText(null));
            List<String> languages = new ArrayList<String>();
            for (JsonNode language : node.path("languages")) {
                languages.add(language.asText());
            }
            template.put("languages", languages);
            templates.add(template);
        }
        return templates;
    }

    /** Capability probe used by the settings screen and by graceful degradation in the UI. */
    public Map<String, Object> health() {
        Map<String, Object> health = new LinkedHashMap<String, Object>();
        try {
            JsonNode response = getJson("/health");
            health.put("reachable", true);
            health.put("status", response.path("status").asText("unknown"));
            health.put("version", response.path("version").asText(""));
            health.put("pdfAvailable", response.path("pdf_available").asBoolean(false));
            health.put("templates", response.path("templates").asInt(0));
            List<String> formats = new ArrayList<String>();
            for (JsonNode format : response.path("formats")) {
                formats.add(format.asText());
            }
            health.put("formats", formats);
        } catch (Exception e) {
            health.put("reachable", false);
            health.put("error", e.getMessage());
        }
        return health;
    }

    // ------------------------------------------------------------------
    // Transport
    // ------------------------------------------------------------------

    private JsonNode postJson(String path, JsonNode body) {
        byte[] payload = request("POST", path, body, true);
        return parse(payload, path);
    }

    private JsonNode getJson(String path) {
        byte[] payload = request("GET", path, null, true);
        return parse(payload, path);
    }

    private byte[] getBinary(String path) {
        return request("GET", path, null, true);
    }

    private JsonNode parse(byte[] payload, String path) {
        try {
            return MAPPER.readTree(payload);
        } catch (IOException e) {
            throw new RenderException("Report Generation Service returned a malformed response "
                    + "for " + path + ".", e);
        }
    }

    private byte[] request(String method, String path, JsonNode body, boolean expectResponse) {
        String token = token();
        if (token.isEmpty() && !"/health".equals(path)) {
            // Fail closed with an actionable message rather than an opaque 401 from the
            // service: an unconfigured token is an install step, not a user error.
            throw new RenderException("The Report Generation Service token is not configured. "
                    + "Set the global property " + MedreportConstants.GP_RENDER_TOKEN
                    + " to the same value as the service's MEDREPORT_RGS_TOKEN.");
        }

        HttpURLConnection connection = null;
        try {
            URL url = new URL(baseUrl() + path);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(Math.min(timeoutMillis(), 15000));
            connection.setReadTimeout(timeoutMillis());
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json, */*");
            if (!token.isEmpty()) {
                connection.setRequestProperty("X-Medreport-Token", token);
            }

            if (body != null) {
                byte[] payload = MAPPER.writeValueAsBytes(body);
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(payload.length);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                OutputStream out = connection.getOutputStream();
                try {
                    out.write(payload);
                    out.flush();
                } finally {
                    out.close();
                }
            }

            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new RenderException(describeError(connection, status, path));
            }
            if (!expectResponse) {
                return new byte[0];
            }
            return readAll(connection.getInputStream());
        } catch (RenderException e) {
            throw e;
        } catch (IOException e) {
            throw new RenderException("Could not reach the Report Generation Service at "
                    + safeBaseUrl() + path + ". Check that the service is running and that "
                    + MedreportConstants.GP_RENDER_BASE_URL + " is correct.", e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String describeError(HttpURLConnection connection, int status, String path) {
        String detail = "";
        try {
            InputStream errorStream = connection.getErrorStream();
            if (errorStream != null) {
                String raw = new String(readAll(errorStream), UTF8);
                try {
                    JsonNode node = MAPPER.readTree(raw);
                    detail = node.path("detail").asText(raw);
                } catch (IOException notJson) {
                    detail = raw;
                }
            }
        } catch (IOException ignored) {
            // the status code alone is still a useful message
        }
        if (detail.length() > 500) {
            detail = detail.substring(0, 500) + "…";
        }
        if (status == 401) {
            return "The Report Generation Service rejected our token (401). Global property "
                    + MedreportConstants.GP_RENDER_TOKEN + " must match the service's "
                    + "MEDREPORT_RGS_TOKEN.";
        }
        return "Report Generation Service returned HTTP " + status + " for " + path
                + (detail.isEmpty() ? "." : ": " + detail);
    }

    private String safeBaseUrl() {
        try {
            return baseUrl();
        } catch (Exception e) {
            return "(unconfigured)";
        }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        if (input == null) {
            return new byte[0];
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        int total = 0;
        try {
            while ((read = input.read(chunk)) != -1) {
                total += read;
                if (total > MAX_DOCUMENT_BYTES) {
                    throw new IOException("Response exceeded " + MAX_DOCUMENT_BYTES + " bytes.");
                }
                buffer.write(chunk, 0, read);
            }
        } finally {
            input.close();
        }
        return buffer.toByteArray();
    }

    /**
     * Artifact ids are service-generated 32-hex strings; anything else is rejected here
     * rather than concatenated into a URL.
     */
    private static String encodeSegment(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{32}")) {
            throw new RenderException("Invalid render artifact id.");
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNull() || value.isMissingNode() ? null : value.asText();
    }

    private static Map<String, String> toStringMap(JsonNode node) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            map.put(entry.getKey(), entry.getValue().asText());
        }
        return map;
    }
}
