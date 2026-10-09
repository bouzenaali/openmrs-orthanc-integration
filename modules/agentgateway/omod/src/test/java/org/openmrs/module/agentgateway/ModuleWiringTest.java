package org.openmrs.module.agentgateway;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Packaging and convention checks that fail the build rather than the hospital.
 * <p>
 * None of these need a running OpenMRS, which is the point: a privilege check omitted from a new
 * endpoint, a Hibernate mapping declared but not shipped, a page whose controller was never
 * written - each of them is invisible until the module is deployed and then behaves as a security
 * hole or a blank screen. The neighbouring patientview module grew the same kind of test for the
 * same reason.
 */
public class ModuleWiringTest {

	private static final Charset UTF_8 = Charset.forName("UTF-8");

	private static final Path OMOD = Paths.get("").toAbsolutePath();

	private static final Path API = OMOD.getParent().resolve("api");

	// ------------------------------------------------------------------ privileges

	@Test
	public void everyRestControllerMethodEnforcesAPrivilege() throws Exception {
		// The Reference Application auto-grants plain API-level privileges to every role, so an
		// @Authorized annotation on the service is not a boundary. Each web entry point has to
		// check an "App:" privilege explicitly, and this is what makes anyone adding one do it.
		List<String> offenders = new ArrayList<String>();
		int inspected = 0;

		for (Path controller : javaFilesUnder(OMOD.resolve("src/main/java/org/openmrs/module/agentgateway/web/controller"))) {
			String source = read(controller);
			Matcher methods = Pattern
					.compile("@RequestMapping\\([^)]*\\)\\s*(?:@ResponseBody\\s*)?public\\s+[\\w<>,\\s\\[\\]]+?"
							+ "\\s+(\\w+)\\s*\\(([^{]*)\\)\\s*\\{(.*?)\\n\\t\\}", Pattern.DOTALL)
					.matcher(source);
			while (methods.find()) {
				inspected++;
				String name = methods.group(1);
				String body = methods.group(3);
				if (!body.contains("AgentGatewayPrivileges.require")
						&& !body.contains("constantTimeEquals")) {
					offenders.add(controller.getFileName() + "#" + name);
				}
			}
		}

		// Without this, a change to how endpoints are written would make the pattern match
		// nothing and the test would pass by inspecting no code at all - the worst kind of green.
		assertEquals("The endpoint pattern no longer matches this module's controllers; the check above "
				+ "would silently inspect nothing. Update the pattern.", countRequestMappings(), inspected);

		assertTrue("These endpoints do not gate themselves on a privilege (or, for the key endpoint, "
				+ "on the channel secret): " + offenders, offenders.isEmpty());
	}

	@Test
	public void everyPrivilegeTheCodeUsesIsDeclaredInConfigXml() throws Exception {
		Set<String> declared = declaredPrivileges();

		assertTrue("Missing: " + AgentGatewayPrivileges.CHAT_USE, declared.contains(AgentGatewayPrivileges.CHAT_USE));
		assertTrue("Missing: " + AgentGatewayPrivileges.CHAT_WRITE, declared.contains(AgentGatewayPrivileges.CHAT_WRITE));
		assertTrue("Missing: " + AgentGatewayPrivileges.ROLLBACK, declared.contains(AgentGatewayPrivileges.ROLLBACK));
	}

	@Test
	public void everyPrivilegeIsAppPrefixedSoItActuallyRestrictsSomething() throws Exception {
		for (String privilege : declaredPrivileges()) {
			assertTrue("'" + privilege + "' is not App:-prefixed and would be auto-granted to every role "
					+ "on a Reference Application install, gating nothing", privilege.startsWith("App: "));
		}
	}

	@Test
	public void everyPrivilegeReferencedByAnAppExtensionIsDeclared() throws Exception {
		Set<String> declared = declaredPrivileges();
		int found = 0;

		for (Path definition : listFiles(OMOD.resolve("src/main/resources/apps"), ".json")) {
			Matcher required = Pattern.compile("\"requiredPrivilege\"\\s*:\\s*\"([^\"]+)\"").matcher(read(definition));
			while (required.find()) {
				found++;
				assertTrue("App extension requires an undeclared privilege: " + required.group(1),
						declared.contains(required.group(1)));
			}
		}
		assertTrue("No app extension declares a required privilege - the links would be visible to everyone",
				found > 0);
	}

