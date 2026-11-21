package io.github.hjosugi.chatsample.providers.openai;

import java.net.URI;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.hjosugi.chatsample.support.StandIn;
import io.github.hjosugi.chatsample.support.StandIn.Reply;
import io.github.hjosugi.chatsample.support.StandIn.Request;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import tools.jackson.databind.JsonNode;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.part.MediaPart;
import org.springframework.ai.chat.messages.part.TextPart;
import org.springframework.ai.chat.messages.part.ToolCallPart;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.EmptyRateLimit;
import org.springframework.ai.chat.metadata.FinishReasonCategory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatModel.ResponseFormat;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions.AudioParameters;
import org.springframework.ai.openai.metadata.OpenAiRateLimit;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The OpenAI chat page: what Spring AI's {@link OpenAiChatModel} sends to the Chat
 * Completions API for each option, and what it makes of the reply, called and streamed.
 * The model talks to a {@link StandIn} through the official OpenAI SDK.
 */
@ExtendWith(OutputCaptureExtension.class)
class OpenAiChatTests {

	@Test
	void aCallSendsTheConversationWithTheKeyAsABearerTokenAndReadsTheReply() {
		try (StandIn openai = new StandIn((request) -> Gpt.completion(request, "stop", Gpt.assistant("Five pirates.")))) {
			ChatResponse response = Gpt.chatModel(openai, OpenAiChatOptions.builder().model(Gpt.MODEL))
				.call(new Prompt(List.of(new SystemMessage("Be brief."), new UserMessage("Name five pirates."))));

			Request request = openai.single();
			assertThat(request.path()).isEqualTo("/v1/chat/completions");
			assertThat(request.header("Authorization")).isEqualTo("Bearer test-key");
			JsonNode body = request.body();
			assertThat(body.get("model").asString()).isEqualTo(Gpt.MODEL);
			assertThat(body.get("messages").toString()).isEqualTo("[{\"content\":\"Be brief.\",\"role\":\"system\"},"
					+ "{\"content\":\"Name five pirates.\",\"role\":\"user\"}]");
			AssistantMessage message = response.getResult().getOutput();
			assertThat(message.getText()).isEqualTo("Five pirates.");
			assertThat(message.getParts()).containsExactly(TextPart.of("Five pirates."));
			assertThat(message.getMetadata()).containsEntry("id", "chatcmpl-1")
				.containsEntry("role", "assistant")
				.containsEntry("finishReason", "STOP")
				.containsEntry("refusal", "");
			assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("STOP");
			assertThat(response.getMetadata().getId()).isEqualTo("chatcmpl-1");
			assertThat(response.getMetadata().getModel()).isEqualTo(Gpt.MODEL);
			assertThat(response.getMetadata().getUsage().getPromptTokens()).isEqualTo(25);
			assertThat(response.getMetadata().getUsage().getTotalTokens()).isEqualTo(40);
		}
	}

	/**
	 * The page says Spring AI sends {@code User-Agent: spring-ai}; 2.1.0 sends
	 * {@code spring-ai-openai} ({@code OpenAiSetup.DEFAULT_USER_AGENT}).
	 */
	@Test
	void theUserAgentIsSpringAiOpenAi() {
		try (StandIn openai = new StandIn((request) -> Gpt.completion(request, "stop", Gpt.assistant("Hi")))) {
			Gpt.chatModel(openai).call(new Prompt("Hello"));

			assertThat(openai.single().header("User-Agent")).isEqualTo("spring-ai-openai");
		}
	}

