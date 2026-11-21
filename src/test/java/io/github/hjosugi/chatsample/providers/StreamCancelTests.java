package io.github.hjosugi.chatsample.providers;

import java.time.Duration;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.IntFunction;

import io.github.hjosugi.chatsample.support.StandIn;
import io.github.hjosugi.chatsample.support.StandIn.Reply;
import io.github.hjosugi.chatsample.support.StandIn.Request;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.mistralai.MistralAiChatModel;
import org.springframework.ai.mistralai.MistralAiChatOptions;
import org.springframework.ai.mistralai.api.MistralAiApi;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.responses.OpenAiResponsesChatModel;
import org.springframework.ai.openai.responses.OpenAiResponsesChatOptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Whether cancelling a stream of Spring AI closes its connection to the provider, for
 * each chat model, over HTTP/1.1 as the stand-in speaks it. A connection that stays open
 * is streamed to its end, and billed. The stand-in streams 30 words, 50 ms apart, and
 * tells which comes first: it sees the connection closed, or it reaches the last word.
 * <p>
 * The SDKs of Anthropic, OpenAI and Google read the stream with a blocking read on a
 * thread of their own. What a cancel does depends on where that thread is, so the tests
 * pin it down: they cancel either while the reading thread waits in a socket read for
 * the next event, which is where it is nearly all the time, or from the reading thread
 * itself, inside the first chunk. The models on {@code WebClient} (Mistral, Ollama,
 * DeepSeek) read without blocking a thread, and are cancelled after the first chunk.
 */
class StreamCancelTests {

	private static final int WORDS = 30;

	/**
	 * The bug the sample reports upstream, as {@code AnthropicStopReproTests} shows it:
	 * the cancel closes the response on the cancelling thread while the reading thread is
	 * inside a read, the close fails, and the connection stays open.
	 */
	@Test
	void cancellingAnAnthropicStreamWhileItWaitsForTheNextEventLeavesItsConnectionOpen() throws Exception {
		assertThat(cancel(anthropic(), StreamCancelTests::anthropicStream, Cancel.WHILE_READING))
			.isEqualTo(Outcome.STAYED_OPEN);
	}

	@Test
	void cancellingAnAnthropicStreamFromItsReadingThreadClosesItsConnection() throws Exception {
		assertThat(cancel(anthropic(), StreamCancelTests::anthropicStream, Cancel.FROM_THE_READING_THREAD))
			.isEqualTo(Outcome.CLOSED);
	}

	@Test
	void cancellingAnOpenAiChatCompletionsStreamWhileItWaitsForTheNextEventLeavesItsConnectionOpen() throws Exception {
		assertThat(cancel(openAiChunks(), StreamCancelTests::openAiStream, Cancel.WHILE_READING))
			.isEqualTo(Outcome.STAYED_OPEN);
	}

	@Test
	void cancellingAnOpenAiChatCompletionsStreamFromItsReadingThreadClosesItsConnection() throws Exception {
		assertThat(cancel(openAiChunks(), StreamCancelTests::openAiStream, Cancel.FROM_THE_READING_THREAD))
			.isEqualTo(Outcome.CLOSED);
	}

	@Test
	void cancellingAnOpenAiResponsesStreamWhileItWaitsForTheNextEventLeavesItsConnectionOpen() throws Exception {
		assertThat(cancel(openAiResponseEvents(), StreamCancelTests::responsesStream, Cancel.WHILE_READING))
			.isEqualTo(Outcome.STAYED_OPEN);
	}

	/**
	 * The stream of {@code GoogleGenAiChatModel} iterates the SDK's blocking
	 * {@code ResponseStream} on the thread that subscribes, so a subscriber that does
	 * not move it to another thread is held until the reply ends.
	 */
	@Test
	void aGeminiStreamIsReadOnTheThreadThatSubscribes() {
		SlowReply reply = gemini();
		try (StandIn standIn = new StandIn(reply::to)) {
			geminiStream(standIn).subscribe((chunk) -> {
			});

			assertThat(reply.lastWord).as("the whole reply was read before subscribe() returned").isDone();
		}
	}

	/**
	 * A cancel ends the iteration but never closes the SDK's {@code ResponseStream}.
	 */
	@Test
	void cancellingAGeminiStreamWhileItWaitsForTheNextEventLeavesItsConnectionOpen() throws Exception {
		assertThat(cancel(gemini(), (standIn) -> geminiStream(standIn).subscribeOn(Schedulers.boundedElastic()),
				Cancel.WHILE_READING))
			.isEqualTo(Outcome.STAYED_OPEN);
	}

	@Test
	void cancellingAMistralStreamClosesItsConnection() throws Exception {
		assertThat(cancel(openAiChunks(), (standIn) -> MistralAiChatModel.builder()
			.mistralAiApi(MistralAiApi.builder().baseUrl(standIn.url()).apiKey("test-key").build())
			.options(MistralAiChatOptions.builder().model("mistral-small-2603").build())
			.build()
			.stream(new Prompt("Tell me a long story")), Cancel.AFTER_THE_FIRST_CHUNK)).isEqualTo(Outcome.CLOSED);
	}

