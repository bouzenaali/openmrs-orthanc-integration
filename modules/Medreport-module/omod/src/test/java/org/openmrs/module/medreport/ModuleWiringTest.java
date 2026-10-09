package org.openmrs.module.medreport;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Fast, no-OpenMRS-context checks on the packaging itself.
 *
 * <p>An OpenMRS module fails in a particular way: the code compiles, the build succeeds, and
 * the module then misbehaves at runtime because a mapping file was not registered, a
 * privilege check was forgotten on a new endpoint, or a template referenced a variable that
 * does not exist in its scope. None of that is caught by a compiler. These tests are the
 * regression guard for exactly those classes of mistake - the same discipline the patientview
 * module adopted after shipping several of them.
 */
public class ModuleWiringTest {

    private static final File OMOD = new File("").getAbsoluteFile();

    private static final File API = new File(OMOD.getParentFile(), "api");

    private static final File RESOURCES = new File(OMOD, "src/main/resources");

    private static final File WEBAPP = new File(OMOD, "src/main/webapp");

    private static final File JAVA = new File(OMOD, "src/main/java");

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private Document parse(File file) throws Exception {
        assertTrue(file + " must exist", file.exists());
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setValidating(false);
        // The .hbm.xml files declare a DOCTYPE pointing at hibernate.org; resolving it would
        // make this test depend on the network.
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) ->
                new InputSource(new java.io.StringReader("")));
        return builder.parse(file);
    }

    private String read(File file) throws IOException {
        StringBuilder text = new StringBuilder();
        Reader reader = new InputStreamReader(new FileInputStream(file), Charset.forName("UTF-8"));
        try {
            char[] chunk = new char[8192];
            int count;
            while ((count = reader.read(chunk)) != -1) {
                text.append(chunk, 0, count);
            }
        } finally {
            reader.close();
        }
        return text.toString();
    }

    private List<File> filesUnder(File root, String suffix) {
        List<File> found = new ArrayList<File>();
        if (!root.exists()) {
            return found;
        }
        File[] children = root.listFiles();
        if (children == null) {
            return found;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                found.addAll(filesUnder(child, suffix));
            } else if (child.getName().endsWith(suffix)) {
                found.add(child);
            }
        }
        return found;
    }

    private List<String> textOf(Document document, String tag) {
        List<String> values = new ArrayList<String>();
        NodeList nodes = document.getElementsByTagName(tag);
        for (int i = 0; i < nodes.getLength(); i++) {
            values.add(nodes.item(i).getTextContent().trim());
        }
        return values;
    }

    // ==================================================================
    // config.xml
    // ==================================================================

    @Test
    public void configXmlParsesAndDeclaresTheModule() throws Exception {
        Document config = parse(new File(RESOURCES, "config.xml"));
        assertEquals("medreport", textOf(config, "id").get(0));
        assertEquals("org.openmrs.module.medreport", textOf(config, "package").get(0));
        assertEquals("org.openmrs.module.medreport.MedreportActivator",
                textOf(config, "activator").get(0));
    }

    /**
     * A mapping file that is not registered here exists on the classpath but is invisible to
     * Hibernate, and the first query against that entity fails at runtime with a confusing
     * "unknown entity" error.
     */
    @Test
    public void everyMappingFileIsRegisteredAndPresentOnDisk() throws Exception {
        Document config = parse(new File(RESOURCES, "config.xml"));
        NodeList nodes = config.getElementsByTagName("mappingFiles");
        assertEquals(1, nodes.getLength());

        Set<String> declared = new LinkedHashSet<String>();
        for (String line : nodes.item(0).getTextContent().split("\\s+")) {
            if (!line.trim().isEmpty()) {
                declared.add(line.trim());
            }
        }

        File modelDir = new File(API, "src/main/resources/org/openmrs/module/medreport/api/model");
        Set<String> onDisk = new LinkedHashSet<String>();
        for (File file : filesUnder(modelDir, ".hbm.xml")) {
            onDisk.add("org/openmrs/module/medreport/api/model/" + file.getName());
        }

        assertFalse("no .hbm.xml files were found at all", onDisk.isEmpty());
        assertEquals("every mapping file on disk must be registered in config.xml, and vice versa",
                onDisk, declared);

        for (String declaredFile : declared) {
            File file = new File(API, "src/main/resources/" + declaredFile);
            assertTrue(declaredFile + " is declared but missing on disk", file.exists());
            parse(file); // must also be well-formed
        }
    }

    /**
     * The Reference Application auto-grants every plain privilege to every role, so a
     * privilege that is meant to restrict something must be "App:"-prefixed or it restricts
     * nothing. This catches a new privilege being added without the prefix.
     */
    @Test
    public void everyPrivilegeThatGatesSomethingIsAppPrefixed() throws Exception {
        Document config = parse(new File(RESOURCES, "config.xml"));
        Set<String> declared = new HashSet<String>(textOf(config, "name"));

        for (String privilege : Arrays.asList(
                MedreportPrivileges.APP_IMAGING_VIEW,
                MedreportPrivileges.APP_IMAGING_MANAGE,
                MedreportPrivileges.APP_ADMIN,
                MedreportPrivileges.APP_DASHBOARD_GENERATE)) {
            assertTrue(privilege + " must be declared in config.xml", declared.contains(privilege));
            assertTrue(privilege + " must be App:-prefixed to be an effective boundary",
                    privilege.startsWith("App: "));
        }
    }

    @Test
    public void theRenderServiceTokenGlobalPropertyShipsEmpty() throws Exception {
        Document config = parse(new File(RESOURCES, "config.xml"));
        NodeList properties = config.getElementsByTagName("globalProperty");
        boolean found = false;
        for (int i = 0; i < properties.getLength(); i++) {
            Element property = (Element) properties.item(i);
            String name = property.getElementsByTagName("property").item(0).getTextContent().trim();
            if (MedreportConstants.GP_RENDER_TOKEN.equals(name)) {
                found = true;
                String value = property.getElementsByTagName("defaultValue")
                        .item(0).getTextContent().trim();
                // A shipped default token would be a shared secret published in source
                // control and identical on every install.
                assertEquals("the shared token must never ship with a default value", "", value);
            }
        }
        assertTrue(MedreportConstants.GP_RENDER_TOKEN + " must be declared", found);
    }

    /**
     * medreport reads contributors reflectively precisely so it does not depend on them; a
     * hard require_module would defeat that and stop OpenMRS booting without them.
     */
    @Test
    public void contributingModulesAreAwareOfNotRequired() throws Exception {
        Document config = parse(new File(RESOURCES, "config.xml"));
        String required = String.valueOf(textOf(config, "require_module"));
        assertFalse("medreport must not require patientview", required.contains("patientview"));
        assertFalse("medreport must not require imaging", required.contains(".imaging"));

        String awareOf = String.valueOf(textOf(config, "aware_of_module"));
        assertTrue(awareOf.contains("patientview"));
        assertTrue(awareOf.contains("imaging"));
    }

    // ==================================================================
    // Spring wiring
    // ==================================================================

    /**
     * A service bean can exist in Spring and still be invisible to
     * {@code Context.getService(...)}: the {@code serviceContext} registration is a separate
     * step, and forgetting it produces a ServiceNotFoundException at first use.
     */
    @Test
    public void everyServiceInterfaceIsRegisteredWithTheServiceContext() throws Exception {
        String spring = read(new File(API, "src/main/resources/moduleApplicationContext.xml"));
        for (String service : Arrays.asList(
                "org.openmrs.module.medreport.api.ImageReportService",
                "org.openmrs.module.medreport.api.PatientReportService",
                "org.openmrs.module.medreport.api.MedreportAuditService")) {
            assertTrue(service + " must be registered with the OpenMRS serviceContext",
                    spring.contains("<value>" + service + "</value>"));
        }
        assertTrue("services must be wrapped in a transaction proxy",
                spring.contains("TransactionProxyFactoryBean"));
    }

    /**
     * The audit log must survive a rollback of the business transaction, otherwise the
     * refused and failed attempts - the ones most worth recording - are erased with it.
     */
    @Test
    public void auditWritesRunInTheirOwnTransaction() throws Exception {
        String spring = read(new File(API, "src/main/resources/moduleApplicationContext.xml"));
        int auditBean = spring.indexOf("medreport.MedreportAuditServiceTarget");
        assertTrue(auditBean > 0);
        String tail = spring.substring(auditBean);
        int nextBean = tail.indexOf("ImageReportServiceTarget");
        String auditSection = nextBean > 0 ? tail.substring(0, nextBean) : tail;
        assertTrue("audit log writes must use PROPAGATION_REQUIRES_NEW",
                auditSection.contains("PROPAGATION_REQUIRES_NEW"));
    }

    // ==================================================================
    // Liquibase
    // ==================================================================

    @Test
    public void liquibaseParsesAndChangesetIdsAreUnique() throws Exception {
        Document changelog = parse(new File(API, "src/main/resources/liquibase.xml"));
        NodeList changesets = changelog.getElementsByTagName("changeSet");
        assertTrue("expected changesets", changesets.getLength() > 0);

        Set<String> ids = new HashSet<String>();
        for (int i = 0; i < changesets.getLength(); i++) {
            String id = ((Element) changesets.item(i)).getAttribute("id");
            assertFalse("changeset id must not be blank", id.trim().isEmpty());
            assertTrue("duplicate changeset id: " + id, ids.add(id));
        }
    }

    /** Every table an .hbm.xml maps must actually be created by a changeset. */
    @Test
    public void everyMappedTableIsCreatedByAChangeset() throws Exception {
        Document changelog = parse(new File(API, "src/main/resources/liquibase.xml"));
        Set<String> created = new HashSet<String>();
        NodeList tables = changelog.getElementsByTagName("createTable");
        for (int i = 0; i < tables.getLength(); i++) {
            created.add(((Element) tables.item(i)).getAttribute("tableName"));
        }

        File modelDir = new File(API, "src/main/resources/org/openmrs/module/medreport/api/model");
        // The leading \s matters: without it this also matches inside mutable="false" and
        // captures "false" as a table name.
        Pattern tablePattern = Pattern.compile("\\stable\\s*=\\s*\"([^\"]+)\"");
        for (File mapping : filesUnder(modelDir, ".hbm.xml")) {
            Matcher matcher = tablePattern.matcher(read(mapping));
            while (matcher.find()) {
                assertTrue("table " + matcher.group(1) + " is mapped by " + mapping.getName()
                        + " but never created by a Liquibase changeset",
                        created.contains(matcher.group(1)));
            }
        }
    }

    /** RP9's join table must exist and index both directions of the association. */
    @Test
    public void theReportImageJoinTableIndexesBothDirections() throws Exception {
        String changelog = read(new File(API, "src/main/resources/liquibase.xml"));
        assertTrue(changelog.contains("medreport_report_image"));
        assertTrue("the 'which reports cover this study' direction must be indexed",
                changelog.contains("medreport_report_image_study_idx"));
        assertTrue("the 'which images does this report cover' direction must be indexed",
                changelog.contains("medreport_report_image_report_idx"));
    }

    // ==================================================================
    // Controllers - the API half of RP6
    // ==================================================================

    /**
     * The guard that matters most here. Every REST handler must re-check a privilege itself:
     * the UI's hidden buttons are a convenience, and a handler that relies on them is an open
     * endpoint. This scans the source so a newly added endpoint cannot skip the check.
     */
    @Test
    public void everyRestEndpointEnforcesAPrivilegeItself() throws Exception {
        List<File> controllers = filesUnder(
                new File(JAVA, "org/openmrs/module/medreport/web/controller"), "RestController.java");
        assertFalse("no REST controllers were found - has the package moved?", controllers.isEmpty());

        Pattern method = Pattern.compile(
                "@RequestMapping\\([^)]*\\)\\s*(?:@ResponseBody\\s*)?"
                        + "public\\s+[\\w<>\\[\\],.?\\s]+\\s+(\\w+)\\s*\\(", Pattern.DOTALL);

        for (File controller : controllers) {
            String source = read(controller);
            Matcher matcher = method.matcher(source);
            int endpoints = 0;
            while (matcher.find()) {
                endpoints++;
                String name = matcher.group(1);
                // Body of this handler: from its opening brace to the start of the next one.
                int start = matcher.end();
                int nextEndpoint = source.indexOf("@RequestMapping", start);
                String body = nextEndpoint > 0 ? source.substring(start, nextEndpoint)
                        : source.substring(start);
                assertTrue(controller.getName() + "." + name
                                + " must call MedreportPrivileges.require* before doing any work",
                        body.contains("MedreportPrivileges.require"));
            }
            assertTrue(controller.getName() + " declares no endpoints", endpoints > 0);
        }
    }

    /**
     * Audit attribution depends on the thread-local being cleared; a leaked value is
     * attributed to whoever gets that pooled thread next.
     */
    @Test
    public void everyControllerClearsTheRequestContext() throws Exception {
        for (File controller : filesUnder(
                new File(JAVA, "org/openmrs/module/medreport/web/controller"), "RestController.java")) {
            String source = read(controller);
            int begins = countOccurrences(source, "begin(request)");
            int ends = countOccurrences(source, "end();");
            assertEquals(controller.getName()
                            + " must call end() in a finally for every begin(request)",
                    begins, ends);
            assertEquals(controller.getName() + " must pair begin/end with try/finally",
                    begins, countOccurrences(source, "} finally {"));
        }
    }

    /** History and restore are a strictly higher capability than ordinary reads. */
    @Test
    public void administratorEndpointsRequireTheAdminPrivilege() throws Exception {
        String source = read(new File(JAVA,
                "org/openmrs/module/medreport/web/controller/ImageReportRestController.java"));
        for (String handler : Arrays.asList("history", "restore")) {
            int index = source.indexOf("public Map<String, Object> " + handler + "(");
            assertTrue(handler + " endpoint not found", index > 0);
            String body = source.substring(index, Math.min(source.length(), index + 900));
            assertTrue(handler + " must require the admin privilege",
                    body.contains("MedreportPrivileges.requireAdmin()"));
        }
    }

    private int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = haystack.indexOf(needle);
        while (index >= 0) {
            count++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return count;
    }

    // ==================================================================
    // Extension / app descriptors
    // ==================================================================

    /**
     * AppConfigurationLoaderFactory routes purely by filename glob: a correctly shaped file
     * with the wrong suffix loads without error and is simply never registered.
     */
    @Test
    public void theDashboardExtensionIsShapedAndNamedCorrectly() throws Exception {
        File extension = new File(RESOURCES, "apps/medreport_extension.json");
        assertTrue("must be named *extension.json to be loaded as an Extension",
                extension.getName().endsWith("extension.json"));

        String json = read(extension).trim();
        assertTrue("must be a top-level JSON array", json.startsWith("[") && json.endsWith("]"));
        assertTrue(json.contains("\"extensionPointId\": \"patientDashboard.overallActions\""));
        // Without this the button is visible to users who cannot use it.
        assertTrue("the dashboard button must be privilege-gated",
                json.contains("\"requiredPrivilege\": \"" + MedreportPrivileges.APP_DASHBOARD_GENERATE + "\""));
    }

    // ==================================================================
    // Templates
    // ==================================================================

    /**
     * Page templates have no {@code config} - that is a fragment-only concept - and a page
     * that references it throws MissingPropertyException at render time, not build time.
     */
    @Test
    public void pagesDoNotUseConfigAndFragmentsDoNotUsePageOnlyVariables() throws Exception {
        for (File page : filesUnder(new File(WEBAPP, "pages"), ".gsp")) {
            String source = read(page);
            assertFalse(page.getName() + " is a page and must not reference `config`",
                    Pattern.compile("\\bconfig\\s*\\.").matcher(source).find());
        }
        for (File fragment : filesUnder(new File(WEBAPP, "fragments"), ".gsp")) {
            String source = read(fragment);
            assertFalse(fragment.getName() + " is a fragment and must not call ui.decorateWith",
                    source.contains("ui.decorateWith"));
        }
    }

    /**
     * Brace balance across scriptlets, counted only inside {@code <% %>} blocks: an if/each
     * block legitimately spans several scriptlets with HTML in between, so counting braces
     * over the whole file gives false failures, and counting only HTML gives false passes.
     */
    @Test
    public void everyGspHasBalancedScriptletBraces() throws Exception {
        List<File> templates = new ArrayList<File>();
        templates.addAll(filesUnder(new File(WEBAPP, "pages"), ".gsp"));
        templates.addAll(filesUnder(new File(WEBAPP, "fragments"), ".gsp"));
        assertFalse("no .gsp templates found", templates.isEmpty());

        Pattern scriptlet = Pattern.compile("<%(.*?)%>", Pattern.DOTALL);
        for (File template : templates) {
            String source = read(template);
            Matcher matcher = scriptlet.matcher(source);
            int depth = 0;
            while (matcher.find()) {
                String code = matcher.group(1);
                if (code.startsWith("--")) {
                    continue; // <%-- comment --%>
                }
                depth += countOccurrences(code, "{") - countOccurrences(code, "}");
            }
            assertEquals(template.getName() + " has unbalanced braces across its scriptlets",
                    0, depth);
        }
    }

    /**
     * The bug that made both UIs completely inert.
     *
     * <p>French messages are full of apostrophes ("Sélectionnez au moins un ensemble
     * d'informations", "Seul l'auteur d'un compte rendu…"). Interpolated raw into a
     * single-quoted JavaScript string they close it early, which is a parse error for the
     * whole {@code <script>} block - so the init function never runs, the page renders with
     * no data and dead buttons, and nothing is logged where a clinician would see it.
     *
     * <p>Every value interpolated inside a {@code <script>} block must therefore go through
     * {@code ui.escapeJs(...)}. This scans the templates for any that do not.
     */
    @Test
    public void everyValueInterpolatedIntoJavaScriptIsEscaped() throws Exception {
        List<File> templates = new ArrayList<File>();
        templates.addAll(filesUnder(new File(WEBAPP, "pages"), ".gsp"));
        templates.addAll(filesUnder(new File(WEBAPP, "fragments"), ".gsp"));

        Pattern scriptBlock = Pattern.compile("<script[^>]*>(.*?)</script>", Pattern.DOTALL);
        Pattern interpolation = Pattern.compile("\\$\\{([^}]*)\\}");
        Set<String> offenders = new LinkedHashSet<String>();

        for (File template : templates) {
            Matcher blocks = scriptBlock.matcher(read(template));
            while (blocks.find()) {
                Matcher values = interpolation.matcher(blocks.group(1));
                while (values.find()) {
                    String expression = values.group(1).trim();
                    if (expression.contains("ui.escapeJs")) {
                        continue;
                    }
                    // Numeric and boolean model attributes are not quoted in the emitted JS,
                    // so they cannot terminate a string literal.
                    if (expression.equals("patient.patientId")
                            || expression.equals("canManage")
                            || expression.equals("isAdmin")) {
                        continue;
                    }
                    offenders.add(template.getName() + ": ${" + expression + "}");
                }
            }
        }

        assertTrue("values interpolated into JavaScript must be wrapped in ui.escapeJs() - "
                + "an apostrophe in a French message otherwise breaks the whole script block: "
                + offenders, offenders.isEmpty());
    }

    /**
     * A message that reaches JavaScript and contains a quote is exactly the payload that
     * broke the UI; assert the French bundle really does contain such strings, so the
     * escaping test above is guarding something real rather than passing vacuously.
     */
    @Test
    public void theFrenchBundleContainsApostrophesThatWouldBreakUnescapedJavaScript() throws Exception {
        Properties french = loadBundle("messages_fr.properties");
        int withApostrophes = 0;
        for (Object value : french.values()) {
            if (String.valueOf(value).contains("'")) {
                withApostrophes++;
            }
        }
        assertTrue("expected French messages containing apostrophes", withApostrophes > 0);
    }

    /** A GSP referencing a missing message key renders the raw key to the clinician. */
    @Test
    public void everyMessageKeyUsedInATemplateExists() throws Exception {
        Properties english = loadBundle("messages.properties");

        List<File> templates = new ArrayList<File>();
        templates.addAll(filesUnder(new File(WEBAPP, "pages"), ".gsp"));
        templates.addAll(filesUnder(new File(WEBAPP, "fragments"), ".gsp"));

        Pattern usage = Pattern.compile("ui\\.message\\(\\s*[\"'](medreport\\.[^\"']+)[\"']");
        Set<String> missing = new LinkedHashSet<String>();
        for (File template : templates) {
            Matcher matcher = usage.matcher(read(template));
            while (matcher.find()) {
                if (!english.containsKey(matcher.group(1))) {
                    missing.add(matcher.group(1) + " (" + template.getName() + ")");
                }
            }
        }
        assertTrue("message keys used in templates but absent from messages.properties: " + missing,
                missing.isEmpty());
    }

    /** A key present in one language and missing in another renders the raw key. */
    @Test
    public void allThreeLanguageBundlesDeclareTheSameKeys() throws Exception {
        Properties english = loadBundle("messages.properties");
        Properties french = loadBundle("messages_fr.properties");
        Properties arabic = loadBundle("messages_ar.properties");

        assertFalse("the English bundle is empty", english.isEmpty());
        assertEquals("French must cover exactly the English keys",
                new LinkedHashSet<Object>(english.keySet()), new LinkedHashSet<Object>(french.keySet()));
        assertEquals("Arabic must cover exactly the English keys",
                new LinkedHashSet<Object>(english.keySet()), new LinkedHashSet<Object>(arabic.keySet()));
    }

    /**
     * java.util.Properties reads .properties as ISO-8859-1, so a bundle containing raw UTF-8
     * renders as mojibake. Non-ASCII must be \\uXXXX-escaped (see scripts/escape_props.py).
     */
    @Test
    public void messageBundlesAreAsciiEscaped() throws Exception {
        for (String name : Arrays.asList("messages.properties", "messages_fr.properties",
                "messages_ar.properties")) {
            byte[] bytes = readBytes(new File(RESOURCES, name));
            for (int i = 0; i < bytes.length; i++) {
                assertTrue(name + " contains a non-ASCII byte at offset " + i
                                + "; regenerate it with scripts/escape_props.py",
                        (bytes[i] & 0xFF) < 0x80);
            }
        }
    }

    private Properties loadBundle(String name) throws IOException {
        Properties properties = new Properties();
        FileInputStream stream = new FileInputStream(new File(RESOURCES, name));
        try {
            properties.load(stream);
        } finally {
            stream.close();
        }
        return properties;
    }

    private byte[] readBytes(File file) throws IOException {
        FileInputStream stream = new FileInputStream(file);
        try {
            byte[] bytes = new byte[(int) file.length()];
            int read = 0;
            while (read < bytes.length) {
                int count = stream.read(bytes, read, bytes.length - read);
                if (count < 0) {
                    break;
                }
                read += count;
            }
            return bytes;
        } finally {
            stream.close();
        }
    }

    // ==================================================================
    // CSS namespace
    // ==================================================================

    /**
     * The Reference Application loads Bootstrap globally, so .panel, .btn, .modal and friends
     * are already taken; reusing one silently restyles the host page. Every class this module
     * defines must be mr-prefixed.
     */
    @Test
    public void everyCssClassIsPrefixed() throws Exception {
        // Strip comments first: the file's own header explains the rule by naming the
        // Bootstrap classes it avoids, and those mentions are not selectors.
        String css = read(new File(WEBAPP, "resources/css/medreport.css"))
                .replaceAll("(?s)/\\*.*?\\*/", "");
        Matcher matcher = Pattern.compile("\\.([a-zA-Z][\\w-]*)").matcher(css);
        Set<String> offenders = new LinkedHashSet<String>();
        while (matcher.find()) {
            String className = matcher.group(1);
            if (!className.startsWith("mr-")) {
                offenders.add(className);
            }
        }
        assertTrue("CSS classes must be mr-prefixed to avoid colliding with Bootstrap: "
                + offenders, offenders.isEmpty());
    }

    // ==================================================================
    // The contributor manifest contract
    // ==================================================================

    /**
     * The manifest is the whole contract with contributing modules; if its filename drifts,
     * discovery silently finds nothing and reports come out empty rather than failing.
     */
    @Test
    public void theManifestResourceNameMatchesWhatContributorsShip() {
        assertEquals("medreport-datasource.json",
                org.openmrs.module.medreport.api.catalog.DataSourceRegistry.MANIFEST_RESOURCE);
    }
}