	@Test
	void aStreamAsksForTheUsageAndDeliversTheTextInPieces() {
		try (StandIn openai = new StandIn((request) -> Gpt.completion(request, "stop", Gpt.assistant("Five pirates.")))) {
			List<ChatResponse> chunks = Gpt.chatModel(openai).stream(new Prompt("Name five pirates.")).collectList().block();

			JsonNode body = openai.single().body();
			assertThat(body.get("stream").asBoolean()).isTrue();
			assertThat(body.get("stream_options").toString()).isEqualTo("{\"include_usage\":true}");
			assertThat(chunks.stream().map((chunk) -> chunk.getResult() != null ? chunk.getResult().getOutput().getText() : "")
				.reduce("", String::concat)).isEqualTo("Five pirates.");
			ChatResponse last = chunks.get(chunks.size() - 1);
			assertThat(last.getMetadata().getUsage().getTotalTokens()).isEqualTo(40);
			assertThat(last.getMetadata().getRateLimit()).isInstanceOf(EmptyRateLimit.class);
		}
	}

	@Test
	void theRateLimitHeadersBecomeAnOpenAiRateLimitOnACallOnly() {
		try (StandIn openai = new StandIn((request) -> Gpt.completion(request, "stop", Gpt.assistant("Hi"))
			.header("x-ratelimit-limit-requests", "60")
			.header("x-ratelimit-remaining-requests", "59")
			.header("x-ratelimit-reset-requests", "1s")
			.header("x-ratelimit-limit-tokens", "150000")
			.header("x-ratelimit-remaining-tokens", "149984")
			.header("x-ratelimit-reset-tokens", "6m0s"))) {
			OpenAiChatModel model = Gpt.chatModel(openai);

			ChatResponse called = model.call(new Prompt("Hello"));
			List<ChatResponse> streamed = model.stream(new Prompt("Hello")).collectList().block();

			assertThat(called.getMetadata().getRateLimit()).isInstanceOfSatisfying(OpenAiRateLimit.class, (rateLimit) -> {
				assertThat(rateLimit.getRequestsLimit()).isEqualTo(60L);
				assertThat(rateLimit.getRequestsRemaining()).isEqualTo(59L);
				assertThat(rateLimit.getRequestsReset()).hasSeconds(1);
				assertThat(rateLimit.getTokensLimit()).isEqualTo(150000L);
				assertThat(rateLimit.getTokensRemaining()).isEqualTo(149984L);
				assertThat(rateLimit.getTokensReset()).hasMinutes(6);
			});
			assertThat(streamed).allSatisfy(
					(chunk) -> assertThat(chunk.getMetadata().getRateLimit()).isInstanceOf(EmptyRateLimit.class));
		}
	}

	@Test
	void theChatOptionsReachTheRequest() {
		try (StandIn openai = new StandIn((request) -> Gpt.completion(request, "stop", Gpt.assistant("Hi")))) {
			OpenAiChatModel model = Gpt.chatModel(openai);

			model.call(new Prompt("Hello", OpenAiChatOptions.builder()
				.model("gpt-5.4-mini")
				.temperature(0.4)
				.topP(0.9)
				.frequencyPenalty(0.5)
				.presencePenalty(-0.5)
				.logitBias(Map.of("50256", -100))
				.logprobs(true)
				.topLogprobs(3)
				.maxCompletionTokens(200)
				.n(2)
				.store(true)
				.metadata(Map.of("shop", "alice"))
				.seed(42)
				.stop(List.of("END", "STOP"))
				.user("alice")
				.reasoningEffort("low")
				.verbosity("high")
				.serviceTier("flex")
				.promptCacheKey("orders")
				.customHeaders(Map.of("X-Request-Id", "req-1"))
				.build()));
			model.call(new Prompt("Hello", OpenAiChatOptions.builder().stop(List.of("END")).maxTokens(100).build()));

			JsonNode body = openai.requests.get(0).body();
			assertThat(body.get("model").asString()).isEqualTo("gpt-5.4-mini");
			assertThat(body.get("temperature").asDouble()).isEqualTo(0.4);
			assertThat(body.get("top_p").asDouble()).isEqualTo(0.9);
			assertThat(body.get("frequency_penalty").asDouble()).isEqualTo(0.5);
			assertThat(body.get("presence_penalty").asDouble()).isEqualTo(-0.5);
			assertThat(body.get("logit_bias").toString()).isEqualTo("{\"50256\":-100}");
			assertThat(body.get("logprobs").asBoolean()).isTrue();
			assertThat(body.get("top_logprobs").asInt()).isEqualTo(3);
			assertThat(body.get("max_completion_tokens").asInt()).isEqualTo(200);
			assertThat(body.has("max_tokens")).isFalse();
			assertThat(body.get("n").asInt()).isEqualTo(2);
			assertThat(body.get("store").asBoolean()).isTrue();
			assertThat(body.get("metadata").toString()).isEqualTo("{\"shop\":\"alice\"}");
			assertThat(body.get("seed").asInt()).isEqualTo(42);
			assertThat(body.get("stop").toString()).isEqualTo("[\"END\",\"STOP\"]");
			assertThat(body.get("user").asString()).isEqualTo("alice");
			assertThat(body.get("reasoning_effort").asString()).isEqualTo("low");
			assertThat(body.get("verbosity").asString()).isEqualTo("high");
			assertThat(body.get("service_tier").asString()).isEqualTo("flex");
			assertThat(body.get("prompt_cache_key").asString()).isEqualTo("orders");
			assertThat(openai.requests.get(0).header("X-Request-Id")).isEqualTo("req-1");
			JsonNode second = openai.requests.get(1).body();
			assertThat(second.get("stop").toString()).as("one stop sequence is sent as a string").isEqualTo("\"END\"");
			assertThat(second.get("max_tokens").asInt()).isEqualTo(100);
		}
	}

