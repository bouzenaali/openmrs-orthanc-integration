package org.openmrs.module.agentgateway.web.filter;

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;

/**
 * Buffers a request body so the audit filter can record what was sent and still hand the
 * untouched bytes to OpenMRS - a servlet request body can only be read once.
 * <p>
 * Only used when the filter has already decided the body is small enough to be worth holding in
 * memory; anything larger is passed straight through unbuffered and logged as omitted.
 */
class BufferedRequestWrapper extends HttpServletRequestWrapper {

	private static final Charset UTF_8 = Charset.forName("UTF-8");

	private final byte[] body;

	BufferedRequestWrapper(HttpServletRequest request) throws IOException {
		super(request);
		this.body = readFully(request.getInputStream());
	}

	String getBodyAsString() {
		return new String(body, UTF_8);
	}

	@Override
	public ServletInputStream getInputStream() {
		final ByteArrayInputStream source = new ByteArrayInputStream(body);
		return new ServletInputStream() {

			@Override
			public int read() {
				return source.read();
			}

			@Override
			public boolean isFinished() {
				return source.available() == 0;
			}

			@Override
			public boolean isReady() {
				return true;
			}

			@Override
			public void setReadListener(ReadListener readListener) {
				throw new UnsupportedOperationException("Asynchronous reads are not supported here");
			}
		};
	}

	@Override
	public BufferedReader getReader() {
		return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), UTF_8));
	}

	private static byte[] readFully(InputStream stream) throws IOException {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		byte[] chunk = new byte[4096];
		int read;
		while ((read = stream.read(chunk)) != -1) {
			buffer.write(chunk, 0, read);
		}
		return buffer.toByteArray();
	}
}
