package org.openmrs.module.agentgateway;

/**
 * Names of every global property, HTTP header and tunable this module reads. Nothing about the
 * deployment (agent URL, channel secret, token lifetime, which URL prefixes are audited) is
 * hardcoded - it is all administrator-editable from Administration &gt; Settings.
 */
public final class AgentGatewayConstants {

	public static final String MODULE_ID = "agentgateway";

	// ---------------------------------------------------------------- global properties

	/** Base URL of the Clinical Agent Service on Server 2, e.g. https://agent.hospital.lan:8443 */
	public static final String GP_AGENT_SERVICE_URL = "agentgateway.agentServiceUrl";

	/**
	 * Shared channel secret proving a /chat request genuinely came from this OpenMRS instance
	 * (ADR-9, "channel trust"). Server-to-server only - it is never rendered into a page and
	 * never leaves this module.
	 */
	public static final String GP_CHANNEL_SECRET = "agentgateway.channelSecret";

	/** Connect/read timeout in milliseconds for the relay call to the agent service. */
	public static final String GP_AGENT_TIMEOUT_MS = "agentgateway.agentTimeoutMillis";

	/** RSA private key (PKCS#8, base64) used to sign delegated tokens. Auto-generated on first start. */
	public static final String GP_SIGNING_PRIVATE_KEY = "agentgateway.signingPrivateKey";

	/** RSA public key (X.509, base64) the agent service uses to verify delegated tokens. */
	public static final String GP_SIGNING_PUBLIC_KEY = "agentgateway.signingPublicKey";

	/** Lifetime of a delegated token, in seconds. Deliberately short (ADR-9: "a few minutes"). */
	public static final String GP_TOKEN_TTL_SECONDS = "agentgateway.tokenTtlSeconds";

	/**
	 * Comma-separated URI prefixes (relative to the OpenMRS context path) on which the audit
	 * filter is allowed to act. Anything outside this list is refused even with a valid token,
	 * so a stolen token cannot be pointed at, say, the admin servlet.
	 */
	public static final String GP_AUDITED_PATH_PREFIXES = "agentgateway.auditedPathPrefixes";

	/**
	 * Base URL this module uses to read a resource's current state back before an agent
	 * overwrites it, so an administrator has a real before-image to restore from (CA9).
	 */
	public static final String GP_SELF_BASE_URL = "agentgateway.selfBaseUrl";

	/** Whether to capture that before-image at all. Turning it off makes updates non-reversible. */
	public static final String GP_CAPTURE_BEFORE_STATE = "agentgateway.captureBeforeState";

	/** Maximum number of characters of any single request/response body kept in the log. */
	public static final String GP_MAX_LOGGED_BODY_CHARS = "agentgateway.maxLoggedBodyChars";

	// ---------------------------------------------------------------- dictation

	/** Base URL of the Clinical Dictation Service on Server 2, e.g. https://stt.hospital.lan */
	public static final String GP_STT_SERVICE_URL = "agentgateway.sttServiceUrl";

	/**
	 * Shared secret proving a relayed dictation genuinely came from this OpenMRS instance.
	 * <p>
	 * Must be a <em>different</em> value from {@link #GP_CHANNEL_SECRET}. The dictation service
	 * refuses to start if the two match, and refuses any request presenting the agent's secret.
	 */
	public static final String GP_STT_CHANNEL_SECRET = "agentgateway.sttChannelSecret";

	/** Connect/read timeout in milliseconds for the relay call to the dictation service. */
	public static final String GP_STT_TIMEOUT_MS = "agentgateway.sttTimeoutMillis";

	// ---------------------------------------------------------------- defaults

	public static final String DEFAULT_AGENT_SERVICE_URL = "http://localhost:8000";

	public static final int DEFAULT_AGENT_TIMEOUT_MS = 30000;

	public static final int DEFAULT_TOKEN_TTL_SECONDS = 300;

	public static final String DEFAULT_AUDITED_PATH_PREFIXES = "/ws/rest/v1/,/ws/fhir2/R4/";

	public static final String DEFAULT_SELF_BASE_URL = "http://localhost:8080/openmrs";

	public static final int DEFAULT_MAX_LOGGED_BODY_CHARS = 20000;

	public static final String DEFAULT_STT_SERVICE_URL = "https://stt.hospital.lan";

	/**
	 * 20 seconds. A 30-second utterance transcribes in well under one second on the deployed
	 * hardware (measured: 17 s of audio in 0.23 s), so this is a stuck-connection backstop rather
	 * than a working bound. Must stay below the proxy's own read timeout.
	 */
	public static final int DEFAULT_STT_TIMEOUT_MS = 20000;

	/**
	 * Hard ceiling on a relayed dictation, in bytes: 30 seconds of 16 kHz mono 16-bit PCM.
	 * The dictation service enforces the same limit and answers 413; this stops an oversized body
	 * being buffered inside Tomcat before it ever leaves Server 1.
	 */
	public static final int MAX_DICTATION_BYTES = 16000 * 30 * 2;