	@Test
	void ofMaxTokensAndMaxCompletionTokensOnlyMaxCompletionTokensIsKept(CapturedOutput output) {
		OpenAiChatOptions first = OpenAiChatOptions.builder().maxTokens(100).maxCompletionTokens(200).build();
		OpenAiChatOptions second = OpenAiChatOptions.builder().maxCompletionTokens(200).maxTokens(100).build();

		assertThat(first.getMaxTokens()).isNull();
		assertThat(first.getMaxCompletionTokens()).isEqualTo(200);
		assertThat(second.getMaxTokens()).isNull();
		assertThat(second.getMaxCompletionTokens()).isEqualTo(200);
		assertThat(output).contains("Both maxTokens and maxCompletionTokens are set");
	}

	/**
	 * {@code verifyPromptChatOptions} warns about a top-k, but only sees one on portable
	 * options given to the model itself, which then fail (see below). Through a
	 * {@code ChatClient} the top-k is merged into {@code OpenAiChatOptions}, which have
	 * none, and is dropped without a word.
	 */
	@Test
	void topKIsDroppedWithoutAWarningThroughAChatClient(CapturedOutput output) {
		try (StandIn openai = new StandIn((request) -> Gpt.completion(request, "stop", Gpt.assistant("Hi")))) {
			ChatClient.create(Gpt.chatModel(openai)).prompt("Hello").options(ChatOptions.builder().topK(5)).call().content();

			assertThat(openai.single().body().has("top_k")).isFalse();
			assertThat(output).doesNotContain("The topK option is not supported");
		}
	}

	@Test
	void theOptionsOfAPromptReplaceTheDefaultsOfTheModelWhileAChatClientMergesThem() {
		try (StandIn openai = new StandIn((request) -> Gpt.completion(request, "stop", Gpt.assistant("Hi")))) {
			OpenAiChatModel model = Gpt.chatModel(openai,
					OpenAiChatOptions.builder().model(Gpt.MODEL).user("shop").maxCompletionTokens(500));

			model.call(new Prompt("Hello", OpenAiChatOptions.builder().temperature(0.2).build()));
			ChatClient.create(model).prompt("Hello").options(OpenAiChatOptions.builder().temperature(0.2)).call().content();

			JsonNode viaModel = openai.requests.get(0).body();
			assertThat(viaModel.get("model").asString()).isEqualTo(OpenAiChatOptions.DEFAULT_CHAT_MODEL);
			assertThat(viaModel.has("user")).isFalse();
			JsonNode viaClient = openai.requests.get(1).body();
			assertThat(viaClient.get("model").asString()).isEqualTo(Gpt.MODEL);
			assertThat(viaClient.get("user").asString()).isEqualTo("shop");
			assertThat(viaClient.get("max_completion_tokens").asInt()).isEqualTo(500);
			assertThat(viaClient.get("temperature").asDouble()).isEqualTo(0.2);
		}
	}

