package io.github.hjosugi.chatsample.providers.google;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import io.github.hjosugi.chatsample.support.StandIn;
import io.github.hjosugi.chatsample.support.StandIn.Reply;
import io.github.hjosugi.chatsample.support.StandIn.Request;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.part.MediaPart;
import org.springframework.ai.chat.messages.part.OpaquePayload;
import org.springframework.ai.chat.messages.part.ReasoningPart;
import org.springframework.ai.chat.messages.part.TextPart;
import org.springframework.ai.chat.messages.part.ToolCallPart;
import org.springframework.ai.chat.messages.part.UnknownPart;
import org.springframework.ai.chat.metadata.FinishReasonCategory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.google.genai.common.GoogleGenAiSafetySetting;
import org.springframework.ai.google.genai.common.GoogleGenAiServiceTier;
import org.springframework.ai.google.genai.common.GoogleGenAiThinkingLevel;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * The Google GenAI chat page: what Spring AI's {@link GoogleGenAiChatModel} sends to
 * the Gemini API, and to Vertex AI, through the Google GenAI SDK, and the message parts
 * it makes of the reply, called and streamed.
 */
class GoogleGenAiChatTests {

	@Test
	void aCallGoesToTheGeminiApiWithTheKeyAndReadsTheReply() {
		try (StandIn gemini = new StandIn((request) -> Gemini.reply(request, "STOP", Gemini.text("Five pirates.")))) {
			ChatResponse response = Gemini.model(gemini)
				.call(new Prompt(List.of(new SystemMessage("Be brief."), new UserMessage("Name five pirates."))));

			Request request = gemini.single();
			assertThat(request.path()).isEqualTo("/v1beta/models/" + Gemini.MODEL + ":generateContent");
			assertThat(request.header("x-goog-api-key")).isEqualTo("test-key");
			JsonNode body = request.body();
			assertThat(body.get("contents").toString())
				.isEqualTo("[{\"parts\":[{\"text\":\"Name five pirates.\"}],\"role\":\"user\"}]");
			assertThat(body.get("systemInstruction").get("parts").get(0).get("text").asString()).isEqualTo("Be brief.");
			AssistantMessage message = response.getResult().getOutput();
			assertThat(message.getText()).isEqualTo("Five pirates.");
			assertThat(message.getParts()).containsExactly(TextPart.of("Five pirates."));
			assertThat(message.getMetadata()).containsEntry("candidateIndex", 0);
			assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("STOP");
			assertThat(response.getMetadata().getId()).isEqualTo("resp-1");
			assertThat(response.getMetadata().getModel()).isEqualTo(Gemini.MODEL);
			assertThat(response.getMetadata().getUsage().getPromptTokens()).isEqualTo(25);
			assertThat(response.getMetadata().getUsage().getCompletionTokens()).isEqualTo(15);
		}
	}

	/**
	 * Not on the page: the model takes one system message at most.
	 */
	@Test
	void aPromptWithTwoSystemMessagesIsRefused() {
		try (StandIn gemini = new StandIn((request) -> Gemini.reply(request, "STOP", Gemini.text("Hi")))) {
			assertThatIllegalArgumentException()
				.isThrownBy(() -> Gemini.model(gemini)
					.call(new Prompt(List.of(new SystemMessage("A"), new SystemMessage("B"), new UserMessage("Hi")))))
				.withMessageContaining("Only one system message is allowed");
		}
	}

	@Test
	void aStreamAsksForServerSentEventsAndDeliversTheTextInPieces() {
		try (StandIn gemini = new StandIn((request) -> Gemini.reply(request, "STOP", Gemini.text("Five pirates.")))) {
			List<ChatResponse> chunks = Gemini.model(gemini).stream(new Prompt("Name five pirates.")).collectList().block();

			assertThat(gemini.single().path()).isEqualTo("/v1beta/models/" + Gemini.MODEL + ":streamGenerateContent");
			assertThat(gemini.single().query()).isEqualTo("alt=sse");
			assertThat(chunks).extracting((chunk) -> chunk.getResult().getOutput().getText())
				.containsExactly("Five p", "irates.");
			assertThat(chunks.get(1).getResult().getMetadata().getFinishReason()).isEqualTo("STOP");
		}
	}

