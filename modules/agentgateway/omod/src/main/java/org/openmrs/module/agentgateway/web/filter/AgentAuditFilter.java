package org.openmrs.module.agentgateway.web.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang.StringUtils;
import org.openmrs.User;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.UserContext;
import org.openmrs.web.WebConstants;
import org.openmrs.module.agentgateway.AgentGatewayConfig;
import org.openmrs.module.agentgateway.AgentGatewayConstants;
import org.openmrs.module.agentgateway.AgentGatewayPrivileges;
import org.openmrs.module.agentgateway.api.AgentGatewayService;
import org.openmrs.module.agentgateway.api.model.AgentOperationLog;
import org.openmrs.module.agentgateway.http.HttpJsonClient;
import org.openmrs.module.agentgateway.rollback.DelegatedApiCaller;
import org.openmrs.module.agentgateway.rollback.OperationTarget;
import org.openmrs.module.agentgateway.security.DelegatedAuthenticationScheme;
import org.openmrs.module.agentgateway.security.DelegatedCredentials;
import org.openmrs.module.agentgateway.security.DelegatedToken;
import org.openmrs.module.agentgateway.security.TokenException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.Date;

/**
 * The single point where an agent-originated call becomes an authenticated, privilege-checked,
 * audited OpenMRS request - and the only OpenMRS-side code that runs on that path (ADR-5).
 * <p>
 * It is mapped on {@code /*} rather than on the REST and FHIR prefixes directly, for two
 * reasons. The prefixes are a configurable list (a deployment that wants the assistant to reach
 * its department-specific endpoints adds them without repackaging this module), and a filter
 * mapping is fixed at module-load time while a global property is not. The cost of that is one
 * {@code getHeader} on every request in the system, which is why the very first thing this does
 * is return when that header is absent - the overwhelming majority of requests never get past
 * line one.
 * <p>
 * <b>What it enforces, in order.</b> The path must be one an agent is allowed to target at all.
 * The token must verify against this instance's own signing key and still be within its few
 * minutes of life. A token minted for reading may not write, a token minted for a chat turn may
 * not be replayed into the rollback path, and a write additionally requires the privilege that
 * matches the token's purpose - re-checked here rather than trusted from the token, so revoking
 * a clinician's chat-write access takes effect immediately rather than when their current token
 * happens to expire. Only then does the request run, as that clinician, with every ordinary
 * OpenMRS privilege check applying unchanged.
 * <p>
 * <b>What it does not do.</b> It never decides whether an operation is clinically sensible, it
 * never grants a privilege the user does not already hold, and it never lets a failure of its own
 * silently pass a request through unaudited: if the audit row cannot be written the call has
 * already happened, so the failure is logged loudly to the server log rather than hidden.
 */
public class AgentAuditFilter implements Filter {

	private static final Logger log = LoggerFactory.getLogger(AgentAuditFilter.class);

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** Bodies above this are streamed straight through and recorded as omitted rather than buffered. */
	private static final int MAX_BUFFERED_BODY_BYTES = 1024 * 1024;

	private static final String FHIR2_R4_PREFIX = "/ws/fhir2/R4";

	private static final String FHIR2_R3_PREFIX = "/ws/fhir2/R3";

	@Override
	public void init(FilterConfig filterConfig) {
	}

	@Override
	public void destroy() {
	}