	/**
	 * The page says a portable {@code ChatOptions} can take the place of
	 * {@code OpenAiChatOptions}. On a prompt given to the model itself, 2.1.0 fails:
	 * {@code createRequest} casts the options to {@code OpenAiChatOptions}. Through a
	 * {@code ChatClient} it works.
	 */
	@Test
	void portableChatOptionsOnAPromptToTheModelFailWithAClassCastException() {
		try (StandIn openai = new StandIn((request) -> Gpt.completion(request, "stop", Gpt.assistant("Hi")))) {
			OpenAiChatModel model = Gpt.chatModel(openai);

			assertThatExceptionOfType(ClassCastException.class)
				.isThrownBy(() -> model.call(new Prompt("Hello", ChatOptions.builder().model("gpt-5.4").build())));
			ChatClient.create(model).prompt("Hello").options(ChatOptions.builder().model("gpt-5.4")).call().content();

			assertThat(openai.single().body().get("model").asString()).isEqualTo("gpt-5.4");
		}
	}

	@Test
	void theChatClientRunsTheToolsTheModelAsksForInACallAndInAStream() {
		try (StandIn openai = new StandIn((request) -> "tool".equals(Gpt.lastMessage(request).get("role").asString())
				? Gpt.completion(request, "stop", Gpt.assistant("Order " + Gpt.lastMessage(request).get("content").asString()))
				: Gpt.completion(request, "tool_calls",
						Gpt.toolCalls(Gpt.toolCall("call_1", "orderStatus", "{\"orderId\":\"A-1\"}"))))) {
			ChatClient client = ChatClient.create(Gpt.chatModel(openai));

			String called = client.prompt("Where is A-1?").tools(new Shop()).call().content();
			String streamed = String.join("",
					client.prompt("Where is A-1?").tools(new Shop()).stream().content().collectList().block());

			assertThat(called).isEqualTo("Order \"A-1 shipped\"");
			assertThat(streamed).isEqualTo("Order \"A-1 shipped\"");
			JsonNode tool = openai.requests.get(0).body().get("tools").get(0);
			assertThat(tool.get("type").asString()).isEqualTo("function");
			assertThat(tool.get("function").get("name").asString()).isEqualTo("orderStatus");
			assertThat(tool.get("function").get("strict").asBoolean()).as("strict is off by default").isFalse();
			for (Request second : List.of(openai.requests.get(1), openai.requests.get(3))) {
				JsonNode messages = second.body().get("messages");
				assertThat(messages.get(1).get("tool_calls").get(0).get("id").asString()).isEqualTo("call_1");
				assertThat(messages.get(1).get("tool_calls").get(0).get("function").get("arguments").asString())
					.isEqualTo("{\"orderId\":\"A-1\"}");
				assertThat(messages.get(2).get("role").asString()).isEqualTo("tool");
				assertThat(messages.get(2).get("tool_call_id").asString()).isEqualTo("call_1");
			}
		}
	}

	@Test
	void theStrictToolModeRequiresEveryPropertyAndNoOthers() {
		try (StandIn openai = new StandIn((request) -> Gpt.completion(request, "stop", Gpt.assistant("Hi")))) {
			ChatClient.create(Gpt.chatModel(openai, OpenAiChatOptions.builder().strict(true)))
				.prompt("Find it")
				.tools(new Search())
				.options(OpenAiChatOptions.builder().toolChoice("required").parallelToolCalls(false))
				.call()
				.content();

			JsonNode body = openai.single().body();
			JsonNode function = body.get("tools").get(0).get("function");
			assertThat(function.get("strict").asBoolean()).isTrue();
			assertThat(function.get("parameters").get("additionalProperties").asBoolean()).isFalse();
			assertThat(function.get("parameters").get("required").toString()).contains("query", "limit");
			assertThat(body.get("tool_choice").asString()).isEqualTo("required");
			assertThat(body.get("parallel_tool_calls").asBoolean()).isFalse();
		}
	}