	@Test
	void vertexAiModeAddressesTheProjectAndLocationWithAnAccessToken() {
		try (StandIn vertex = new StandIn((request) -> Gemini.reply(request, "STOP", Gemini.text("Hi")))) {
			Gemini.model(Gemini.vertexClient(vertex), GoogleGenAiChatOptions.builder().model(Gemini.MODEL))
				.call(new Prompt("Hello"));

			Request request = vertex.single();
			assertThat(request.path()).isEqualTo("/v1beta1/projects/shop-project/locations/us-central1/publishers/google/models/"
					+ Gemini.MODEL + ":generateContent");
			assertThat(request.header("Authorization")).isEqualTo("Bearer vertex-token");
			assertThat(request.header("x-goog-api-key")).isNull();
		}
	}

	@Test
	void theOptionsReachTheGenerationConfig() {
		try (StandIn gemini = new StandIn((request) -> Gemini.reply(request, "STOP", Gemini.text("Hi")))) {
			Gemini.model(gemini)
				.call(new Prompt("Hello", GoogleGenAiChatOptions.builder()
					.model("gemini-2.5-pro")
					.temperature(0.4)
					.topK(20)
					.topP(0.9)
					.candidateCount(2)
					.maxOutputTokens(512)
					.stopSequences(List.of("END"))
					.frequencyPenalty(0.1)
					.presencePenalty(0.2)
					.thinkingBudget(8192)
					.includeThoughts(true)
					.serviceTier(GoogleGenAiServiceTier.FLEX)
					.build()));

			Request request = gemini.single();
			assertThat(request.path()).isEqualTo("/v1beta/models/gemini-2.5-pro:generateContent");
			JsonNode config = request.body().get("generationConfig");
			assertThat(config.get("temperature").asDouble()).isEqualTo(0.4);
			assertThat(config.get("topK").asDouble()).isEqualTo(20);
			assertThat(config.get("topP").asDouble()).isCloseTo(0.9, org.assertj.core.data.Offset.offset(0.0001));
			assertThat(config.get("candidateCount").asInt()).isEqualTo(2);
			assertThat(config.get("maxOutputTokens").asInt()).isEqualTo(512);
			assertThat(config.get("stopSequences").toString()).isEqualTo("[\"END\"]");
			assertThat(config.get("frequencyPenalty").asDouble()).isCloseTo(0.1, org.assertj.core.data.Offset.offset(0.0001));
			assertThat(config.get("presencePenalty").asDouble()).isCloseTo(0.2, org.assertj.core.data.Offset.offset(0.0001));
			assertThat(config.get("thinkingConfig").toString())
				.isEqualTo("{\"includeThoughts\":true,\"thinkingBudget\":8192}");
			assertThat(request.body().toString()).containsIgnoringCase("flex");
		}
	}

	/**
	 * The page says each safety setting has a method, a threshold and a category. The
	 * method never reaches the request: {@code toGeminiSafetySettings} maps the category
	 * and the threshold only.
	 */
	@Test
	void safetySettingsAreSentWithoutTheirMethod() {
		try (StandIn gemini = new StandIn((request) -> Gemini.reply(request, "STOP", Gemini.text("Hi")))) {
			Gemini.model(Gemini.vertexClient(gemini), GoogleGenAiChatOptions.builder().model(Gemini.MODEL))
				.call(new Prompt("Hello", GoogleGenAiChatOptions.builder()
					.model(Gemini.MODEL)
					.safetySettings(List.of(new GoogleGenAiSafetySetting(
							GoogleGenAiSafetySetting.HarmCategory.HARM_CATEGORY_DANGEROUS_CONTENT,
							GoogleGenAiSafetySetting.HarmBlockThreshold.BLOCK_ONLY_HIGH,
							GoogleGenAiSafetySetting.HarmBlockMethod.PROBABILITY)))
					.build()));

			assertThat(gemini.single().body().get("safetySettings").toString())
				.isEqualTo("[{\"category\":\"HARM_CATEGORY_DANGEROUS_CONTENT\",\"threshold\":\"BLOCK_ONLY_HIGH\"}]");
		}
	}