	@Override
	public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain)
			throws IOException, ServletException {

		if (!(servletRequest instanceof HttpServletRequest) || !(servletResponse instanceof HttpServletResponse)) {
			chain.doFilter(servletRequest, servletResponse);
			return;
		}

		HttpServletRequest request = (HttpServletRequest) servletRequest;
		HttpServletResponse response = (HttpServletResponse) servletResponse;

		String token = request.getHeader(AgentGatewayConstants.HEADER_AGENT_TOKEN);
		if (StringUtils.isBlank(token)) {
			chain.doFilter(servletRequest, servletResponse);
			return;
		}

		handleAgentRequest(request, response, chain, token);
	}

	private void handleAgentRequest(HttpServletRequest request, HttpServletResponse response, FilterChain chain,
			String token) throws IOException, ServletException {

		String requestPath = pathWithinContext(request);
		boolean relayed = requestPath.startsWith(AgentGatewayConstants.RELAY_PATH_PREFIX);
		String path = relayed ? requestPath.substring(AgentGatewayConstants.RELAY_PATH_PREFIX.length())
				: requestPath;

		if (!AgentGatewayConfig.isPathAudited(path)) {
			deny(response, HttpServletResponse.SC_FORBIDDEN,
					"The assistant is not allowed to call this part of OpenMRS");
			log.warn("agentgateway: refused an agent-tagged call to an unlisted path: {} {}", request.getMethod(),
					path);
			return;
		}

		AgentGatewayService service = Context.getService(AgentGatewayService.class);
		DelegatedToken verified;
		try {
			verified = service.verifyDelegatedToken(token);
		}
		catch (TokenException e) {
			// No trustworthy identity means no attributable audit row to write; the rejection is
			// recorded in the server log instead, where it is an operational signal, not PHI.
			log.warn("agentgateway: rejected an agent-tagged call to {} {}: {}", request.getMethod(), path,
					e.getMessage());
			deny(response, HttpServletResponse.SC_UNAUTHORIZED, "The assistant's authorisation has expired");
			return;
		}

		boolean isWrite = isWriteMethod(request.getMethod());
		if (isWrite && AgentGatewayConstants.PURPOSE_INTERNAL_READ.equals(verified.getPurpose())) {
			deny(response, HttpServletResponse.SC_FORBIDDEN, "This authorisation is read-only");
			return;
		}
		if (isWrite && !verified.mayWrite()) {
			deny(response, HttpServletResponse.SC_FORBIDDEN,
					"You are not authorised to have changes saved through the assistant");
			return;
		}

		UserContext previousUserContext = currentUserContextOrNull();
		HttpSession seededSession = null;
		try {
			UserContext delegated = new UserContext(new DelegatedAuthenticationScheme());
			delegated.authenticate(DelegatedCredentials.forVerifiedToken(verified));
			Context.setUserContext(delegated);

			if (isWrite && !Context.hasPrivilege(requiredWritePrivilege(verified))) {
				deny(response, HttpServletResponse.SC_FORBIDDEN,
						"You are not authorised to have changes saved through the assistant");
				return;
			}

			if (relayed) {
				// OpenmrsFilter *is* mapped for FORWARD, and it re-reads the user context from the
				// HTTP session - so on the forward below it would replace the delegated context
				// with a fresh, unauthenticated one, and the call would fail just as surely as it
				// does without the relay. Seeding the session makes OpenmrsFilter re-install this
				// same context instead of overwriting it.
				//
				// Safe because each agent call arrives on its own cookie-less connection: the
				// session is created here, used by this one request, and invalidated below. It is
				// never a session a browser could join.
				seededSession = request.getSession(true);
				seededSession.setAttribute(WebConstants.OPENMRS_USER_CONTEXT_HTTPSESSION_ATTR, delegated);
			}

			executeAndAudit(request, response, chain, verified, path, isWrite, relayed);
		}
		catch (org.openmrs.api.context.ContextAuthenticationException e) {
			log.warn("agentgateway: delegated token names a user that cannot be authenticated: {}", e.getMessage());
			deny(response, HttpServletResponse.SC_UNAUTHORIZED, "The assistant's authorisation is no longer valid");
		}
		finally {
			if (seededSession != null) {
				try {
					// Not tidiness: one session per chat turn, never invalidated, is a leak that
					// grows for as long as the assistant is used.
					seededSession.invalidate();
				}
				catch (IllegalStateException alreadyInvalidated) {
					log.debug("agentgateway: the relay session was already invalidated");
				}
			}
			if (previousUserContext != null) {
				Context.setUserContext(previousUserContext);
			}
		}
	}

	private void executeAndAudit(HttpServletRequest request, HttpServletResponse response, FilterChain chain,
			DelegatedToken verified, String path, boolean isWrite, boolean relayed)
			throws IOException, ServletException {

		String fullPath = path + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
		OperationTarget target = OperationTarget.parse(request.getMethod() + " " + fullPath);

		HttpServletRequest effectiveRequest = request;
		String requestBody = null;
		if (isWrite && request.getContentLength() >= 0 && request.getContentLength() <= MAX_BUFFERED_BODY_BYTES) {
			BufferedRequestWrapper buffered = new BufferedRequestWrapper(request);
			effectiveRequest = buffered;
			requestBody = buffered.getBodyAsString();
		} else if (isWrite) {
			requestBody = "(body not recorded: too large or of unknown length)";
		}

		String previousState = captureBeforeState(verified, target, isWrite);

		int captureLimit = AgentGatewayConfig.getMaxLoggedBodyChars();
		CapturingResponseWrapper capturing = new CapturingResponseWrapper(response, captureLimit);

		boolean internal = "true".equalsIgnoreCase(request.getHeader(AgentGatewayConstants.HEADER_INTERNAL_CALL));
		try {
			if (relayed) {
				// Not chain.doFilter: the module filters still ahead of us include fhir2's own
				// authentication gate, which start order has already decided against us. A forward
				// re-enters at the servlet, and module filters are not mapped for FORWARD, so the
				// real fhir2 servlet serves the request under the delegated user.
				request.getRequestDispatcher(dispatchTarget(path, request.getQueryString()))
						.forward(effectiveRequest, capturing);
			} else {
				chain.doFilter(effectiveRequest, capturing);
			}
		}
		finally {
			try {
				capturing.flushBuffer();
			}
			catch (Exception e) {
				// A response the container already committed cannot be flushed again, and that
				// must not be allowed to swallow the audit row for a call that did happen.
				log.debug("agentgateway: response was already committed when flushing", e);
			}
			if (!internal) {
				recordOperation(request, capturing, verified, fullPath, target, requestBody, previousState,
						captureLimit);
			}
		}
	}

	/**
	 * Reads the record's current contents back before the agent overwrites them, so a rollback
	 * has something real to restore rather than a guess (CA9). Only meaningful for an update to
	 * an identified record - a create has no "before", and a body-less call changes nothing that
	 * needs restoring.
	 */
	private String captureBeforeState(DelegatedToken verified, OperationTarget target, boolean isWrite) {
		if (!isWrite || target == null || !AgentGatewayConfig.isCaptureBeforeStateEnabled()) {
			return null;
		}
		if (target.getKind() != OperationTarget.Kind.UPDATE || target.getResourceId() == null) {
			return null;
		}

		try {
			String readPath = target.getFamily() == OperationTarget.Family.REST ? target.getPath() + "?v=full"
					: target.getPath();
			HttpJsonClient.Response before = DelegatedApiCaller
					.readOnly(verified.getUsername(), verified.getUserUuid(), verified.getConversationId())
					.call("GET", readPath, null, true);
			return before.isSuccessful() ? before.getBody() : null;
		}
		catch (Exception e) {
			// Not fatal: the write still goes ahead, the log records that no before-image exists,
			// and the rollback engine will refuse to auto-reverse it for exactly that reason.
			log.warn("agentgateway: could not capture the before-state of {}", target.getPath(), e);
			return null;
		}
	}

	private void recordOperation(HttpServletRequest request, CapturingResponseWrapper response, DelegatedToken verified,
			String fullPath, OperationTarget target, String requestBody, String previousState, int captureLimit) {

		try {
			User actingUser = Context.getAuthenticatedUser();
			if (actingUser == null) {
				log.error("agentgateway: an agent-originated call to {} completed with no authenticated user; it "
						+ "could not be recorded", fullPath);
				return;
			}

			String responseBody = response.getCapturedBody();

			AgentOperationLog operation = new AgentOperationLog();
			operation.setConversationId(verified.getConversationId());
			operation.setActingUser(actingUser);
			operation.setCreator(actingUser);
			operation.setDateCreated(new Date());
			operation.setRawPrompt(decodeHeader(request.getHeader(AgentGatewayConstants.HEADER_RAW_PROMPT)));
			String taskType = request.getHeader(AgentGatewayConstants.HEADER_TASK_TYPE);
			operation.setTaskType(StringUtils.isBlank(taskType) ? "unspecified" : taskType);
			operation.setTargetEndpoint(request.getMethod() + " " + fullPath);
			operation.setRequestBody(truncate(requestBody, captureLimit));
			operation.setResponseStatus(response.getStatus());
			operation.setResponseBody(truncate(responseBody, captureLimit));
			operation.setPreviousState(truncate(previousState, captureLimit));
			operation.setResourceUuidAffected(resolveAffectedResource(responseBody, target));
			operation.setUsingAgent(Boolean.TRUE);
			operation.setReversesLogId(parseInteger(request.getHeader(AgentGatewayConstants.HEADER_REVERSES_LOG_ID)));

			applyReversibility(operation, target, previousState, response.getStatus());

			Context.getService(AgentGatewayService.class).recordOperation(operation);
		}
		catch (Exception e) {
			// The clinical call has already happened. Failing loudly here is the only honest
			// option - swallowing it would leave a change in the database with no trace of who
			// made it or how.
			log.error("agentgateway: FAILED TO RECORD an agent-originated call to {}. The call itself completed; "
					+ "this audit gap needs investigating.", fullPath, e);
		}
	}

	/**
	 * A first, cheap opinion on whether this row could be reversed, recorded for the
	 * administrator's list view. The binding decision is made again from live data at the moment
	 * a rollback is attempted - a row that looks reversible now may not be in an hour.
	 */
	private void applyReversibility(AgentOperationLog operation, OperationTarget target, String previousState,
			int status) {
		if (status < 200 || status >= 300) {
			operation.setReversible(Boolean.FALSE);
			operation.setReversibilityNote("The call did not succeed, so there is nothing to reverse");
			return;
		}
		if (target == null || !target.isWrite()) {
			operation.setReversible(Boolean.FALSE);
			operation.setReversibilityNote("Read-only lookup");
			return;
		}
		if (StringUtils.isBlank(operation.getResourceUuidAffected())) {
			operation.setReversible(Boolean.FALSE);
			operation.setReversibilityNote("The affected record could not be identified from the response");
			return;
		}
		switch (target.getKind()) {
			case CREATE:
				operation.setReversible(Boolean.TRUE);
				operation.setReversibilityNote("Reversible by voiding the created record, if nothing depends on it "
						+ "by then");
				break;
			case UPDATE:
				boolean haveBefore = StringUtils.isNotBlank(previousState);
				operation.setReversible(haveBefore);
				operation.setReversibilityNote(haveBefore
						? "Reversible by writing the previous values back, if the record has not changed since"
						: "No before-image was captured, so this cannot be reversed automatically");
				break;
			default:
				operation.setReversible(Boolean.FALSE);
				operation.setReversibilityNote("Restoring a voided record is not exposed over the REST API");
		}
	}

	/**
	 * OpenMRS answers a REST create with {@code uuid} and a FHIR create with {@code id}. Falling
	 * back to the id already in the URL covers updates, where the response may be empty.
	 */
	private String resolveAffectedResource(String responseBody, OperationTarget target) {
		if (StringUtils.isNotBlank(responseBody)) {
			try {
				JsonNode parsed = MAPPER.readTree(responseBody);
				String uuid = parsed.path("uuid").asText(null);
				if (StringUtils.isBlank(uuid)) {
					uuid = parsed.path("id").asText(null);
				}
				if (StringUtils.isNotBlank(uuid)) {
					return uuid;
				}
			}
			catch (Exception e) {
				log.debug("agentgateway: response body was not JSON, falling back to the URL for the resource id");
			}
		}
		return target == null ? null : target.getResourceId();
	}

	private String requiredWritePrivilege(DelegatedToken verified) {
		return AgentGatewayConstants.PURPOSE_ROLLBACK.equals(verified.getPurpose())
				? AgentGatewayPrivileges.ROLLBACK
				: AgentGatewayPrivileges.CHAT_WRITE;
	}

	private UserContext currentUserContextOrNull() {
		try {
			return Context.getUserContext();
		}
		catch (Exception e) {
			return null;
		}
	}

	/**
	 * Where to forward, for the target path the agent asked for.
	 * <p>
	 * {@code fhir2} does not serve {@code /ws/fhir2/R4/*} itself - its own {@code ForwardingFilter}
	 * rewrites that to {@code /ms/fhir2Servlet/*}, dropping the version segment. That filter is a
	 * module filter too, so it does not run on our forward either, and the rewrite has to be done
	 * here. {@code /ws/rest/v1/*} needs no translation: web.xml maps {@code /ws/*} to OpenMRS's own
	 * DispatcherServlet, and servlet mappings do apply to a forward.
	 */
	private String dispatchTarget(String path, String queryString) {
		String translated = path;
		if (path.startsWith(FHIR2_R4_PREFIX)) {
			translated = AgentGatewayConstants.FHIR2_R4_SERVLET_PATH + path.substring(FHIR2_R4_PREFIX.length());
		} else if (path.startsWith(FHIR2_R3_PREFIX)) {
			translated = AgentGatewayConstants.FHIR2_R3_SERVLET_PATH + path.substring(FHIR2_R3_PREFIX.length());
		}
		return StringUtils.isBlank(queryString) ? translated : translated + "?" + queryString;
	}

	private String pathWithinContext(HttpServletRequest request) {
		String uri = request.getRequestURI();
		String contextPath = request.getContextPath();
		return contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)
				? uri.substring(contextPath.length())
				: uri;
	}

	private boolean isWriteMethod(String method) {
		return !"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)
				&& !"OPTIONS".equalsIgnoreCase(method);
	}

	private void deny(HttpServletResponse response, int status, String message) throws IOException {
		response.setStatus(status);
		response.setContentType("application/json;charset=UTF-8");
		response.getWriter().write("{\"error\":{\"message\":\"" + message.replace("\"", "'") + "\"}}");
		response.getWriter().flush();
	}

	private String decodeHeader(String value) {
		if (StringUtils.isBlank(value)) {
			return null;
		}
		try {
			return URLDecoder.decode(value, "UTF-8");
		}
		catch (UnsupportedEncodingException | IllegalArgumentException e) {
			return value;
		}
	}

	private Integer parseInteger(String value) {
		try {
			return StringUtils.isBlank(value) ? null : Integer.valueOf(value.trim());
		}
		catch (NumberFormatException e) {
			return null;
		}
	}

	private String truncate(String value, int limit) {
		if (value == null) {
			return null;
		}
		return value.length() <= limit ? value : value.substring(0, limit) + "... (truncated)";
	}
}