	@Test
	void aToolChoiceCanNameAFunctionAsJson() {
		try (StandIn openai = new StandIn((request) -> Gpt.completion(request, "stop", Gpt.assistant("Hi")))) {
			ChatClient.create(Gpt.chatModel(openai))
				.prompt("Where is A-1?")
				.tools(new Shop())
				.options(OpenAiChatOptions.builder()
					.toolChoice("{\"type\":\"function\",\"function\":{\"name\":\"orderStatus\"}}"))
				.call()
				.content();

			assertThat(openai.single().body().get("tool_choice").toString())
				.isEqualTo("{\"function\":{\"name\":\"orderStatus\"},\"type\":\"function\"}");
		}
	}

	@Test
	void imagesAudioAndPdfsBecomeContentPartsInTheOrderOfTheParts() {
		byte[] png = { (byte) 0x89, 'P', 'N', 'G' };
		byte[] mp3 = { 'I', 'D', '3' };
		byte[] pdf = "%PDF-1.4".getBytes();
		try (StandIn openai = new StandIn((request) -> Gpt.completion(request, "stop", Gpt.assistant("Seen.")))) {
			OpenAiChatModel model = Gpt.chatModel(openai);

			model.call(new Prompt(UserMessage.builder()
				.part(TextPart.of("Look at "))
				.part(MediaPart.of(Media.builder().mimeType(MimeTypeUtils.IMAGE_PNG).data(png).build()))
				.part(TextPart.of("and listen to "))
				.part(MediaPart.of(Media.builder().mimeType(new MimeType("audio", "mp3")).data(mp3).build()))
				.part(TextPart.of("and read "))
				.part(MediaPart.of(Media.builder().mimeType(new MimeType("application", "pdf")).data(pdf).name("a.pdf").build()))
				.build()));
			model.call(new Prompt(UserMessage.builder()
				.text("Explain this picture.")
				.media(new Media(MimeTypeUtils.IMAGE_PNG, URI.create("https://example.com/fruit.png")))
				.build()));

			JsonNode content = Gpt.lastMessage(openai.requests.get(0)).get("content");
			assertThat(content).extracting((part) -> part.get("type").asString())
				.containsExactly("text", "image_url", "text", "input_audio", "text", "file");
			assertThat(content.get(1).get("image_url").get("url").asString())
				.isEqualTo("data:image/png;base64," + Base64.getEncoder().encodeToString(png));
			assertThat(content.get(3).get("input_audio").toString()).isEqualTo(
					"{\"data\":\"" + Base64.getEncoder().encodeToString(mp3) + "\",\"format\":\"mp3\"}");
			assertThat(content.get(5).get("file").get("file_data").asString())
				.isEqualTo("data:application/pdf;base64," + Base64.getEncoder().encodeToString(pdf));
			assertThat(content.get(5).get("file").get("filename").asString()).isEqualTo("a.pdf");
			JsonNode url = Gpt.lastMessage(openai.requests.get(1)).get("content");
			assertThat(url.get(0).get("text").asString()).isEqualTo("Explain this picture.");
			assertThat(url.get(1).get("image_url").get("url").asString()).isEqualTo("https://example.com/fruit.png");
		}
	}