	/**
	 * The bug this exists to prevent, in full, because it cost a deployment its entire UI and
	 * left no error a user would ever see.
	 * <p>
	 * OpenMRS's appframework does not read {@code apps/*.json} generically. It runs three
	 * separate classpath scans, keyed on how the file is <em>named</em>:
	 * {@code apps/*AppTemplates.json} becomes app templates, {@code apps/*app.json} becomes
	 * {@code AppDescriptor}s, and {@code apps/*extension.json} becomes {@code Extension}s. Those
	 * are different classes with different fields - {@code AppDescriptor} has no
	 * {@code extensionPointId} - and appframework parses them with Jackson 1.x, which rejects
	 * unknown properties outright. So a file of extensions named {@code ..._app.json} is parsed
	 * as apps, fails, and is skipped, with one line in the server log and no symptom anywhere
	 * else: the module starts, its settings appear, its endpoints work, and not a single link
	 * or widget renders.
	 */
	@Test
	public void appFrameworkJsonFilesAreNamedForWhatTheyActuallyContain() throws Exception {
		List<Path> definitions = listFiles(OMOD.resolve("src/main/resources/apps"), ".json");
		assertFalse("No app framework definitions found - the module would have no UI entry points at all",
				definitions.isEmpty());

		for (Path definition : definitions) {
			String name = definition.getFileName().toString();
			boolean declaresExtensions = read(definition).contains("\"extensionPointId\"");

			if (declaresExtensions) {
				assertTrue(name + " contains extensions, so appframework will only load it if the file name "
						+ "ends with 'extension.json'. Named as it is, it is parsed as an AppDescriptor, "
						+ "rejected, and silently ignored.", name.endsWith("extension.json"));
			} else {
				assertTrue(name + " declares no extensionPointId, so it must be an app definition and has to "
						+ "be named '...app.json' to be loaded at all.",
						name.endsWith("app.json") || name.endsWith("AppTemplates.json"));
			}
		}
	}

	@Test
	public void everyFragmentIncludedByAnExtensionExists() throws Exception {
		// A fragment-inclusion extension names its provider and fragment as plain strings; a typo
		// or a rename produces an empty space on the dashboard rather than an error.
		for (Path definition : listFiles(OMOD.resolve("src/main/resources/apps"), ".json")) {
			Matcher fragments = Pattern
					.compile("\"provider\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"fragment\"\\s*:\\s*\"([^\"]+)\"")
					.matcher(read(definition));
			while (fragments.find()) {
				assertEquals("A fragment extension points at another module's provider", "agentgateway",
						fragments.group(1));
				Path gsp = OMOD.resolve("src/main/webapp/fragments").resolve(fragments.group(2) + ".gsp");
				assertTrue("Extension includes a fragment with no template: " + gsp, Files.exists(gsp));

				String controller = Character.toUpperCase(fragments.group(2).charAt(0))
						+ fragments.group(2).substring(1) + "FragmentController.java";
				Path java = OMOD.resolve("src/main/java/org/openmrs/module/agentgateway/fragment/controller")
						.resolve(controller);
				assertTrue("Fragment has no controller: " + java, Files.exists(java));
			}
		}
	}

	// ------------------------------------------------------------------ packaging

	@Test
	public void everyMappingFileDeclaredInConfigXmlExistsOnDisk() throws Exception {
		Document config = parse(OMOD.resolve("src/main/resources/config.xml"));
		NodeList mappings = config.getElementsByTagName("mappingFiles");
		assertEquals("Expected exactly one <mappingFiles> element", 1, mappings.getLength());

		String[] declared = mappings.item(0).getTextContent().trim().split("\\s+");
		assertTrue("No Hibernate mapping files are declared", declared.length > 0);
		for (String mapping : declared) {
			Path resource = API.resolve("src/main/resources").resolve(mapping);
			assertTrue("Declared but missing on disk: " + mapping, Files.exists(resource));
		}
	}