	/**
	 * The page says a thinking level on a Gemini 2.5 model fails at the API. For the two
	 * Flash models Spring AI refuses it before the request; Gemini 2.5 Pro is not in its
	 * table, so the level goes to the API. A level and a budget together go as they are.
	 */
	@Test
	void theThinkingLevelIsCheckedAgainstTheModelsSpringAiKnows() {
		try (StandIn gemini = new StandIn((request) -> Gemini.reply(request, "STOP", Gemini.text("Hi")))) {
			GoogleGenAiChatModel model = Gemini.model(gemini);

			assertThatIllegalArgumentException().isThrownBy(() -> model.call(new Prompt("Hi", GoogleGenAiChatOptions
				.builder()
				.model("gemini-3.1-pro-preview")
				.thinkingLevel(GoogleGenAiThinkingLevel.MINIMAL)
				.build()))).withMessageContaining("Supported levels: LOW, MEDIUM, HIGH");
			assertThatIllegalArgumentException().isThrownBy(() -> model.call(new Prompt("Hi", GoogleGenAiChatOptions
				.builder()
				.model("gemini-3-pro-preview")
				.thinkingLevel(GoogleGenAiThinkingLevel.MEDIUM)
				.build())));
			assertThatIllegalArgumentException().isThrownBy(() -> model.call(new Prompt("Hi", GoogleGenAiChatOptions
				.builder()
				.model("gemini-2.5-flash")
				.thinkingLevel(GoogleGenAiThinkingLevel.LOW)
				.build()))).withMessageContaining("use thinkingBudget instead");
			model.call(new Prompt("Hi", GoogleGenAiChatOptions.builder()
				.model("gemini-2.5-pro")
				.thinkingLevel(GoogleGenAiThinkingLevel.HIGH)
				.thinkingBudget(1024)
				.build()));
			model.call(new Prompt("Hi", GoogleGenAiChatOptions.builder()
				.model(Gemini.MODEL)
				.thinkingLevel(GoogleGenAiThinkingLevel.MINIMAL)
				.build()));

			assertThat(gemini.requests).extracting(
					(request) -> request.body().get("generationConfig").get("thinkingConfig").toString())
				.containsExactly("{\"thinkingBudget\":1024,\"thinkingLevel\":\"HIGH\"}", "{\"thinkingLevel\":\"MINIMAL\"}");
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	void thoughtsAreReasoningPartsWhoseSignatureIsTheirPayload() {
		try (StandIn gemini = new StandIn((request) -> Gemini.reply(request, "STOP",
				Gemini.thought("Comparing Rome and Florence.", "sig-1"), Gemini.text("Rome.")))) {
			AssistantMessage message = Gemini.model(gemini).call(new Prompt("Where?")).getResult().getOutput();

			assertThat(message.getParts()).containsExactly(
					new ReasoningPart("Comparing Rome and Florence.", null, new OpaquePayload(
							GoogleGenAiChatModel.GOOGLE_PROVIDER, GoogleGenAiChatModel.PAYLOAD_THOUGHT_SIGNATURE,
							Gemini.base64("sig-1")), Map.of()),
					TextPart.of("Rome."));
			assertThat(message.getText()).isEqualTo("Rome.");
			assertThat(message.getReasoning()).extracting(ReasoningPart::text).containsExactly("Comparing Rome and Florence.");
			assertThat((List<byte[]>) message.getMetadata().get("thoughtSignatures")).singleElement()
				.isEqualTo("sig-1".getBytes());
		}
	}

	@Test
	void aStreamedThoughtKeepsTheSignatureOfItsLastDeltaWhenAggregated() {
		try (StandIn gemini = new StandIn((request) -> Gemini.reply(request, "STOP",
				Gemini.thought("Comparing Rome and Florence.", "sig-1"), Gemini.text("Rome.")))) {
			AtomicReference<ChatResponse> aggregated = new AtomicReference<>();
			new MessageAggregator().aggregate(Gemini.model(gemini).stream(new Prompt("Where?")), aggregated::set)
				.blockLast();

			assertThat(aggregated.get().getResult().getOutput().getParts()).containsExactly(
					new ReasoningPart("Comparing Rome and Florence.", null,
							new OpaquePayload("google", "thought_signature", Gemini.base64("sig-1")), Map.of()),
					TextPart.of("Rome."));
		}
	}

	/**
	 * As the page says, thoughts and signatures go back on the parts that carried them.
	 * Not on the page: the function response goes back with the name of its call but
	 * without its id, which Gemini sent ({@code messageToGeminiParts} builds the
	 * {@code FunctionResponse} from the name only), so two parallel calls of one function
	 * are told apart by their order alone.
	 */
	@Test
	void aToolLoopReplaysTheThoughtsAndTheSignatureOnTheirOwnPartsInACallAndInAStream() {
		try (StandIn gemini = new StandIn((request) -> Gemini.lastParts(request).get(0).has("functionResponse")
				? Gemini.reply(request, "STOP", Gemini.text("Shipped."))
				: Gemini.reply(request, "STOP", Gemini.thought("Look it up.", "sig-t"),
						Gemini.functionCall("fc_1", "orderStatus", "{\"orderId\":\"A-1\"}", "sig-f")))) {
			ChatClient client = ChatClient.create(Gemini.model(Gemini.client(gemini), GoogleGenAiChatOptions.builder()
				.model(Gemini.MODEL)
				.includeThoughts(true)));

			String called = client.prompt("Where is A-1?").tools(new Shop()).call().content();
			String streamed = String.join("",
					client.prompt("Where is A-1?").tools(new Shop()).stream().content().collectList().block());

			assertThat(called).isEqualTo("Shipped.");
			assertThat(streamed).isEqualTo("Shipped.");
			JsonNode declaration = gemini.requests.get(0).body().get("tools").get(0).get("functionDeclarations").get(0);
			assertThat(declaration.get("name").asString()).isEqualTo("orderStatus");
			for (Request second : List.of(gemini.requests.get(1), gemini.requests.get(3))) {
				JsonNode contents = second.body().get("contents");
				assertThat(contents.get(1).get("role").asString()).isEqualTo("model");
				JsonNode parts = contents.get(1).get("parts");
				assertThat(parts.get(0).get("thought").asBoolean()).isTrue();
				assertThat(parts.get(0).get("thoughtSignature").asString()).isEqualTo(Gemini.base64("sig-t"));
				assertThat(parts.get(1).get("functionCall").get("name").asString()).isEqualTo("orderStatus");
				assertThat(parts.get(1).get("functionCall").get("id").asString()).isEqualTo("fc_1");
				assertThat(parts.get(1).get("thoughtSignature").asString()).isEqualTo(Gemini.base64("sig-f"));
				JsonNode response = contents.get(2).get("parts").get(0).get("functionResponse");
				assertThat(response.toString()).as("the function response carries no id of its call")
					.isEqualTo("{\"name\":\"orderStatus\",\"response\":{\"result\":\"A-1 shipped\"}}");
			}
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	void googleSearchIsAToolAndItsInvocationsAreUnknownPartsAndMetadataReplayedVerbatim() {
		String toolCall = "{\"toolCall\":{\"id\":\"tc_1\",\"toolType\":\"GOOGLE_SEARCH_WEB\",\"args\":{\"query\":\"quantum\"}},"
				+ "\"thoughtSignature\":\"" + Gemini.base64("sig-s") + "\"}";
		String toolResponse = "{\"toolResponse\":{\"id\":\"tc_1\",\"toolType\":\"GOOGLE_SEARCH_WEB\","
				+ "\"response\":{\"results\":[\"a\"]}}}";
		try (StandIn gemini = new StandIn((request) -> Gemini.reply(request, "STOP", toolCall, toolResponse,
				Gemini.text("Qubits.")))) {
			GoogleGenAiChatModel model = Gemini.model(gemini);
			GoogleGenAiChatOptions options = GoogleGenAiChatOptions.builder()
				.model(Gemini.MODEL)
				.googleSearchRetrieval(true)
				.includeServerSideToolInvocations(true)
				.build();

			AssistantMessage message = model.call(new Prompt("Latest in quantum computing?", options))
				.getResult()
				.getOutput();
			model.call(new Prompt(List.of(new UserMessage("Latest?"), message, new UserMessage("More?")), options));

			JsonNode first = gemini.requests.get(0).body();
			assertThat(first.get("tools").toString()).isEqualTo("[{\"googleSearch\":{}}]");
			assertThat(first.get("toolConfig").get("includeServerSideToolInvocations").asBoolean()).isTrue();
			assertThat(message.getParts()).hasSize(3);
			assertThat(message.getParts().get(0)).isInstanceOfSatisfying(UnknownPart.class, (part) -> {
				assertThat(part.provider()).isEqualTo("google");
				assertThat(part.kind()).isEqualTo("toolCall");
			});
			assertThat(message.getParts().get(1)).isInstanceOfSatisfying(UnknownPart.class,
					(part) -> assertThat(part.kind()).isEqualTo("toolResponse"));
			assertThat(message.getToolCalls()).isEmpty();
			List<Map<String, Object>> invocations = (List<Map<String, Object>>) message.getMetadata()
				.get("serverSideToolInvocations");
			assertThat(invocations).extracting((invocation) -> invocation.get("type"))
				.containsExactly("toolCall", "toolResponse");
			assertThat(invocations.get(0)).containsEntry("id", "tc_1").containsEntry("toolType", "GOOGLE_SEARCH_WEB");
			JsonNode replayed = gemini.requests.get(1).body().get("contents").get(1).get("parts");
			assertThat(replayed.get(0).get("toolCall").get("id").asString()).isEqualTo("tc_1");
			assertThat(replayed.get(0).get("thoughtSignature").asString()).isEqualTo(Gemini.base64("sig-s"));
			assertThat(replayed.get(1).get("toolResponse").get("id").asString()).isEqualTo("tc_1");
		}
	}

	@Test
	void theToolChoiceBecomesTheFunctionCallingConfigWithItsNamesOnlyForAnyAndValidated() {
		try (StandIn gemini = new StandIn((request) -> Gemini.reply(request, "STOP", Gemini.text("Hi")))) {
			ChatClient client = ChatClient.create(Gemini.model(gemini));

			client.prompt("Where is A-1?")
				.tools(new Shop())
				.options(GoogleGenAiChatOptions.builder()
					.toolChoice(new GoogleGenAiChatOptions.ToolChoice(GoogleGenAiChatOptions.ToolChoice.Mode.ANY,
							List.of("orderStatus"))))
				.call()
				.content();
			client.prompt("Where is A-1?")
				.tools(new Shop())
				.options(GoogleGenAiChatOptions.builder()
					.toolChoice(new GoogleGenAiChatOptions.ToolChoice(GoogleGenAiChatOptions.ToolChoice.Mode.AUTO,
							List.of("orderStatus"))))
				.call()
				.content();
			client.prompt("Where is A-1?")
				.tools(new Shop())
				.options(GoogleGenAiChatOptions.builder()
					.toolChoice(new GoogleGenAiChatOptions.ToolChoice(GoogleGenAiChatOptions.ToolChoice.Mode.NONE, null)))
				.call()
				.content();

			assertThat(gemini.requests).extracting((request) -> request.body().get("toolConfig").toString())
				.containsExactly("{\"functionCallingConfig\":{\"allowedFunctionNames\":[\"orderStatus\"],\"mode\":\"ANY\"}}",
						"{\"functionCallingConfig\":{\"mode\":\"AUTO\"}}", "{\"functionCallingConfig\":{\"mode\":\"NONE\"}}");
		}
	}

	@Test
	void mediaBecomesInlineDataInTheOrderOfThePartsAndInlineDataComesBackAsMedia() {
		byte[] png = { (byte) 0x89, 'P', 'N', 'G' };
		byte[] pdf = "%PDF-1.4".getBytes();
		String image = "{\"inlineData\":{\"mimeType\":\"image/png\",\"data\":\"" + Base64.getEncoder().encodeToString(png)
				+ "\"}}";
		try (StandIn gemini = new StandIn((request) -> Gemini.reply(request, "STOP", Gemini.text("A cookie."), image))) {
			AssistantMessage message = Gemini.model(gemini)
				.call(new Prompt(UserMessage.builder()
					.part(TextPart.of("Look at "))
					.part(MediaPart.of(Media.builder().mimeType(MimeTypeUtils.IMAGE_PNG).data(png).build()))
					.part(TextPart.of("and read "))
					.part(MediaPart.of(Media.builder().mimeType(new MimeType("application", "pdf")).data(pdf).build()))
					.build()))
				.getResult()
				.getOutput();

			JsonNode parts = Gemini.lastParts(gemini.single());
			assertThat(parts.toString()).isEqualTo("[{\"text\":\"Look at \"},{\"inlineData\":{\"data\":\""
					+ Base64.getEncoder().encodeToString(png) + "\",\"mimeType\":\"image/png\"}},{\"text\":\"and read \"},"
					+ "{\"inlineData\":{\"data\":\"" + Base64.getEncoder().encodeToString(pdf)
					+ "\",\"mimeType\":\"application/pdf\"}}]");
			assertThat(message.getParts().get(1)).isInstanceOf(MediaPart.class);
			assertThat(message.getMedia()).singleElement()
				.satisfies((media) -> assertThat(media.getDataAsByteArray()).isEqualTo(png));
		}
	}

	@Test
	void cachedContentIsUsedOnlyWhenAskedForAndAutoCachingDoesNothing() {
		try (StandIn gemini = new StandIn((request) -> Gemini.reply(request, "STOP", Gemini.text("Hi")))) {
			GoogleGenAiChatModel model = Gemini.model(gemini);

			model.call(new Prompt("Summarize", GoogleGenAiChatOptions.builder()
				.model(Gemini.MODEL)
				.useCachedContent(true)
				.cachedContentName("cachedContents/abc")
				.build()));
			model.call(new Prompt("Summarize", GoogleGenAiChatOptions.builder()
				.model(Gemini.MODEL)
				.cachedContentName("cachedContents/abc")
				.autoCacheThreshold(1)
				.autoCacheTtl(java.time.Duration.ofMinutes(5))
				.build()));

			assertThat(gemini.requests.get(0).body().get("cachedContent").asString()).isEqualTo("cachedContents/abc");
			assertThat(gemini.requests.get(1).body().has("cachedContent")).isFalse();
			assertThat(gemini.requests).hasSize(2).extracting(Request::path).allMatch((path) -> path.contains(":generateContent"));
		}
	}

	@Test
	void structuredOutputBecomesAJsonMimeTypeAndSchema() {
		try (StandIn gemini = new StandIn((request) -> Gemini.reply(request, "STOP",
				Gemini.text("{\"actor\":\"Tom Hanks\",\"movies\":[\"Big\"]}")))) {
			ActorFilms films = ChatClient.create(Gemini.model(gemini))
				.prompt("Filmography of a random actor")
				.advisors(AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT)
				.call()
				.entity(ActorFilms.class);

			JsonNode config = gemini.single().body().get("generationConfig");
			assertThat(config.get("responseMimeType").asString()).isEqualTo("application/json");
			assertThat(config.get("responseJsonSchema").get("properties").has("movies")).isTrue();
			assertThat(films).isEqualTo(new ActorFilms("Tom Hanks", List.of("Big")));
		}
	}

	@Test
	void aFunctionCallIsAToolCallPartWithItsId() {
		try (StandIn gemini = new StandIn((request) -> Gemini.reply(request, "STOP",
				Gemini.functionCall("fc_1", "orderStatus", "{\"orderId\":\"A-1\"}", null)))) {
			AssistantMessage message = Gemini.model(gemini).call(new Prompt("Where is A-1?")).getResult().getOutput();

			assertThat(message.getParts()).singleElement().isInstanceOfSatisfying(ToolCallPart.class, (part) -> {
				assertThat(part.toolCall().id()).isEqualTo("fc_1");
				assertThat(part.toolCall().arguments()).isEqualTo("{\"orderId\":\"A-1\"}");
			});
		}
	}

	/**
	 * The 2.1 upgrade guide: one generation per candidate, holding its thoughts, so that
	 * {@code content()} is the answer rather than the first thought.
	 */
	@Test
	void eachCandidateIsOneGenerationAndTheContentIsTheAnswer() {
		String twoCandidates = "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[" + Gemini.thought("Hmm.", "s")
				+ "," + Gemini.text("Rome.") + "]},\"finishReason\":\"STOP\",\"index\":0},{\"content\":{\"role\":\"model\","
				+ "\"parts\":[" + Gemini.text("Florence.") + "]},\"finishReason\":\"STOP\",\"index\":1}],\"responseId\":\"resp-1\"}";
		try (StandIn gemini = new StandIn((request) -> Reply.json(twoCandidates))) {
			GoogleGenAiChatModel model = Gemini.model(gemini);

			ChatResponse response = model.call(new Prompt("Where?",
					GoogleGenAiChatOptions.builder().model(Gemini.MODEL).candidateCount(2).includeThoughts(true).build()));
			String content = ChatClient.create(model)
				.prompt("Where?")
				.options(GoogleGenAiChatOptions.builder().includeThoughts(true))
				.call()
				.content();

			assertThat(response.getResults()).extracting((generation) -> generation.getOutput().getText())
				.containsExactly("Rome.", "Florence.");
			assertThat(response.getResults().get(0).getOutput().getMetadata()).doesNotContainKey("isThought");
			assertThat(content).isEqualTo("Rome.");
		}
	}

	@Test
	void finishReasonsHaveACategoryAndAFunctionCallIsAToolCall() {
		Map<String, FinishReasonCategory> categories = new LinkedHashMap<>();
		categories.put("STOP", FinishReasonCategory.COMPLETED);
		categories.put("MAX_TOKENS", FinishReasonCategory.TRUNCATED);
		categories.put("SAFETY", FinishReasonCategory.FILTERED);
		categories.put("MALFORMED_FUNCTION_CALL", FinishReasonCategory.OTHER);
		try (StandIn gemini = new StandIn((request) -> {
			String asked = Gemini.lastParts(request).get(0).get("text").asString();
			return asked.equals("call") ? Gemini.reply(request, "STOP", Gemini.functionCall("fc_1", "f", "{}", null))
					: Gemini.reply(request, asked, Gemini.text("Hi"));
		})) {
			GoogleGenAiChatModel model = Gemini.model(gemini);

			categories.forEach((reason, category) -> assertThat(
					model.call(new Prompt(reason)).getResult().getMetadata().getFinishReasonCategory())
				.as(reason)
				.isEqualTo(category));
			assertThat(model.call(new Prompt("call")).getResult().getMetadata().getFinishReasonCategory())
				.isEqualTo(FinishReasonCategory.TOOL_CALL);
		}
	}

	/**
	 * The 2.0 upgrade guide: Google reports the cached input tokens it read.
	 */
	@Test
	void theCachedContentTokensOfTheUsageAreCacheReads() {
		try (StandIn gemini = new StandIn((request) -> Reply.json(Gemini.response("STOP", Gemini.text("Hi"))
			.replace("\"thoughtsTokenCount\":5", "\"thoughtsTokenCount\":5,\"cachedContentTokenCount\":20")))) {
			Usage usage = Gemini.model(gemini).call(new Prompt("Hello")).getMetadata().getUsage();

			assertThat(usage.getCacheReadInputTokens()).isEqualTo(20L);
		}
	}

	record ActorFilms(String actor, List<String> movies) {
	}

	static class Shop {

		@Tool(description = "Tells where an order is")
		String orderStatus(String orderId) {
			return orderId + " shipped";
		}

	}

}