	@Test
	void cancellingAnOllamaStreamClosesItsConnection() throws Exception {
		assertThat(cancel(ollamaLines(), (standIn) -> OllamaChatModel.builder()
			.ollamaApi(OllamaApi.builder().baseUrl(standIn.url()).build())
			.options(OllamaChatOptions.builder().model("qwen3").build())
			.build()
			.stream(new Prompt("Tell me a long story")), Cancel.AFTER_THE_FIRST_CHUNK)).isEqualTo(Outcome.CLOSED);
	}

	@Test
	void cancellingADeepSeekStreamClosesItsConnection() throws Exception {
		assertThat(cancel(openAiChunks(), (standIn) -> DeepSeekChatModel.builder()
			.deepSeekApi(DeepSeekApi.builder().baseUrl(standIn.url()).apiKey("test-key").build())
			.options(DeepSeekChatOptions.builder().model("deepseek-v4-flash").build())
			.build()
			.stream(new Prompt("Tell me a long story")), Cancel.AFTER_THE_FIRST_CHUNK)).isEqualTo(Outcome.CLOSED);
	}

	private static Flux<ChatResponse> anthropicStream(StandIn standIn) {
		return AnthropicChatModel.builder()
			.options(AnthropicChatOptions.builder().baseUrl(standIn.url()).apiKey("test-key").maxRetries(0).build())
			.build()
			.stream(new Prompt("Tell me a long story"));
	}

	private static Flux<ChatResponse> openAiStream(StandIn standIn) {
		return OpenAiChatModel.builder()
			.options(OpenAiChatOptions.builder().baseUrl(standIn.url() + "/v1").apiKey("test-key").maxRetries(0).build())
			.build()
			.stream(new Prompt("Tell me a long story"));
	}

	private static Flux<ChatResponse> responsesStream(StandIn standIn) {
		return OpenAiResponsesChatModel.builder()
			.options(OpenAiResponsesChatOptions.builder()
				.baseUrl(standIn.url() + "/v1")
				.apiKey("test-key")
				.maxRetries(0)
				.build())
			.build()
			.stream(new Prompt("Tell me a long story"));
	}

	private static Flux<ChatResponse> geminiStream(StandIn standIn) {
		return GoogleGenAiChatModel.builder()
			.genAiClient(com.google.genai.Client.builder()
				.apiKey("test-key")
				.httpOptions(com.google.genai.types.HttpOptions.builder().baseUrl(standIn.url()).build())
				.build())
			.options(GoogleGenAiChatOptions.builder().model("gemini-3.5-flash-lite").build())
			.build()
			.stream(new Prompt("Tell me a long story"));
	}

	enum Cancel {

		/** From the test, once the first chunk has arrived. */
		AFTER_THE_FIRST_CHUNK,

		/** From the test, once the thread that delivered the first chunk waits in a socket read. */
		WHILE_READING,

		/** Inside the first chunk, on the thread that read it. */
		FROM_THE_READING_THREAD

	}

	enum Outcome {

		/** The stand-in saw the connection closed before its last word. */
		CLOSED,

		/** The stand-in wrote its last word: the connection stayed open. */
		STAYED_OPEN

	}

	/**
	 * Streams a slow reply to the stream the model opens, cancels as told, and tells what
	 * became of the connection.
	 */
	private static Outcome cancel(SlowReply reply, Function<StandIn, Flux<ChatResponse>> stream, Cancel when)
			throws Exception {
		try (StandIn standIn = new StandIn(reply::to)) {
			CompletableFuture<Thread> reader = new CompletableFuture<>();
			AtomicReference<Disposable> subscription = new AtomicReference<>();
			subscription.set(stream.apply(standIn).subscribe((chunk) -> {
				if (reader.complete(Thread.currentThread()) && when == Cancel.FROM_THE_READING_THREAD) {
					// The subscription may not be set yet if the first chunk comes during subscribe().
					await().atMost(Duration.ofSeconds(5)).until(() -> subscription.get() != null);
					subscription.get().dispose();
				}
			}, (error) -> {
			}));
			Thread readingThread = reader.get(10, TimeUnit.SECONDS);

			if (when == Cancel.WHILE_READING) {
				await().atMost(Duration.ofSeconds(5)).until(() -> inSocketRead(readingThread));
			}
			if (when != Cancel.FROM_THE_READING_THREAD) {
				subscription.get().dispose();
			}

			CompletableFuture.anyOf(standIn.clientGone, reply.lastWord).get(10, TimeUnit.SECONDS);
			return standIn.clientGone.isDone() ? Outcome.CLOSED : Outcome.STAYED_OPEN;
		}
	}