	@Test
	public void theFilterDeclaredInConfigXmlExists() throws Exception {
		Document config = parse(OMOD.resolve("src/main/resources/config.xml"));
		NodeList filters = config.getElementsByTagName("filter-class");
		assertTrue("The audit filter is the module's whole enforcement point; it must be registered",
				filters.getLength() > 0);

		for (int i = 0; i < filters.getLength(); i++) {
			String className = filters.item(i).getTextContent().trim();
			Path source = OMOD.resolve("src/main/java").resolve(className.replace('.', '/') + ".java");
			assertTrue("config.xml registers a filter with no source file: " + className, Files.exists(source));
		}
	}

	@Test
	public void theAuditFilterIsMappedWidelyEnoughToCoverTheConfigurablePrefixes() throws Exception {
		// The audited prefixes are a global property an administrator can extend at runtime; a
		// narrower filter mapping would silently stop covering anything they added.
		Document config = parse(OMOD.resolve("src/main/resources/config.xml"));
		NodeList patterns = config.getElementsByTagName("url-pattern");

		boolean coversEverything = false;
		for (int i = 0; i < patterns.getLength(); i++) {
			coversEverything |= "/*".equals(patterns.item(i).getTextContent().trim());
		}
		assertTrue("The audit filter must be mapped on /* so it still covers prefixes added later",
				coversEverything);
	}

	@Test
	public void configXmlAndBothPomsAgreeOnTheVersion() throws Exception {
		String version = firstMatch(read(OMOD.resolve("src/main/resources/config.xml")),
				"<version>([^<]+)</version>");
		String parentVersion = firstMatch(read(OMOD.getParent().resolve("pom.xml")),
				"<artifactId>agentgateway</artifactId>\\s*<version>([^<]+)</version>");

		assertNotNull(version);
		assertEquals("config.xml and the root pom disagree on the module version", parentVersion, version);
	}

	@Test
	public void everyGlobalPropertyTheCodeReadsIsDeclaredWithADefault() throws Exception {
		Document config = parse(OMOD.resolve("src/main/resources/config.xml"));
		Set<String> declared = new HashSet<String>();
		NodeList properties = config.getElementsByTagName("globalProperty");
		for (int i = 0; i < properties.getLength(); i++) {
			Element property = (Element) properties.item(i);
			declared.add(property.getElementsByTagName("property").item(0).getTextContent().trim());
			assertTrue("Every global property needs a description an administrator can act on: ",
					property.getElementsByTagName("description").getLength() == 1);
		}

		String constants = read(API.resolve(
				"src/main/java/org/openmrs/module/agentgateway/AgentGatewayConstants.java"));
		Matcher used = Pattern.compile("\"(agentgateway\\.[A-Za-z]+)\"").matcher(constants);
		while (used.find()) {
			assertTrue("Global property used in code but not declared in config.xml: " + used.group(1),
					declared.contains(used.group(1)));
		}
	}

	// ------------------------------------------------------------------ pages

	@Test
	public void everyPageHasAControllerAndEveryControllerHasAPage() throws Exception {
		Set<String> pages = new HashSet<String>();
		for (Path page : listFiles(OMOD.resolve("src/main/webapp/pages"), ".gsp")) {
			pages.add(stripExtension(page.getFileName().toString()));
		}

		Set<String> controllers = new HashSet<String>();
		for (Path controller : javaFilesUnder(
				OMOD.resolve("src/main/java/org/openmrs/module/agentgateway/page/controller"))) {
			String name = stripExtension(controller.getFileName().toString());
			assertTrue("Page controllers must be named XxxPageController: " + name, name.endsWith("PageController"));
			String base = name.substring(0, name.length() - "PageController".length());
			controllers.add(Character.toLowerCase(base.charAt(0)) + base.substring(1));
		}

		assertEquals("Every .gsp needs a matching page controller and vice versa", controllers, pages);
	}

