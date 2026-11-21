package io.github.hjosugi.chatsample.repro;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stands in for the streaming APIs of Anthropic, OpenAI and Gemini with one long reply, a
 * word at a time, and tells whether the client closed the stream before its end. The path
 * of the request tells which of the three the client speaks. A client that stops reading
 * but keeps the connection open is not noticed: the words still fit the socket's buffers.
 * Only a closed connection ends the reply.
 * <p>
 * As the stand-in of the coding agent it answers by what the user wrote, as {@link AgentScript}
 * says, and embeds texts for the guidelines. Only a reply to a question that asks for a
 * long reply is slow then.
 */
public final class SlowModel implements AutoCloseable {

	private static final Log logger = LogFactory.getLog(SlowModel.class);

	private static final JsonMapper JSON = JsonMapper.shared();

	/**
	 * The pause between two words of an answer that is not the long reply.
	 */
	private static final Duration ANSWER_PAUSE = Duration.ofMillis(40);

	private final HttpServer server;

	private final int words;

	private final Duration pause;

	private final boolean agent;

	private final AtomicInteger calls = new AtomicInteger();

	private volatile CompletableFuture<Integer> closedByClient = new CompletableFuture<>();

	private volatile CompletableFuture<Void> streamedToTheEnd = new CompletableFuture<>();

	/**
	 * @param port the port, or 0 for any free one
	 * @param words how many words each reply has
	 * @param pause the time between two words
	 */
	public SlowModel(int port, int words, Duration pause) throws IOException {
		this(port, words, pause, false);
	}

	/**
	 * @param agent whether it answers as the stand-in of the coding agent does, and not with the
	 * long reply to anything
	 */
	public SlowModel(int port, int words, Duration pause, boolean agent) throws IOException {
		this.words = words;
		this.pause = pause;
		this.agent = agent;
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
		byte[] request = exchange.getRequestBody().readAllBytes();
		String path = exchange.getRequestURI().getPath();
		if (this.agent && path.contains("embeddings")) {
			byte[] embeddings = BagOfWords.openAiResponse(JSON.readTree(request)).getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, embeddings.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(embeddings);
			}
			return;
		}
		this.closedByClient = new CompletableFuture<>();
		this.streamedToTheEnd = new CompletableFuture<>();
		Dialect dialect = path.contains("streamGenerateContent") ? Dialect.GEMINI
				: path.contains("chat/completions") ? Dialect.OPENAI : Dialect.ANTHROPIC;
		AgentScript.Plan plan = this.agent ? AgentScript.plan(dialect, JSON.readTree(request)) : AgentScript.Plan.LONG;
		List<String> pieces = (plan.text() != null) ? pieces(plan.text()) : null;
		int count = (pieces != null) ? pieces.size() : this.words;
		Duration pause = (pieces != null) ? ANSWER_PAUSE : this.pause;
		logger.info((plan.tool() != null) ? "Asking for the tool %s, as %s".formatted(plan.tool(), dialect)
				: "Streaming a reply of %d words, one every %d ms, as %s".formatted(count, pause.toMillis(), dialect));
		exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
		exchange.sendResponseHeaders(200, 0);
		int sent = 0;
		try (OutputStream out = exchange.getResponseBody()) {
			if (plan.tool() != null) {
				dialect.toolCall(out, this.calls.incrementAndGet(), plan.tool(), plan.arguments());
				sent = 1;
			}
			else {
				dialect.start(out);
				for (; sent < count; sent++) {
					Thread.sleep(pause);
					dialect.word(out, (pieces != null) ? pieces.get(sent) : "word" + (sent + 1) + " ", sent == count - 1);
				}
				dialect.end(out, count);
			}
			logger.info("Streamed all %d words: the client kept the connection open to the end".formatted(count));
			this.streamedToTheEnd.complete(null);
		}
		catch (IOException ex) {
			logger.info("The client closed the connection after %d of %d words".formatted(sent, count));
			this.closedByClient.complete(sent);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	private static List<String> pieces(String text) {
		return Arrays.stream(text.split("(?<= )")).toList();
	}

	private static void write(OutputStream out, String event, String data) throws IOException {
		String frame = ((event != null) ? "event: " + event + "\n" : "") + "data: " + data + "\n\n";
		out.write(frame.getBytes(StandardCharsets.UTF_8));
		out.flush();
	}

	/**
	 * The server-sent events of one of the three APIs.
	 */
	enum Dialect {

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
						+ "\"delta\":{\"type\":\"text_delta\",\"text\":" + quote(word) + "}}");
			}