	private static boolean inSocketRead(Thread thread) {
		return Arrays.stream(thread.getStackTrace())
			.anyMatch((frame) -> frame.getClassName().equals("sun.nio.ch.NioSocketImpl") && frame.getMethodName().contains("read"));
	}

	private static SlowReply anthropic() {
		return new SlowReply("text/event-stream", List.of(StandIn.event("message_start",
				"{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
						+ "\"content\":[],\"model\":\"claude-haiku-5-5\",\"stop_reason\":null,\"stop_sequence\":null,"
						+ "\"usage\":{\"input_tokens\":25,\"output_tokens\":1}}}"),
				StandIn.event("content_block_start",
						"{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}")),
				(word) -> StandIn.event("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,"
						+ "\"delta\":{\"type\":\"text_delta\",\"text\":\"word" + word + " \"}}"),
				List.of(StandIn.event("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}"),
						StandIn.event("message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\","
								+ "\"stop_sequence\":null},\"usage\":{\"output_tokens\":30}}"),
						StandIn.event("message_stop", "{\"type\":\"message_stop\"}")));
	}

	/**
	 * Chunks in the shape of Chat Completions, which Mistral and DeepSeek share.
	 */
	private static SlowReply openAiChunks() {
		return new SlowReply("text/event-stream", List.of(),
				(word) -> StandIn.data("{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":1700000000,"
						+ "\"model\":\"m\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"word" + word
						+ " \"},\"finish_reason\":null}]}"),
				List.of(StandIn.data("{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":1700000000,"
						+ "\"model\":\"m\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}"),
						StandIn.data("[DONE]")));
	}

	private static SlowReply openAiResponseEvents() {
		String response = "{\"id\":\"resp_1\",\"object\":\"response\",\"created_at\":1700000000,\"status\":\"in_progress\","
				+ "\"model\":\"gpt-5.4-mini\",\"output\":[],\"parallel_tool_calls\":true,\"tool_choice\":\"auto\",\"tools\":[],"
				+ "\"error\":null,\"incomplete_details\":null,\"instructions\":null,\"metadata\":{}}";
		return new SlowReply("text/event-stream",
				List.of(StandIn.event("response.created",
						"{\"type\":\"response.created\",\"sequence_number\":0,\"response\":" + response + "}")),
				(word) -> StandIn.event("response.output_text.delta", "{\"type\":\"response.output_text.delta\","
						+ "\"sequence_number\":" + (word + 1) + ",\"item_id\":\"msg_1\",\"output_index\":0,\"content_index\":0,"
						+ "\"delta\":\"word" + word + " \",\"logprobs\":[]}"),
				List.of(StandIn.event("response.completed", "{\"type\":\"response.completed\",\"sequence_number\":99,"
						+ "\"response\":" + response.replace("in_progress", "completed") + "}")));
	}

	private static SlowReply gemini() {
		return new SlowReply("text/event-stream", List.of(),
				(word) -> StandIn.data("{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"word" + word
						+ " \"}]},\"index\":0}],\"responseId\":\"resp-1\"}"),
				List.of(StandIn.data("{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\".\"}]},"
						+ "\"finishReason\":\"STOP\",\"index\":0}],\"responseId\":\"resp-1\"}")));
	}

	private static SlowReply ollamaLines() {
		return new SlowReply("application/x-ndjson", List.of(),
				(word) -> "{\"model\":\"qwen3\",\"created_at\":\"2026-11-21T00:00:00Z\",\"message\":{\"role\":\"assistant\","
						+ "\"content\":\"word" + word + " \"},\"done\":false}\n",
				List.of("{\"model\":\"qwen3\",\"created_at\":\"2026-11-21T00:00:00Z\",\"message\":{\"role\":\"assistant\","
						+ "\"content\":\"\"},\"done\":true,\"done_reason\":\"stop\"}\n"));
	}

	/**
	 * A reply of {@link #WORDS} words, one every 50 ms, between a head and a tail, that
	 * tells when the stand-in reaches its last piece.
	 */
	private static final class SlowReply {

		final CompletableFuture<Void> lastWord = new CompletableFuture<>();

		private final String contentType;

		private final List<String> events = new ArrayList<>();

		SlowReply(String contentType, List<String> head, IntFunction<String> word, List<String> tail) {
			this.contentType = contentType;
			this.events.addAll(head);
			for (int i = 0; i < WORDS; i++) {
				this.events.add(word.apply(i));
				this.events.add(StandIn.PAUSE);
			}
			this.events.addAll(tail);
		}

		Reply to(Request request) {
			Reply events = Reply.events(this.events);
			List<byte[]> pieces = events.pieces();
			return new Reply(200, this.contentType, Map.of(), new AbstractList<>() {

				@Override
				public byte[] get(int index) {
					if (index == pieces.size() - 1) {
						SlowReply.this.lastWord.complete(null);
					}
					return pieces.get(index);
				}

				@Override
				public int size() {
					return pieces.size();
				}

			});
		}

	}

}
