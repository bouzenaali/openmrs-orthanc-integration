package org.openmrs.module.medreport.api.obs;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.Obs;
import org.openmrs.api.APIException;
import org.openmrs.obs.ComplexData;
import org.openmrs.obs.ComplexObsHandler;
import org.openmrs.obs.handler.AbstractHandler;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Stores a rendered report document as a Complex Obs (ADR-6).
 *
 * <p>{@code ComplexObsHandler} is OpenMRS's existing mechanism for "a file plus database
 * metadata, tied to an encounter, retrievable, downloadable, voidable", which is exactly the
 * shape an imaging report needs - so it is reused rather than reinvented as a BLOB column.
 * Its void-not-delete default is also already the behaviour RP5 and RP8 require.
 *
 * <p>The file lands under OpenMRS's configured complex-obs directory, so it inherits whatever
 * backup and filesystem permissions the deployment already applies to patient attachments,
 * instead of introducing a second location for PHI that an administrator has to know about.
 *
 * <p>{@link #purgeComplexData(Obs)} is intentionally refused. Nothing in this module ever
 * physically deletes a report or a version, and a handler that could would be a way around
 * that guarantee even though no medreport code path calls it.
 */
public class MedreportComplexObsHandler extends AbstractHandler implements ComplexObsHandler {

    private static final Log log = LogFactory.getLog(MedreportComplexObsHandler.class);

    private static final String[] SUPPORTED_VIEWS = { ComplexObsHandler.RAW_VIEW,
            ComplexObsHandler.TITLE_VIEW, ComplexObsHandler.URI_VIEW };

    public Obs saveObs(Obs obs) throws APIException {
        ComplexData complexData = obs.getComplexData();
        if (complexData == null || complexData.getData() == null) {
            throw new APIException("medreport: cannot save a complex obs with no document data.");
        }

        try {
            File target = getOutputFileToWrite(obs);
            OutputStream out = new FileOutputStream(target, false);
            try {
                Object data = complexData.getData();
                if (data instanceof byte[]) {
                    out.write((byte[]) data);
                } else if (data instanceof InputStream) {
                    copy((InputStream) data, out);
                } else {
                    throw new APIException("medreport: unsupported complex data type "
                            + data.getClass().getName() + "; expected byte[] or InputStream.");
                }
            } finally {
                out.close();
            }

            // "<original title>|<stored file name>" is the convention core's own handlers use;
            // AbstractHandler.getComplexDataFile() reads the stored name back out of it.
            obs.setValueComplex(complexData.getTitle() + "|" + target.getName());
            obs.setComplexData(null);
            return obs;
        } catch (IOException e) {
            throw new APIException("medreport: could not write the report document for obs "
                    + obs.getUuid() + " to the complex obs directory.", e);
        }
    }

    public Obs getObs(Obs obs, String view) {
        File file = getComplexDataFile(obs);
        if (file == null || !file.exists()) {
            log.warn("medreport: complex obs " + obs.getUuid()
                    + " references a document that is missing from disk.");
            return obs;
        }

        String title = titleOf(obs, file);

        if (ComplexObsHandler.TITLE_VIEW.equals(view) || ComplexObsHandler.URI_VIEW.equals(view)) {
            obs.setComplexData(new ComplexData(title, title));
            return obs;
        }

        try {
            obs.setComplexData(new ComplexData(title, read(file)));
        } catch (IOException e) {
            throw new APIException("medreport: could not read the report document for obs "
                    + obs.getUuid() + ".", e);
        }
        return obs;
    }

    /**
     * Always refuses. Reports and their versions are never physically deleted (RP5, RP8);
     * a handler that honoured a purge would be a hole in that guarantee.
     */
    @Override
    public boolean purgeComplexData(Obs obs) {
        log.warn("medreport: refused a request to purge the document of obs "
                + (obs != null ? obs.getUuid() : "null")
                + ". Report documents are retained for the life of the record; "
                + "removal is a soft delete.");
        return false;
    }

    @Override
    public String[] getSupportedViews() {
        return SUPPORTED_VIEWS.clone();
    }

    @Override
    public boolean supportsView(String view) {
        for (String supported : SUPPORTED_VIEWS) {
            if (supported.equals(view)) {
                return true;
            }
        }
        return false;
    }

    private String titleOf(Obs obs, File file) {
        String valueComplex = obs.getValueComplex();
        if (valueComplex != null && valueComplex.contains("|")) {
            String title = valueComplex.substring(0, valueComplex.indexOf('|')).trim();
            if (!title.isEmpty()) {
                return title;
            }
        }
        return file.getName();
    }

    private static byte[] read(File file) throws IOException {
        InputStream in = new FileInputStream(file);
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream((int) Math.max(file.length(), 1024));
            copy(in, buffer);
            return buffer.toByteArray();
        } finally {
            in.close();
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) != -1) {
            out.write(chunk, 0, read);
        }
    }
}
