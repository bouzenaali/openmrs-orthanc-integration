package org.openmrs.module.agentgateway.web.filter;

import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;

/**
 * Copies the response body aside as it is written, so the audit trail can record what OpenMRS
 * actually answered without withholding a single byte from the caller. The copy is capped: past
 * the limit the response still streams normally and only the recorded copy stops growing.
 */
class CapturingResponseWrapper extends HttpServletResponseWrapper {

	private static final Charset UTF_8 = Charset.forName("UTF-8");

	private final ByteArrayOutputStream captured = new ByteArrayOutputStream();

	private final int captureLimitBytes;

	private ServletOutputStream outputStream;

	private PrintWriter writer;

	CapturingResponseWrapper(HttpServletResponse response, int captureLimitBytes) {
		super(response);
		this.captureLimitBytes = captureLimitBytes;
	}

	String getCapturedBody() {
		return new String(captured.toByteArray(), UTF_8);
	}

	@Override
	public ServletOutputStream getOutputStream() throws IOException {
		if (writer != null) {
			throw new IllegalStateException("getWriter() has already been called on this response");
		}
		if (outputStream == null) {
			final ServletOutputStream delegate = super.getOutputStream();
			outputStream = new ServletOutputStream() {

				@Override
				public void write(int b) throws IOException {
					delegate.write(b);
					capture(b);
				}

				@Override
				public void write(byte[] bytes, int offset, int length) throws IOException {
					delegate.write(bytes, offset, length);
					capture(bytes, offset, length);
				}

				@Override
				public void flush() throws IOException {
					delegate.flush();
				}

				@Override
				public void close() throws IOException {
					delegate.close();
				}

				@Override
				public boolean isReady() {
					return delegate.isReady();
				}

				@Override
				public void setWriteListener(WriteListener writeListener) {
					delegate.setWriteListener(writeListener);
				}
			};
		}
		return outputStream;
	}

	@Override
	public PrintWriter getWriter() throws IOException {
		if (outputStream != null) {
			throw new IllegalStateException("getOutputStream() has already been called on this response");
		}
		if (writer == null) {
			writer = new PrintWriter(new OutputStreamWriter(getOutputStream(), UTF_8), true);
		}
		return writer;
	}

	@Override
	public void flushBuffer() throws IOException {
		if (writer != null) {
			writer.flush();
		}
		super.flushBuffer();
	}

	private void capture(int b) {
		if (captured.size() < captureLimitBytes) {
			captured.write(b);
		}
	}

	private void capture(byte[] bytes, int offset, int length) {
		int room = captureLimitBytes - captured.size();
		if (room > 0) {
			captured.write(bytes, offset, Math.min(length, room));
		}
	}
}
