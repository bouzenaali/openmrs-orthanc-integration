package org.openmrs.module.agentgateway;

import org.apache.commons.lang.StringUtils;
import org.openmrs.api.context.Context;
import org.openmrs.util.PrivilegeConstants;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reads this module's settings from OpenMRS global properties, applying the documented default
 * when a property is missing or unparseable rather than failing the request.
 */
public final class AgentGatewayConfig {

	private AgentGatewayConfig() {
	}

	public static String getAgentServiceUrl() {
		return stripTrailingSlash(
				getString(AgentGatewayConstants.GP_AGENT_SERVICE_URL, AgentGatewayConstants.DEFAULT_AGENT_SERVICE_URL));
	}

	public static String getChannelSecret() {
		return getString(AgentGatewayConstants.GP_CHANNEL_SECRET, "");
	}

	public static int getAgentTimeoutMillis() {
		return getInt(AgentGatewayConstants.GP_AGENT_TIMEOUT_MS, AgentGatewayConstants.DEFAULT_AGENT_TIMEOUT_MS);
	}

	public static int getTokenTtlSeconds() {
		return getInt(AgentGatewayConstants.GP_TOKEN_TTL_SECONDS, AgentGatewayConstants.DEFAULT_TOKEN_TTL_SECONDS);
	}

	public static String getSelfBaseUrl() {
		return stripTrailingSlash(
				getString(AgentGatewayConstants.GP_SELF_BASE_URL, AgentGatewayConstants.DEFAULT_SELF_BASE_URL));
	}

	public static boolean isCaptureBeforeStateEnabled() {
		return !"false".equalsIgnoreCase(getString(AgentGatewayConstants.GP_CAPTURE_BEFORE_STATE, "true").trim());
	}

	public static int getMaxLoggedBodyChars() {
		return getInt(AgentGatewayConstants.GP_MAX_LOGGED_BODY_CHARS,
				AgentGatewayConstants.DEFAULT_MAX_LOGGED_BODY_CHARS);
	}

	// ---------------------------------------------------------------- dictation

	public static String getSttServiceUrl() {
		return stripTrailingSlash(
				getString(AgentGatewayConstants.GP_STT_SERVICE_URL, AgentGatewayConstants.DEFAULT_STT_SERVICE_URL));
	}

	public static String getSttChannelSecret() {
		return getString(AgentGatewayConstants.GP_STT_CHANNEL_SECRET, "");
	}

	public static int getSttTimeoutMillis() {
		return getInt(AgentGatewayConstants.GP_STT_TIMEOUT_MS, AgentGatewayConstants.DEFAULT_STT_TIMEOUT_MS);
	}

	/**
	 * Whether dictation is configured well enough to offer at all.
	 * <p>
	 * The microphone button is hidden when this is false, rather than shown and then failing: a
	 * button that does nothing is worse than no button. Deliberately also false when the dictation
	 * secret matches the agent's - separate secrets are what stop a compromise of the dictation
	 * service reaching /chat, so a copy-paste of the wrong value disables dictation rather than
	 * quietly removing that boundary.
	 */
	public static boolean isDictationConfigured() {
		String secret = getSttChannelSecret();
		if (secret.isEmpty() || getSttServiceUrl().isEmpty()) {
			return false;
		}
		return !secret.equals(getChannelSecret());
	}

	/**
	 * The URI prefixes an agent-originated call is allowed to target. A token is only ever a
	 * delegation of the clinician's privileges over this surface - it is not a general-purpose
	 * OpenMRS credential, so anything outside these prefixes is refused before OpenMRS even sees
	 * the request.
	 */
	public static List<String> getAuditedPathPrefixes() {
		String raw = getString(AgentGatewayConstants.GP_AUDITED_PATH_PREFIXES,
				AgentGatewayConstants.DEFAULT_AUDITED_PATH_PREFIXES);
		List<String> prefixes = new ArrayList<String>();
		for (String candidate : raw.split(",")) {
			String trimmed = candidate.trim();
			if (!trimmed.isEmpty()) {
				prefixes.add(trimmed);
			}
		}
		return prefixes.isEmpty() ? Collections.<String> emptyList() : prefixes;
	}

	public static boolean isPathAudited(String pathWithinContext) {
		if (pathWithinContext == null) {
			return false;
		}
		for (String prefix : getAuditedPathPrefixes()) {
			if (pathWithinContext.startsWith(prefix)) {
				return true;
			}
		}
		return false;
	}

	private static String getString(String property, String defaultValue) {
		String value = Context.getAdministrationService().getGlobalProperty(property);
		return StringUtils.isBlank(value) ? defaultValue : value.trim();
	}

	private static int getInt(String property, int defaultValue) {
		String value = Context.getAdministrationService().getGlobalProperty(property);
		if (StringUtils.isBlank(value)) {
			return defaultValue;
		}
		try {
			return Integer.parseInt(value.trim());
		}
		catch (NumberFormatException e) {
			return defaultValue;
		}
	}

	/**
	 * Writes a global property from a context that may not have an authenticated user (module
	 * startup), which is why the privilege is proxied for the duration of the write only.
	 */
	public static void saveGlobalPropertyAsSystem(String property, String value) {
		try {
			Context.addProxyPrivilege(PrivilegeConstants.MANAGE_GLOBAL_PROPERTIES);
			Context.getAdministrationService().setGlobalProperty(property, value);
		}
		finally {
			Context.removeProxyPrivilege(PrivilegeConstants.MANAGE_GLOBAL_PROPERTIES);
		}
	}

	public static String getGlobalPropertyAsSystem(String property) {
		try {
			Context.addProxyPrivilege(PrivilegeConstants.GET_GLOBAL_PROPERTIES);
			return Context.getAdministrationService().getGlobalProperty(property);
		}
		finally {
			Context.removeProxyPrivilege(PrivilegeConstants.GET_GLOBAL_PROPERTIES);
		}
	}

	private static String stripTrailingSlash(String url) {
		String trimmed = url.trim();
		while (trimmed.endsWith("/")) {
			trimmed = trimmed.substring(0, trimmed.length() - 1);
		}
		return trimmed;
	}
}
