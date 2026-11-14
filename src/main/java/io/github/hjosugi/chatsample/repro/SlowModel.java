package io.github.hjosugi.chatsample.repro;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Stands in for the streaming APIs of Anthropic, OpenAI and Gemini with one long reply, a
 * word at a time, and tells whether the client closed the stream before its end. The path
 * of the request tells which of the three the client speaks. A client that stops reading
 * but keeps the connection open is not noticed: the words still fit the socket's buffers.
 * Only a closed connection ends the reply.
 */
public final class SlowModel implements AutoCloseable {

	private static final Log logger = LogFactory.getLog(SlowModel.class);

	private final HttpServer server;

	private final int words;

	private final Duration pause;

	private volatile CompletableFuture<Integer> closedByClient = new CompletableFuture<>();

	private volatile CompletableFuture<Void> streamedToTheEnd = new CompletableFuture<>();

	/**
	 * @param port the port, or 0 for any free one
	 * @param words how many words each reply has
	 * @param pause the time between two words
	 */
	public SlowModel(int port, int words, Duration pause) throws IOException {
		this.words = words;
		this.pause = pause;
		this.server = HttpServer.create(new InetSocketAddress("localhost", port), 0);
		this.server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
		this.server.createContext("/", this::reply);
		this.server.start();
	}

	public String url() {
		return "http://localhost:" + this.server.getAddress().getPort();
	}

	/**
	 * Completes, with the number of words sent, when a client closes a stream before its
	 * end.
	 */
	public CompletableFuture<Integer> closedByClient() {
		return this.closedByClient;
	}

	/**
	 * Completes when a stream was written to its end.
	 */
	public CompletableFuture<Void> streamedToTheEnd() {
		return this.streamedToTheEnd;
	}

	@Override
	public void close() {
		this.server.stop(0);
	}

	private void reply(HttpExchange exchange) throws IOException {
		exchange.getRequestBody().readAllBytes();
		this.closedByClient = new CompletableFuture<>();
		this.streamedToTheEnd = new CompletableFuture<>();
		String path = exchange.getRequestURI().getPath();
		Dialect dialect = path.contains("streamGenerateContent") ? Dialect.GEMINI
				: path.contains("chat/completions") ? Dialect.OPENAI : Dialect.ANTHROPIC;
		logger.info("Streaming a reply of %d words, one every %d ms, as %s".formatted(this.words,
				this.pause.toMillis(), dialect));
		exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
		exchange.sendResponseHeaders(200, 0);
		int sent = 0;
		try (OutputStream out = exchange.getResponseBody()) {
			dialect.start(out);
			for (; sent < this.words; sent++) {
				Thread.sleep(this.pause);
				dialect.word(out, "word" + (sent + 1) + " ", sent == this.words - 1);
			}
			dialect.end(out, this.words);
			logger.info("Streamed all %d words: the client kept the connection open to the end".formatted(this.words));
			this.streamedToTheEnd.complete(null);
		}
		catch (IOException ex) {
			logger.info("The client closed the connection after %d of %d words".formatted(sent, this.words));
			this.closedByClient.complete(sent);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	private static void write(OutputStream out, String event, String data) throws IOException {
		String frame = ((event != null) ? "event: " + event + "\n" : "") + "data: " + data + "\n\n";
		out.write(frame.getBytes(StandardCharsets.UTF_8));
		out.flush();
	}

	/**
	 * The server-sent events of one of the three APIs.
	 */
	private enum Dialect {

		ANTHROPIC {

			@Override
			void start(OutputStream out) throws IOException {
				write(out, "message_start", "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\","
						+ "\"role\":\"assistant\",\"content\":[],\"model\":\"fake\",\"stop_reason\":null,"
						+ "\"stop_sequence\":null,\"usage\":{\"input_tokens\":25,\"output_tokens\":1}}}");
				write(out, "content_block_start",
						"{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}");
			}

			@Override
			void word(OutputStream out, String word, boolean last) throws IOException {
				write(out, "content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,"
						+ "\"delta\":{\"type\":\"text_delta\",\"text\":\"" + word + "\"}}");
			}

			@Override
			void end(OutputStream out, int words) throws IOException {
				write(out, "content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}");
				write(out, "message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\","
						+ "\"stop_sequence\":null},\"usage\":{\"output_tokens\":" + words + "}}");
				write(out, "message_stop", "{\"type\":\"message_stop\"}");
			}

		},

		OPENAI {

			@Override
			void word(OutputStream out, String word, boolean last) throws IOException {
				write(out, null, chunk("{\"role\":\"assistant\",\"content\":\"" + word + "\"}", "null"));
			}

			@Override
			void end(OutputStream out, int words) throws IOException {
				write(out, null, chunk("{}", "\"stop\""));
				write(out, null, "[DONE]");
			}

			private String chunk(String delta, String finishReason) {
				return "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":1700000000,"
						+ "\"model\":\"fake\",\"choices\":[{\"index\":0,\"delta\":" + delta + ",\"finish_reason\":"
						+ finishReason + "}]}";
			}

		},

		GEMINI {

			@Override
			void word(OutputStream out, String word, boolean last) throws IOException {
				write(out, null, "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"" + word + "\"}],"
						+ "\"role\":\"model\"}," + (last ? "\"finishReason\":\"STOP\"," : "") + "\"index\":0}],"
						+ "\"usageMetadata\":{\"promptTokenCount\":25,\"candidatesTokenCount\":1,"
						+ "\"totalTokenCount\":26},\"modelVersion\":\"fake\"}");
			}

		};

		void start(OutputStream out) throws IOException {
		}

		abstract void word(OutputStream out, String word, boolean last) throws IOException;

		void end(OutputStream out, int words) throws IOException {
		}

	}

}