	@Test
	public void everyPageGatesItselfOnAccessDenied() throws Exception {
		for (Path page : listFiles(OMOD.resolve("src/main/webapp/pages"), ".gsp")) {
			String source = read(page);
			assertTrue(page.getFileName() + " does not handle the accessDenied model attribute",
					source.contains("accessDenied"));
		}
	}

	@Test
	public void everyCssClassIsPrefixedSoItCannotCollideWithBootstrap() throws Exception {
		// The Reference Application loads Bootstrap globally; a generic class name silently
		// inherits its styling. This deployment has already lost a UI to that once.
		String css = read(OMOD.resolve("src/main/webapp/resources/styles/agentgateway.css"));
		Matcher selectors = Pattern.compile("^\\.([\\w-]+)", Pattern.MULTILINE).matcher(css);

		List<String> unprefixed = new ArrayList<String>();
		while (selectors.find()) {
			if (!selectors.group(1).startsWith("agent-")) {
				unprefixed.add(selectors.group(1));
			}
		}
		assertTrue("These CSS classes are not agent- prefixed: " + unprefixed, unprefixed.isEmpty());
	}


	@Test
	public void everyPrivilegeDescriptionSurvivesOpenmrsValidation() throws Exception {
		// OpenMRS validates a privilege's description at 250 characters and throws at MODULE
		// START, not at build time. So an over-long one packages cleanly, deploys cleanly, and
		// then fails on a running instance with a ValidationException repeated once per retry -
		// and the privilege is never created, so the feature it gates is invisible with nothing
		// obviously wrong. That happened on the 1.2.0 deploy and cost a restart cycle.
		//
		// Privileges only, deliberately. Both columns are TEXT in the database, so this is a
		// validator rule rather than a schema one, and it does NOT apply to global properties:
		// a 355-character sttChannelSecret description was written successfully in the same
		// deploy that rejected the privilege. Checked against the real database, not assumed.
		Document config = parse(OMOD.resolve("src/main/resources/config.xml"));
		List<String> tooLong = new ArrayList<String>();

		NodeList privileges = config.getElementsByTagName("privilege");
		for (int i = 0; i < privileges.getLength(); i++) {
			Element privilege = (Element) privileges.item(i);
			NodeList descriptions = privilege.getElementsByTagName("description");
			if (descriptions.getLength() == 0) {
				continue;
			}
			String description = descriptions.item(0).getTextContent().trim();
			if (description.length() > 250) {
				tooLong.add(privilege.getElementsByTagName("name").item(0).getTextContent().trim()
						+ " (" + description.length() + " chars)");
			}
		}
		assertTrue("These privilege descriptions exceed OpenMRS's 250-character validation limit and "
				+ "will fail at module start, leaving the privilege uncreated: " + tooLong, tooLong.isEmpty());
	}

	// ------------------------------------------------------------------ dictation

	@Test
	public void dictationNeverSendsAndNeverConfirms() throws Exception {
		// STT-PLAN.md 6.1, and the reason speech-recognition accuracy is a usability property
		// here rather than a safety one: the transcript lands in the compose box as a draft the
		// clinician reads before pressing send, and voice cannot produce the "oui" that
		// authorises a write. Both rules are one careless line away from being broken by someone
		// adding a convenience, so the build checks them rather than a comment asking nicely.
		// Comments are stripped first. The file documents these two rules at length, and naming
		// the forbidden calls while explaining that they are forbidden is exactly the right thing
		// for it to do - a check that punished it for that would push the next person to delete
		// the explanation rather than keep the property.
		String voice = stripJsComments(read(OMOD.resolve("src/main/webapp/resources/scripts/agent-voice.js")));

		List<String> forbidden = new ArrayList<String>();
		for (String call : new String[] { "agentSend(", "agentConfirm(", "agentPost(" }) {
			if (voice.contains(call)) {
				forbidden.add(call);
			}
		}
		assertTrue("agent-voice.js must never send a turn or confirm a write - it may only put text "
				+ "in the compose box. Found: " + forbidden, forbidden.isEmpty());
	}