	@Test
	void audioOutputIsAskedForAndArrivesAsTheTranscriptAndAMediaPart() {
		byte[] wav = { 'R', 'I', 'F', 'F' };
		String audio = "{\"role\":\"assistant\",\"content\":null,\"audio\":{\"id\":\"audio_1\",\"data\":\""
				+ Base64.getEncoder().encodeToString(wav) + "\",\"transcript\":\"Why did the bean...\",\"expires_at\":1700003600}}";
		try (StandIn openai = new StandIn((request) -> Gpt.completion(request, "stop", audio))) {
			ChatResponse response = Gpt.chatModel(openai)
				.call(new Prompt("Tell me a joke about Spring.",
						OpenAiChatOptions.builder()
							.model("gpt-audio-1.5")
							.outputModalities(List.of("text", "audio"))
							.outputAudio(new AudioParameters(AudioParameters.Voice.ALLOY,
									AudioParameters.AudioResponseFormat.WAV))
							.build()));

			JsonNode body = openai.single().body();
			assertThat(body.get("modalities").toString()).isEqualTo("[\"text\",\"audio\"]");
			assertThat(body.get("audio").toString()).isEqualTo("{\"format\":\"wav\",\"voice\":\"alloy\"}");
			AssistantMessage message = response.getResult().getOutput();
			assertThat(message.getText()).isEqualTo("Why did the bean...");
			assertThat(message.getMedia()).singleElement().satisfies((media) -> {
				assertThat(media.getMimeType().toString()).isEqualTo("audio/wav");
				assertThat(media.getDataAsByteArray()).isEqualTo(wav);
			});
			assertThat(message.getParts().get(message.getParts().size() - 1)).isInstanceOf(MediaPart.class);
			assertThat((String) response.getResult().getMetadata().get("audioId")).isEqualTo("audio_1");
		}
	}

