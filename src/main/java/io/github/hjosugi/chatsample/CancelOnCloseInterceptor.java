package io.github.hjosugi.chatsample;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;
import org.jspecify.annotations.Nullable;

/**
 * Closes the connection of a stream that is closed before its end, whichever thread
 * closes it.
 * <p>
 * Spring AI 2.0.2 does not manage it when a reply is stopped. It closes the response on
 * the thread that cancels the stream, while another thread is inside a read of it. To
 * close a body before its end, OkHttp reads the rest of it, enters the read timeout that
 * the reading thread is already in, and okio throws {@code Unbalanced enter/exit}. The
 * close is abandoned, the connection stays open, and the model writes, and bills, the
 * whole reply. The branch {@code repro/anthropic-stop} reproduces it.
 * <p>
 * Here a close before the end first cancels the call, which OkHttp lets any thread do:
 * the connection closes, and the read in the other thread ends.
 */
final class CancelOnCloseInterceptor implements Interceptor {

	@Override
	public Response intercept(Chain chain) throws IOException {
		Response response = chain.proceed(chain.request());
		ResponseBody body = response.body();
		if (body == null || !isEventStream(body.contentType())) {
			return response;
		}
		return response.newBuilder().body(new CancelOnCloseBody(body, chain.call())).build();
	}

	private static boolean isEventStream(@Nullable MediaType type) {
		return type != null && type.type().equals("text") && type.subtype().equals("event-stream");
	}

	private static final class CancelOnCloseBody extends ResponseBody {

		private final ResponseBody body;

		private final Call call;

		private final BufferedSource source;

		private volatile boolean closed;

		private volatile boolean exhausted;

		CancelOnCloseBody(ResponseBody body, Call call) {
			this.body = body;
			this.call = call;
			this.source = Okio.buffer(new ForwardingSource(body.source()) {

				@Override
				public long read(Buffer sink, long byteCount) throws IOException {
					try {
						long read = super.read(sink, byteCount);
						if (read == -1) {
							CancelOnCloseBody.this.exhausted = true;
						}
						return read;
					}
					catch (IOException ex) {
						if (!CancelOnCloseBody.this.closed) {
							throw ex;
						}
						// The close cancelled the call: to the reader, the body ends here.
						CancelOnCloseBody.this.exhausted = true;
						return -1;
					}
				}

				@Override
				public void close() {
					CancelOnCloseBody.this.close();
				}

			});
		}

		@Override
		public @Nullable MediaType contentType() {
			return this.body.contentType();
		}

		@Override
		public long contentLength() {
			return this.body.contentLength();
		}

		@Override
		public BufferedSource source() {
			return this.source;
		}

		@Override
		public void close() {
			this.closed = true;
			if (!this.exhausted) {
				this.call.cancel();
			}
			try {
				this.body.close();
			}
			catch (IllegalStateException ex) {
				// Unbalanced enter/exit: another thread is inside a read. The cancel ends
				// that read, and the reader closes the body again when it sees the end.
			}
		}

	}

}
