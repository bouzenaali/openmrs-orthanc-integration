package org.openmrs.module.agentgateway.http;

import org.apache.commons.lang.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.Map;

/**
 * A deliberately small JSON-over-HTTP client, built on {@code HttpURLConnection}.
 * <p>
 * The module needs exactly three outbound calls - relay a chat turn to the agent service, read a
 * resource's current state back before it is overwritten, and issue a reversing call during
 * rollback - all of them one-shot request/response with a JSON body. Pulling in an HTTP client
 * library for that would add a dependency tree to a module whose whole selling point is that it
 * is boring and cannot destabilise the platform it runs inside.
 */
public final class HttpJsonClient {

	private static final Charset UTF_8 = Charset.forName("UTF-8");

	private HttpJsonClient() {
	}

	public static final class Response {

		private final int status;

		private final String body;

		Response(int status, String body) {
			this.status = status;
			this.body = body;
		}

		public int getStatus() {
			return status;
		}

		public String getBody() {
			return body;
		}

		public boolean isSuccessful() {
			return status >= 200 && status < 300;
		}
	}

	/**
	 * @param body request body, or null for methods that carry none
	 * @throws IOException on any transport-level failure. Callers turn this into the
	 *             "network/timeout" reason CA8 requires - it never reaches the chat verbatim.
	 */
	public static Response request(String method, String url, Map<String, String> headers, String body, int timeoutMs)
			throws IOException {
		HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
		try {
			connection.setRequestMethod(method);
			connection.setConnectTimeout(timeoutMs);
			connection.setReadTimeout(timeoutMs);
			connection.setInstanceFollowRedirects(false);
			connection.setRequestProperty("Accept", "application/json");
			if (headers != null) {
				for (Map.Entry<String, String> header : headers.entrySet()) {
					if (header.getValue() != null) {
						connection.setRequestProperty(header.getKey(), header.getValue());
					}
				}
			}

			if (body != null) {
				connection.setDoOutput(true);
				if (StringUtils.isBlank(connection.getRequestProperty("Content-Type"))) {
					connection.setRequestProperty("Content-Type", "application/json");
				}
				OutputStream out = connection.getOutputStream();
				try {
					out.write(body.getBytes(UTF_8));
				}
				finally {
					out.close();
				}
			}

			int status = connection.getResponseCode();
			InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
			return new Response(status, readFully(stream));
		}
		finally {
			connection.disconnect();
		}
	}

	private static String readFully(InputStream stream) throws IOException {
		if (stream == null) {
			return "";
		}
		try {
			ByteArrayOutputStream buffer = new ByteArrayOutputStream();
			byte[] chunk = new byte[4096];
			int read;
			while ((read = stream.read(chunk)) != -1) {
				buffer.write(chunk, 0, read);
			}
			return new String(buffer.toByteArray(), UTF_8);
		}
		finally {
			stream.close();
		}
	}
}