	@Test
	void aJsonSchemaResponseFormatIsSentStrictAndTheReplyConverts() {
		BeanOutputConverter<MathReasoning> converter = new BeanOutputConverter<>(MathReasoning.class);
		try (StandIn openai = new StandIn((request) -> Gpt.completion(request, "stop",
				Gpt.assistant("{\"steps\":[\"subtract 7\",\"divide by 8\"],\"final_answer\":\"x = -3.75\"}")))) {
			OpenAiChatModel model = Gpt.chatModel(openai);

			String content = model
				.call(new Prompt("how can I solve 8x + 7 = -23", OpenAiChatOptions.builder()
					.responseFormat(ResponseFormat.builder()
						.type(ResponseFormat.Type.JSON_SCHEMA)
						.jsonSchema(converter.getJsonSchema())
						.build())
					.build()))
				.getResult()
				.getOutput()
				.getText();
			model.call(new Prompt("Answer in JSON", OpenAiChatOptions.builder()
				.responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build())
				.build()));
			model.call(new Prompt("Answer", OpenAiChatOptions.builder().outputSchema(converter.getJsonSchema()).build()));

			JsonNode format = openai.requests.get(0).body().get("response_format");
			assertThat(format.get("type").asString()).isEqualTo("json_schema");
			assertThat(format.get("json_schema").get("name").asString()).isEqualTo("json_schema");
			assertThat(format.get("json_schema").get("strict").asBoolean()).isTrue();
			assertThat(format.get("json_schema").get("schema").get("properties").has("final_answer")).isTrue();
			assertThat(openai.requests.get(1).body().get("response_format").toString())
				.isEqualTo("{\"type\":\"json_object\"}");
			assertThat(openai.requests.get(2).body().get("response_format").get("type").asString())
				.isEqualTo("json_schema");
			assertThat(converter.convert(content).finalAnswer()).isEqualTo("x = -3.75");
		}
	}

	/**
	 * The 2.1 upgrade guide: a finish reason the API does not define, such as
	 * {@code eos}, is kept as it was sent, and each one has a category.
	 */
	@Test
	void finishReasonsHaveACategoryAndOneTheApiDoesNotDefineIsKept() {
		Map<String, FinishReasonCategory> categories = new LinkedHashMap<>();
		categories.put("stop", FinishReasonCategory.COMPLETED);
		categories.put("length", FinishReasonCategory.TRUNCATED);
		categories.put("tool_calls", FinishReasonCategory.TOOL_CALL);
		categories.put("content_filter", FinishReasonCategory.FILTERED);
		categories.put("eos", FinishReasonCategory.OTHER);
		try (StandIn openai = new StandIn((request) -> Gpt.completion(request,
				request.body().get("messages").get(0).get("content").asString(), Gpt.assistant("Hi")))) {
			OpenAiChatModel model = Gpt.chatModel(openai);

			categories.forEach((reason, category) -> {
				ChatGenerationMetadata metadata = model.call(new Prompt(reason)).getResult().getMetadata();
				assertThat(metadata.getFinishReasonCategory()).as(reason).isEqualTo(category);
			});
			assertThat(model.call(new Prompt("eos")).getResult().getMetadata().getFinishReason()).isEqualTo("eos");
		}
	}

	@Test
	void aToolCallOnlyChoiceHasNoTextPartButAnEmptyText() {
		try (StandIn openai = new StandIn((request) -> Gpt.completion(request, "tool_calls",
				Gpt.toolCalls(Gpt.toolCall("call_1", "orderStatus", "{\"orderId\":\"A-1\"}"))))) {
			AssistantMessage message = Gpt.chatModel(openai).call(new Prompt("Where is A-1?")).getResult().getOutput();

			assertThat(message.getParts()).singleElement().isInstanceOf(ToolCallPart.class);
			assertThat(message.getText()).isEmpty();
		}
	}

	/**
	 * The 2.0 upgrade guide: OpenAI reports the cached input tokens it read.
	 */
	@Test
	void theCachedTokensOfTheUsageAreCacheReads() {
		try (StandIn openai = new StandIn((request) -> Reply.json(Gpt.completion("stop", Gpt.assistant("Hi"))
			.replace("\"total_tokens\":40}", "\"total_tokens\":40,\"prompt_tokens_details\":{\"cached_tokens\":20}}")))) {
			Usage usage = Gpt.chatModel(openai).call(new Prompt("Hello")).getMetadata().getUsage();

			assertThat(usage.getCacheReadInputTokens()).isEqualTo(20L);
			assertThat(usage.getCacheWriteInputTokens()).isNull();
		}
	}

	@Test
	void anEmptyKeyDisablesAuthentication() {
		try (StandIn local = new StandIn((request) -> Gpt.completion(request, "stop", Gpt.assistant("Hi")))) {
			OpenAiChatModel.builder()
				.options(OpenAiChatOptions.builder().baseUrl(local.url() + "/v1").apiKey("").maxRetries(0).build())
				.build()
				.call(new Prompt("Hello"));

			assertThat(local.single().header("Authorization")).isNull();
		}
	}

	@Test
	void anHttpClientCustomizerAddsItsInterceptorToEveryRequest() {
		try (StandIn openai = new StandIn((request) -> Gpt.completion(request, "stop", Gpt.assistant("Hi")))) {
			OpenAiChatModel model = OpenAiChatModel.builder()
				.options(OpenAiChatOptions.builder().baseUrl(openai.url() + "/v1").apiKey("test-key").maxRetries(0).build())
				.httpClientBuilderCustomizer((builder) -> builder.interceptor(
						(chain) -> chain.proceed(chain.request().newBuilder().header("X-Intercepted", "yes").build())))
				.build();

			model.call(new Prompt("Hello"));
			model.stream(new Prompt("Hello")).blockLast();

			assertThat(openai.requests).extracting((request) -> request.header("X-Intercepted"))
				.containsExactly("yes", "yes");
		}
	}

	record MathReasoning(List<String> steps, @com.fasterxml.jackson.annotation.JsonProperty("final_answer") String finalAnswer) {
	}

	static class Shop {

		@Tool(description = "Tells where an order is")
		String orderStatus(@ToolParam(description = "The order id") String orderId) {
			return orderId + " shipped";
		}

	}

	static class Search {

		@Tool(description = "Searches the orders")
		String search(@ToolParam(description = "What to look for") String query,
				@ToolParam(description = "How many at most", required = false) Integer limit) {
			return "none";
		}

	}

}