	/**
	 * Prefix the agent puts in front of the real path it wants to reach, e.g.
	 * {@code /module/agentgateway/relay/ws/fhir2/R4/Patient?name=…}.
	 * <p>
	 * The agent cannot call {@code /ws/fhir2/*} directly: {@code fhir2} registers its own
	 * authentication filter there, and because {@code fhir2} is a bundled module it starts - and
	 * so registers that filter - before this one. Module filters run in start order inside
	 * web.xml's single {@code ModuleFilter}, so fhir2 answers 401 before this module has had a
	 * chance to authenticate the delegated user. The one path that ever worked was
	 * {@code /metadata}, which fhir2 exempts.
	 * <p>
	 * Calling through this prefix instead lands on a path fhir2 does not guard; the audit filter
	 * then authenticates the clinician and <em>forwards</em> to the real servlet. Module filters
	 * are not mapped for {@code FORWARD}, so fhir2's gate is not consulted again - while the real
	 * fhir2 servlet still serves the request and OpenMRS's own privilege checks still run as that
	 * clinician.
	 */
	public static final String RELAY_PATH_PREFIX = "/module/agentgateway/relay";

	/** Where {@code fhir2} actually serves R4, per its own {@code FhirConstants}. */
	public static final String FHIR2_R4_SERVLET_PATH = "/ms/fhir2Servlet";

	/** Where {@code fhir2} actually serves R3. */
	public static final String FHIR2_R3_SERVLET_PATH = "/ms/fhir2R3Servlet";

	// ---------------------------------------------------------------- HTTP headers

	/** Carries the delegated token on every agent-originated REST/FHIR call. */
	public static final String HEADER_AGENT_TOKEN = "X-OpenMRS-Agent-Token";

	/** Ties a logged operation back to the chat conversation that produced it. */
	public static final String HEADER_CONVERSATION_ID = "X-OpenMRS-Agent-Conversation";

	/** The agent's own classification of the turn (add_patient, query, ...), recorded as-is. */
	public static final String HEADER_TASK_TYPE = "X-OpenMRS-Agent-Task";

	/** The clinician's original words for this turn, URL-encoded. Recorded for the audit trail. */
	public static final String HEADER_RAW_PROMPT = "X-OpenMRS-Agent-Prompt";

	/** Channel secret header on the OpenMRS -> agent leg and on the public-key endpoint. */
	public static final String HEADER_CHANNEL_SECRET = "X-Agent-Channel-Key";

	/**
	 * The dictation service's own channel header. A different header carrying a different secret:
	 * presenting the chat's key here is refused, and presenting this one at /chat is refused too.
	 */
	public static final String HEADER_STT_CHANNEL_SECRET = "X-Stt-Channel-Key";

	/** On a reversing call, the id of the logged operation being reversed. */
	public static final String HEADER_REVERSES_LOG_ID = "X-OpenMRS-Agent-Reverses";

	/**
	 * Marks a call this module made to itself (reading a resource's before-state). Such calls
	 * are still authenticated and still confined to the audited prefixes, but are not written to
	 * the log - they change nothing, and logging them would bury the operations that did.
	 */
	public static final String HEADER_INTERNAL_CALL = "X-OpenMRS-Agent-Internal";

	// ---------------------------------------------------------------- token claims

	public static final String TOKEN_AUDIENCE = "clinical-agent-service";

	public static final String TOKEN_ISSUER = "openmrs-agentgateway";

	/** Claim carrying whether this user may have writes executed on their behalf through the chat. */
	public static final String CLAIM_MAY_WRITE = "may_write";

	public static final String CLAIM_CONVERSATION_ID = "cid";

	public static final String CLAIM_USER_UUID = "user_uuid";

	/**
	 * What the token was minted for. A chat token can only ever be used for what the clinician
	 * confirmed in a conversation; a rollback token is minted for an administrator undoing one
	 * logged operation, and is authorised by a different privilege. Keeping them apart means an
	 * administrator does not need chat-write access to reverse somebody else's mistake, and a
	 * clinician's chat token can never be replayed into the rollback path.
	 */
	public static final String CLAIM_PURPOSE = "purpose";

	public static final String PURPOSE_CHAT = "chat";

	public static final String PURPOSE_ROLLBACK = "rollback";

	/** Reading a resource's state back before an agent write overwrites it. Read-only by design. */
	public static final String PURPOSE_INTERNAL_READ = "internal_read";

	/**
	 * One dictation being transcribed. Carries no write capability and reaches no OpenMRS API:
	 * the dictation service turns audio into text and hands it back. It exists so the service can
	 * rate-limit per clinician - every request arrives from this server's single address, so the
	 * proxy's limit bounds the hospital rather than a user - and so a dictation can be attributed.
	 */
	public static final String PURPOSE_STT = "stt";

	/**
	 * The dictation service's audience, deliberately not {@link #TOKEN_AUDIENCE}.
	 * <p>
	 * A chat token must not be usable to drive the GPU, and a dictation token must not open a chat
	 * turn. The two services also hold different channel secrets, for the same reason: the
	 * dictation service decodes attacker-shaped binary audio through a model runtime, which is a
	 * larger attack surface than the agent's JSON, and a compromise there must not reach /chat.
	 */
	public static final String TOKEN_AUDIENCE_STT = "stt-service";

	private AgentGatewayConstants() {
	}
}
