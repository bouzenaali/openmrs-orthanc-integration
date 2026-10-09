package org.openmrs.module.agentgateway.web.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang.StringUtils;
import org.openmrs.api.context.Context;
import org.openmrs.module.agentgateway.AgentGatewayConfig;
import org.openmrs.module.agentgateway.AgentGatewayConstants;
import org.openmrs.module.agentgateway.AgentGatewayPrivileges;
import org.openmrs.module.agentgateway.api.AgentGatewayService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Relays one dictation from the clinician's browser to the Clinical Dictation Service, and the
 * transcript back.
 * <p>
 * Same shape and same reasoning as {@link ChatRelayController}: the browser never talks to Server 2
 * (ADR-12). If it did, the channel secret would have to be reachable from page JavaScript, which
 * means it would not be a secret. So the browser posts same-origin to OpenMRS inside its existing
 * authenticated session, and this controller - which already holds the secret - is the only thing
 * that speaks to the dictation service.
 * <p>
 * What comes back is <em>text for the clinician's compose box</em>, nothing more. It is not sent, it
 * is not interpreted, and it reaches no OpenMRS API. The clinician reads it, edits it if they want,
 * and presses send - at which point it becomes an ordinary chat turn subject to every gate that
 * already exists, including the confirmation gate for any write.
 * <p>
 * This does not use {@link org.openmrs.module.agentgateway.http.HttpJsonClient} because that sends a
 * {@code String} body. A dictation is raw 16 kHz mono PCM: encoding it as text would inflate it by a
 * third and mean decoding it again on the other side, for no benefit.
 */
@Controller
public class TranscribeRelayController {

	private static final Logger log = LoggerFactory.getLogger(TranscribeRelayController.class);

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@RequestMapping(value = "/module/agentgateway/transcribe.form", method = RequestMethod.POST)
	@ResponseBody
	public Map<String, Object> transcribe(HttpServletRequest request,
			@RequestParam(value = "lang", required = false) String lang) {

		AgentGatewayPrivileges.requireVoiceUse();

		Map<String, Object> result = new LinkedHashMap<String, Object>();

		if (!AgentGatewayConfig.isDictationConfigured()) {
			// Also false when the dictation secret matches the chat's - see isDictationConfigured.
			log.error("agentgateway: dictation is not configured; refusing to relay");
			result.put("success", false);
			result.put("reason", "not_configured");
			return result;
		}

		byte[] audio;
		try {
			audio = readBounded(request.getInputStream(), AgentGatewayConstants.MAX_DICTATION_BYTES);
		}
		catch (IllegalStateException tooBig) {
			// Refused here rather than streamed on: an oversized body must not be buffered inside
			// Tomcat and must not occupy a GPU slot on Server 2.
			result.put("success", false);
			result.put("reason", "too_long");
			return result;
		}
		catch (IOException e) {
			log.warn("agentgateway: could not read the dictation body", e);
			result.put("success", false);
			result.put("reason", "unreadable");
			return result;
		}

		if (audio.length == 0) {
			result.put("success", true);
			result.put("text", "");
			return result;
		}

		String token;
		try {
			token = Context.getService(AgentGatewayService.class).mintDictationTokenForCurrentUser();
		}
		catch (Exception e) {
			log.error("agentgateway: could not mint a dictation token", e);
			result.put("success", false);
			result.put("reason", "identity");
			return result;
		}

		try {
			// The language is browser-supplied, so it is checked against a shape rather than
			// trimmed and concatenated. Anything else is dropped and the service's own default
			// applies. Unvalidated text going into a URL is how a query parameter becomes a
			// second query parameter, or a line in somebody's access log that is not a URL.
			String url = AgentGatewayConfig.getSttServiceUrl() + "/v1/transcribe"
					+ (isLanguageTag(lang) ? "?lang=" + lang.trim() : "");

			HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
			try {
				connection.setRequestMethod("POST");
				connection.setConnectTimeout(AgentGatewayConfig.getSttTimeoutMillis());
				connection.setReadTimeout(AgentGatewayConfig.getSttTimeoutMillis());
				connection.setInstanceFollowRedirects(false);
				connection.setRequestProperty("Accept", "application/json");
				connection.setRequestProperty("Content-Type", "application/octet-stream");
				connection.setRequestProperty(AgentGatewayConstants.HEADER_STT_CHANNEL_SECRET,
						AgentGatewayConfig.getSttChannelSecret());
				connection.setRequestProperty(AgentGatewayConstants.HEADER_AGENT_TOKEN, token);
				connection.setFixedLengthStreamingMode(audio.length);
				connection.setDoOutput(true);

				OutputStream out = connection.getOutputStream();
				try {
					out.write(audio);
				}
				finally {
					out.close();
				}

				int status = connection.getResponseCode();
				InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
				String body = readFully(stream);

				if (status != 200) {
					log.warn("agentgateway: the dictation service answered HTTP {}", status);
					result.put("success", false);
					result.put("reason", status == 429 ? "busy" : "unavailable");
					return result;
				}

				JsonNode parsed = MAPPER.readTree(body);
				JsonNode text = parsed.get("text");
				result.put("success", true);
				result.put("text", text == null || text.isNull() ? "" : text.asText());
				// "silence" and "too_short" come back as an empty transcript with a reason. The
				// browser uses it to say nothing at all rather than flashing an error - the
				// clinician pressed the button and said nothing, which is not a failure.
				JsonNode reason = parsed.get("reason");
				if (reason != null && !reason.isNull()) {
					result.put("reason", reason.asText());
				}
				return result;
			}
			finally {
				connection.disconnect();
			}
		}
		catch (IOException e) {
			// Graceful degradation, exactly as for the chat: dictation being down must never look
			// like OpenMRS being down. The composer stays a normal text box.
			log.warn("agentgateway: the dictation service could not be reached", e);
			result.put("success", false);
			result.put("reason", "unavailable");
			return result;
		}
		catch (Exception e) {
			log.error("agentgateway: unexpected failure relaying a dictation", e);
			result.put("success", false);
			result.put("reason", "error");
			return result;
		}
	}

	/**
	 * Whether this looks like a language tag ({@code fr}, {@code en}, {@code ar-DZ}) and nothing
	 * else. Deliberately a whitelist: the set of valid values is tiny and known, so anything
	 * outside it is a mistake or an attempt, and both deserve the same answer.
	 */
	private static boolean isLanguageTag(String value) {
		return value != null && value.trim().matches("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})?");
	}

	/**
	 * Reads at most {@code limit} bytes, and throws rather than truncating past it.
	 * <p>
	 * Truncating would send a clipped utterance on to be transcribed, producing a half sentence the
	 * clinician has to notice is half. Refusing says what happened.
	 */
	private static byte[] readBounded(InputStream stream, int limit) throws IOException {
		if (stream == null) {
			return new byte[0];
		}
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		byte[] chunk = new byte[8192];
		int read;
		while ((read = stream.read(chunk)) != -1) {
			if (buffer.size() + read > limit) {
				throw new IllegalStateException("dictation exceeds " + limit + " bytes");
			}
			buffer.write(chunk, 0, read);
		}
		return buffer.toByteArray();
	}

	private static String readFully(InputStream stream) throws IOException {
		if (stream == null) {
			return "";
		}
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		byte[] chunk = new byte[4096];
		int read;
		while ((read = stream.read(chunk)) != -1) {
			buffer.write(chunk, 0, read);
		}
		return buffer.toString("UTF-8");
	}
}