			@Override
			void end(OutputStream out, int words) throws IOException {
				write(out, "content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}");
				write(out, "message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\","
						+ "\"stop_sequence\":null},\"usage\":{\"output_tokens\":" + words + "}}");
				write(out, "message_stop", "{\"type\":\"message_stop\"}");
			}

			@Override
			void toolCall(OutputStream out, int number, String name, String arguments) throws IOException {
				write(out, "message_start", "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\","
						+ "\"role\":\"assistant\",\"content\":[],\"model\":\"fake\",\"stop_reason\":null,"
						+ "\"stop_sequence\":null,\"usage\":{\"input_tokens\":25,\"output_tokens\":1}}}");
				write(out, "content_block_start", "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":"
						+ "{\"type\":\"tool_use\",\"id\":\"toolu_" + number + "\",\"name\":" + quote(name)
						+ ",\"input\":{}}}");
				write(out, "content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,"
						+ "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":" + quote(arguments) + "}}");
				write(out, "content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}");
				write(out, "message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\","
						+ "\"stop_sequence\":null},\"usage\":{\"output_tokens\":15}}");
				write(out, "message_stop", "{\"type\":\"message_stop\"}");
			}

		},

		OPENAI {

			@Override
			void word(OutputStream out, String word, boolean last) throws IOException {
				write(out, null, chunk("{\"role\":\"assistant\",\"content\":" + quote(word) + "}", "null"));
			}

			@Override
			void end(OutputStream out, int words) throws IOException {
				write(out, null, chunk("{}", "\"stop\""));
				write(out, null, "[DONE]");
			}

			@Override
			void toolCall(OutputStream out, int number, String name, String arguments) throws IOException {
				write(out, null, chunk("{\"role\":\"assistant\",\"tool_calls\":[{\"index\":0,\"id\":\"call_" + number
						+ "\",\"type\":\"function\",\"function\":{\"name\":" + quote(name) + ",\"arguments\":"
						+ quote(arguments) + "}}]}", "null"));
				write(out, null, chunk("{}", "\"tool_calls\""));
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
				write(out, null, "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":" + quote(word) + "}],"
						+ "\"role\":\"model\"}," + (last ? "\"finishReason\":\"STOP\"," : "") + "\"index\":0}],"
						+ "\"usageMetadata\":{\"promptTokenCount\":25,\"candidatesTokenCount\":1,"
						+ "\"totalTokenCount\":26},\"modelVersion\":\"fake\"}");
			}

			@Override
			void toolCall(OutputStream out, int number, String name, String arguments) throws IOException {
				write(out, null, "{\"candidates\":[{\"content\":{\"parts\":[{\"functionCall\":{\"id\":\"call_" + number
						+ "\",\"name\":" + quote(name) + ",\"args\":" + arguments + "}}],\"role\":\"model\"},"
						+ "\"finishReason\":\"STOP\",\"index\":0}],\"usageMetadata\":{\"promptTokenCount\":25,"
						+ "\"candidatesTokenCount\":1,\"totalTokenCount\":26},\"modelVersion\":\"fake\"}");
			}

		};

		void start(OutputStream out) throws IOException {
		}

		abstract void word(OutputStream out, String word, boolean last) throws IOException;

		void end(OutputStream out, int words) throws IOException {
		}

		/**
		 * A reply that asks for a tool, whole: its events from start to end.
		 */
		abstract void toolCall(OutputStream out, int number, String name, String arguments) throws IOException;

		private static String quote(String text) {
			return JSON.writeValueAsString(text);
		}

	}

}