	/** Removes block and line comments so a check looks at code rather than prose about it. */
	private static String stripJsComments(String source) {
		String withoutBlocks = source.replaceAll("(?s)/\\*.*?\\*/", " ");
		return withoutBlocks.replaceAll("(?m)^\\s*//.*$", " ");
	}

	@Test
	public void theDictationRelayGatesItselfOnItsOwnPrivilege() throws Exception {
		// chat.use is not enough: dictation sends audio to a GPU service, and an administrator
		// must be able to switch that off without taking the assistant away from anyone.
		String controller = read(
				OMOD.resolve("src/main/java/org/openmrs/module/agentgateway/web/controller/TranscribeRelayController.java"));
		assertTrue("TranscribeRelayController must call requireVoiceUse()",
				controller.contains("requireVoiceUse()"));
	}

	@Test
	public void dictationUsesItsOwnChannelSecretAndAudience() throws Exception {
		// The dictation service refuses any request presenting the chat's secret, and a chat token
		// must not be usable to drive the GPU. Reusing either here would quietly remove a boundary
		// that is tested end-to-end on Server 2 but invisible from inside this module.
		String constants = read(
				API.resolve("src/main/java/org/openmrs/module/agentgateway/AgentGatewayConstants.java"));
		assertTrue("A separate STT channel-secret header must be declared",
				constants.contains("HEADER_STT_CHANNEL_SECRET"));
		assertTrue("A separate STT audience must be declared", constants.contains("TOKEN_AUDIENCE_STT"));
		assertTrue("A separate STT purpose must be declared", constants.contains("PURPOSE_STT"));

		String impl = read(
				API.resolve("src/main/java/org/openmrs/module/agentgateway/api/impl/AgentGatewayServiceImpl.java"));
		Matcher mint = Pattern.compile("mintDictationTokenForCurrentUser\\(\\)\\s*\\{(.*?)\\n\\t\\}",
				Pattern.DOTALL).matcher(impl);
		assertTrue("mintDictationTokenForCurrentUser must exist", mint.find());
		String body = mint.group(1);
		assertTrue("a dictation token must be minted for the STT audience",
				body.contains("TOKEN_AUDIENCE_STT"));
		assertTrue("a dictation token must carry purpose=stt", body.contains("PURPOSE_STT"));
		assertTrue("a dictation token must never carry a write capability - dictation reaches no "
				+ "OpenMRS API, so there is nothing for one to authorise", body.contains("false"));
	}

	// ------------------------------------------------------------------ database

	@Test
	public void theLiquibaseChangelogParsesAndEveryChangesetIsIdentified() throws Exception {
		Document changelog = parse(API.resolve("src/main/resources/liquibase.xml"));
		NodeList changesets = changelog.getElementsByTagName("changeSet");
		assertTrue("No changesets found", changesets.getLength() > 0);

		Set<String> ids = new HashSet<String>();
		for (int i = 0; i < changesets.getLength(); i++) {
			Element changeset = (Element) changesets.item(i);
			String id = changeset.getAttribute("id");
			assertFalse("A changeset has no id", id.isEmpty());
			assertFalse("A changeset has no author", changeset.getAttribute("author").isEmpty());
			assertTrue("Duplicate changeset id: " + id, ids.add(id));
		}
	}

	@Test
	public void theAuditTableIsCreatedWithTheColumnsTheMappingExpects() throws Exception {
		String changelog = read(API.resolve("src/main/resources/liquibase.xml"));
		String mapping = read(API.resolve(
				"src/main/resources/org/openmrs/module/agentgateway/api/model/AgentOperationLog.hbm.xml"));

		Matcher columns = Pattern.compile("column=\"(\\w+)\"").matcher(mapping);
		while (columns.find()) {
			String column = columns.group(1);
			assertTrue("Mapped column with no matching Liquibase column: " + column,
					changelog.contains("name=\"" + column + "\""));
		}
	}

