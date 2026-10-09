package org.openmrs.module.medreport.web.controller;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.openmrs.Patient;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.module.medreport.MedreportPrivileges;
import org.openmrs.module.medreport.api.PatientReportService;
import org.openmrs.module.medreport.api.render.RenderedDocument;
import org.openmrs.module.medreport.api.report.ReportRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Use case 2's HTTP surface: the personalisation window, preview and download.
 *
 * <p>The personalisation payload arrives as a JSON string in a form field rather than as a
 * {@code @RequestBody}. OpenMRS's legacy dispatcher is configured per distribution and its
 * message-converter set is not something a module can rely on; a form field parsed with an
 * explicit ObjectMapper works the same way on every install, and matches the {@code .form}
 * POST convention the rest of this deployment already uses.
 */
@Controller
public class PatientReportRestController extends MedreportBaseController {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** A personalisation payload is a few kilobytes of ids; anything larger is not one. */
    private static final int MAX_PAYLOAD_CHARS = 200000;

    private PatientReportService service() {
        return Context.getService(PatientReportService.class);
    }

    // ==================================================================
    // Catalogue and settings
    // ==================================================================

    /**
     * The personalisation tree. Contains only what this user may include - sets they cannot
     * view are absent rather than disabled, so their existence is not disclosed.
     */
    @RequestMapping(value = "/module/medreport/catalog.form", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> catalog(HttpServletRequest request,
                                       @RequestParam(value = "language", required = false) String language) {
        begin(request);
        try {
            MedreportPrivileges.requireDashboardGenerate();
            Map<String, Object> response = ok();
            response.put("catalog", service().getCatalog(language));
            response.put("canViewImaging", MedreportPrivileges.canViewImaging());
            return response;
        } finally {
            end();
        }
    }

    @RequestMapping(value = "/module/medreport/reportTemplates.form", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> templates(HttpServletRequest request) {
        begin(request);
        try {
            MedreportPrivileges.requireDashboardGenerate();
            Map<String, Object> response = ok();
            response.put("templates", service().getTemplates());
            response.put("service", service().getRenderServiceHealth());
            return response;
        } finally {
            end();
        }
    }

    // ==================================================================
    // Preferences
    // ==================================================================

    /** The authenticated user's own saved choices; there is no way to read another user's. */
    @RequestMapping(value = "/module/medreport/reportPreferences.form", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> getPreferences(HttpServletRequest request) {
        begin(request);
        try {
            MedreportPrivileges.requireDashboardGenerate();
            Map<String, Object> response = ok();
            response.put("preferences", service().getPreferences());
            return response;
        } finally {
            end();
        }
    }

    @RequestMapping(value = "/module/medreport/reportPreferences.form", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> savePreferences(HttpServletRequest request,
                                               @RequestParam("payload") String payload) {
        begin(request);
        try {
            MedreportPrivileges.requireDashboardGenerate();
            service().savePreferences(parse(payload));
            return ok();
        } finally {
            end();
        }
    }

    // ==================================================================
    // Generation
    // ==================================================================

    @RequestMapping(value = "/module/medreport/generateReport.form", method = RequestMethod.POST)
    @ResponseBody
    public Map<String, Object> generate(HttpServletRequest request,
                                        @RequestParam("patientId") Integer patientId,
                                        @RequestParam("payload") String payload,
                                        @RequestParam(value = "remember", required = false,
                                                defaultValue = "false") boolean remember) {
        begin(request);
        try {
            MedreportPrivileges.requireDashboardGenerate();

            Patient patient = Context.getPatientService().getPatient(patientId);
            if (patient == null) {
                return fail("Patient introuvable.");
            }

            ReportRequest reportRequest = parse(payload);
            RenderedDocument rendered = service().generate(patient, reportRequest);

            // Persisted only after a successful render, so a request that failed validation
            // does not become the user's saved default.
            if (remember) {
                service().savePreferences(reportRequest);
            }

            Map<String, Object> document = new LinkedHashMap<String, Object>();
            document.put("artifactId", rendered.getArtifactId());
            document.put("filename", rendered.getFilename());
            document.put("format", rendered.getFormat());
            document.put("sizeBytes", rendered.getSizeBytes());
            document.put("previewAvailable", rendered.isPreviewAvailable());
            document.put("previewFormat", rendered.getPreviewFormat());
            document.put("warnings", rendered.getWarnings());

            Map<String, Object> response = ok();
            response.put("document", document);
            return response;
        } finally {
            end();
        }
    }

    /** Inline rendition for the preview pane: a PDF when the host can make one, else HTML. */
    @RequestMapping(value = "/module/medreport/reportPreview.form", method = RequestMethod.GET)
    public ResponseEntity<byte[]> preview(HttpServletRequest request,
                                          @RequestParam("artifactId") String artifactId,
                                          @RequestParam(value = "format", required = false) String format) {
        begin(request);
        try {
            MedreportPrivileges.requireDashboardGenerate();
            byte[] content = service().preview(artifactId);
            if (content == null || content.length == 0) {
                return new ResponseEntity<byte[]>(HttpStatus.NOT_FOUND);
            }
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType("html".equalsIgnoreCase(format)
                    ? MediaType.parseMediaType("text/html; charset=UTF-8")
                    : MediaType.parseMediaType("application/pdf"));
            headers.set(HttpHeaders.CONTENT_DISPOSITION, "inline");
            headers.set(HttpHeaders.CACHE_CONTROL, "no-store, private");
            headers.set("X-Content-Type-Options", "nosniff");
            return new ResponseEntity<byte[]>(content, headers, HttpStatus.OK);
        } finally {
            end();
        }
    }

    @RequestMapping(value = "/module/medreport/reportDownload.form", method = RequestMethod.GET)
    public ResponseEntity<byte[]> download(HttpServletRequest request,
                                           @RequestParam("artifactId") String artifactId,
                                           @RequestParam(value = "filename", required = false) String filename,
                                           @RequestParam(value = "format", required = false) String format) {
        begin(request);
        try {
            MedreportPrivileges.requireDashboardGenerate();
            byte[] content = service().download(artifactId);
            if (content == null || content.length == 0) {
                return new ResponseEntity<byte[]>(HttpStatus.NOT_FOUND);
            }
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(contentType(format)));
            headers.set(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + safeFilename(filename, format) + "\"");
            headers.set(HttpHeaders.CACHE_CONTROL, "no-store, private");
            headers.set("X-Content-Type-Options", "nosniff");
            return new ResponseEntity<byte[]>(content, headers, HttpStatus.OK);
        } finally {
            end();
        }
    }

    // ==================================================================
    // Helpers
    // ==================================================================

    private ReportRequest parse(String payload) {
        if (payload == null || payload.trim().isEmpty()) {
            throw new APIException("La configuration du rapport est manquante.");
        }
        if (payload.length() > MAX_PAYLOAD_CHARS) {
            throw new APIException("La configuration du rapport est trop volumineuse.");
        }
        try {
            return MAPPER.readValue(payload, ReportRequest.class);
        } catch (Exception e) {
            throw new APIException("La configuration du rapport est illisible.", e);
        }
    }

    private String contentType(String format) {
        if ("pdf".equalsIgnoreCase(format)) {
            return "application/pdf";
        }
        if ("html".equalsIgnoreCase(format)) {
            return "text/html; charset=UTF-8";
        }
        if ("odt".equalsIgnoreCase(format)) {
            return "application/vnd.oasis.opendocument.text";
        }
        return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    }

    /**
     * The filename ends up in a Content-Disposition header, so it is stripped to a safe
     * character set here - a quote or a newline in it would let a caller inject header
     * content.
     */
    private String safeFilename(String filename, String format) {
        String extension = format != null && format.matches("[a-zA-Z]{2,4}")
                ? format.toLowerCase() : "docx";
        if (filename == null || filename.trim().isEmpty()) {
            return "rapport." + extension;
        }
        String cleaned = filename.replaceAll("[^A-Za-z0-9._-]", "_");
        if (cleaned.length() > 100) {
            cleaned = cleaned.substring(0, 100);
        }
        return cleaned.toLowerCase().endsWith("." + extension) ? cleaned : cleaned + "." + extension;
    }
}