	// ------------------------------------------------------------------ helpers

	/** How many endpoints exist at all, counted the crude way the pattern above cannot fool. */
	private int countRequestMappings() throws Exception {
		int total = 0;
		for (Path controller : javaFilesUnder(OMOD.resolve("src/main/java/org/openmrs/module/agentgateway/web/controller"))) {
			Matcher annotations = Pattern.compile("@RequestMapping\\(").matcher(read(controller));
			while (annotations.find()) {
				total++;
			}
		}
		return total;
	}

	private Set<String> declaredPrivileges() throws Exception {
		Document config = parse(OMOD.resolve("src/main/resources/config.xml"));
		NodeList privileges = config.getElementsByTagName("privilege");
		Set<String> declared = new HashSet<String>();
		for (int i = 0; i < privileges.getLength(); i++) {
			Element privilege = (Element) privileges.item(i);
			declared.add(privilege.getElementsByTagName("name").item(0).getTextContent().trim());
		}
		return declared;
	}

	private Document parse(Path file) throws Exception {
		assertTrue("Missing file: " + file, Files.exists(file));
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setValidating(false);
		factory.setNamespaceAware(false);
		DocumentBuilder builder = factory.newDocumentBuilder();
		// Module DTDs and the Liquibase schema are not fetched over the network at test time.
		builder.setEntityResolver(new org.xml.sax.EntityResolver() {

			@Override
			public InputSource resolveEntity(String publicId, String systemId) {
				return new InputSource(new java.io.StringReader(""));
			}
		});
		Reader reader = new InputStreamReader(new FileInputStream(file.toFile()), UTF_8);
		try {
			return builder.parse(new InputSource(reader));
		}
		finally {
			reader.close();
		}
	}

	private String read(Path file) throws Exception {
		assertTrue("Missing file: " + file, Files.exists(file));
		return new String(Files.readAllBytes(file), UTF_8);
	}

	private List<Path> javaFilesUnder(Path directory) throws Exception {
		return listFiles(directory, ".java");
	}

	private List<Path> listFiles(Path directory, String suffix) throws Exception {
		List<Path> found = new ArrayList<Path>();
		File[] children = directory.toFile().listFiles();
		assertNotNull("Expected directory: " + directory, children);
		for (File child : children) {
			if (child.isDirectory()) {
				found.addAll(listFiles(child.toPath(), suffix));
			} else if (child.getName().endsWith(suffix)) {
				found.add(child.toPath());
			}
		}
		return found;
	}

	private String stripExtension(String fileName) {
		int dot = fileName.lastIndexOf('.');
		return dot < 0 ? fileName : fileName.substring(0, dot);
	}

	private String firstMatch(String source, String regex) {
		Matcher matcher = Pattern.compile(regex, Pattern.DOTALL).matcher(source);
		return matcher.find() ? matcher.group(1) : null;
	}

	/**
	 * The stylesheet has to sit where {@code ui.includeCss} looks for it.
	 *
	 * It did not. The UI framework resolves {@code ui.includeCss(provider, file)} to
	 * {@code /moduleResources/<provider>/styles/<file>} and {@code ui.includeJavascript} to
	 * {@code .../scripts/<file>}. The javascript was in {@code resources/scripts/} and loaded; the
	 * stylesheet was in {@code resources/css/} and returned 404 on every page - the chat panel, the
	 * widget and the administrator's operation log. The chat therefore worked perfectly while being
	 * entirely unstyled, which read as "nobody designed a distinction between the clinician's
	 * messages and the assistant's" when {@code .agent-message-user} and {@code .agent-message-bot}
	 * had been there all along.
	 */
	@Test
	public void stylesheetIsWhereTheUiFrameworkLooksForIt() {
		assertTrue("agentgateway.css must live in resources/styles/, not resources/css/",
				new File("src/main/webapp/resources/styles/agentgateway.css").isFile());
		assertFalse("a stylesheet in resources/css/ is never served",
				new File("src/main/webapp/resources/css/agentgateway.css").isFile());
	}
}