package io.github.hjosugi.chatsample.chatclient;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.github.hjosugi.chatsample.support.ScriptedChatModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.ContentFilteredException;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.FinishReasonCategory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.converter.MapOutputConverter;
import org.springframework.core.ParameterizedTypeReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * What a {@code ChatClient} call returns, as the page "Responses and Streaming" shows it:
 * the {@code ChatResponse}, the finish reason and its category, entities, a filtered
 * response, and the streaming results.
 */
class ChatClientResponsesTests {

	private static final String FILMS = "{\"actor\":\"Tom Hanks\",\"movies\":[\"Big\",\"Cast Away\"]}";

	@Test
	void theChatResponseCarriesTheGenerationsAndTheTokensUsed() {
		ChatResponse response = ChatClient.create(ScriptedChatModel.answering("Why did the pirate laugh"))
			.prompt()
			.user("Tell me a joke")
			.call()
			.chatResponse();

		assertThat(response.getResults()).hasSize(1);
		assertThat(response.getResult().getOutput().getText()).isEqualTo("Why did the pirate laugh");
		assertThat(response.getMetadata().getUsage().getPromptTokens()).isEqualTo(4);
		assertThat(response.getMetadata().getUsage().getCompletionTokens()).isEqualTo(5);
	}

	@ParameterizedTest
	@CsvSource({ "stop, COMPLETED", "end_turn, COMPLETED", "STOP, COMPLETED", "stop_sequence, COMPLETED",
			"length, TRUNCATED", "max_tokens, TRUNCATED", "MAX_TOKENS, TRUNCATED", "tool_calls, TOOL_CALL",
			"tool_use, TOOL_CALL", "content_filter, FILTERED", "refusal, FILTERED", "SAFETY, FILTERED",
			"RECITATION, FILTERED", "guardrailRefusal, FILTERED", "MALFORMED_FUNCTION_CALL, OTHER",
			"something new, OTHER" })
	void aRawFinishReasonFallsIntoACategory(String raw, FinishReasonCategory category) {
		ChatResponse response = ChatClient.create(CannedChatModel.answering(CannedChatModel.response("x", raw)))
			.prompt("Tell me a joke")
			.call()
			.chatResponse();

		ChatGenerationMetadata metadata = response.getResult().getMetadata();
		assertThat(metadata.getFinishReason()).isEqualTo(raw);
		assertThat(metadata.getFinishReasonCategory()).isEqualTo(category);
		assertThat(response.hasFinishReasonCategory(category)).isTrue();
	}

	@Test
	void noFinishReasonIsUnspecified() {
		ChatResponse response = ChatClient.create(CannedChatModel.answering(CannedChatModel.response("x", null)))
			.prompt("Tell me a joke")
			.call()
			.chatResponse();

		assertThat(response.getResult().getMetadata().getFinishReasonCategory())
			.isEqualTo(FinishReasonCategory.UNSPECIFIED);
		assertThat(response.hasFinishReasonCategory(FinishReasonCategory.COMPLETED)).isFalse();
	}

	@Test
	void aChatModelOfYourOwnSetsTheCategoryItKnowsBetter() {
		// The provider said "stop", but the model knows that it refused.
		ChatResponse response = ChatClient
			.create(CannedChatModel.answering(
					CannedChatModel.response("I can't help", "stop", FinishReasonCategory.FILTERED, 1, 1)))
			.prompt("Tell me a joke")
			.call()
			.chatResponse();

		assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("stop");
		assertThat(response.getResult().getMetadata().getFinishReasonCategory())
			.isEqualTo(FinishReasonCategory.FILTERED);
	}

	@Test
	void eachChunkOfAStreamIsClassifiedByWhatItHolds() {
		List<ChatResponse> chunks = ChatClient.create(ScriptedChatModel.answering("Two words"))
			.prompt("Tell me a joke")
			.stream()
			.chatResponse()
			.collectList()
			.block();

		assertThat(chunks).extracting((chunk) -> chunk.getResult().getMetadata().getFinishReasonCategory())
			.containsExactly(FinishReasonCategory.UNSPECIFIED, FinishReasonCategory.UNSPECIFIED,
					FinishReasonCategory.COMPLETED);
	}

	@Test
	void entityMapsTheAnswerToARecordAndAsksForItsFormat() {
		ScriptedChatModel model = ScriptedChatModel.answering(FILMS);

		ActorFilms films = ChatClient.create(model)
			.prompt()
			.user("Generate the filmography for a random actor.")
			.call()
			.entity(ActorFilms.class);

		assertThat(films).isEqualTo(new ActorFilms("Tom Hanks", List.of("Big", "Cast Away")));
		assertThat(model.lastPrompt().getUserMessage().getText())
			.startsWith("Generate the filmography for a random actor.")
			.contains("Your response should be in JSON format.", "\"actor\"", "\"movies\"");
	}

	@Test
	void entityWithAParameterizedTypeMapsAList() {
		List<ActorFilms> films = ChatClient.create(ScriptedChatModel.answering("[" + FILMS + "]"))
			.prompt()
			.user("Generate the filmography of 5 movies for Tom Hanks and Bill Murray.")
			.call()
			.entity(new ParameterizedTypeReference<List<ActorFilms>>() {
			});

		assertThat(films).containsExactly(new ActorFilms("Tom Hanks", List.of("Big", "Cast Away")));
	}

	@Test
	void entityWithAConverterUsesThatConverter() {
		Map<String, Object> map = ChatClient.create(ScriptedChatModel.answering("{\"numbers\":[1,2,3]}"))
			.prompt("Provide an array of the numbers from 1 to 3 under the key 'numbers'.")
			.call()
			.entity(new MapOutputConverter());

		assertThat(map).containsEntry("numbers", List.of(1, 2, 3));
	}

	@Test
	void aFilteredEmptyAnswerThrowsAContentFilteredException() {
		CannedChatModel model = CannedChatModel.answering(CannedChatModel.response("", "content_filter"));

		assertThatExceptionOfType(ContentFilteredException.class)
			.isThrownBy(() -> ChatClient.create(model).prompt("Generate a filmography").call().entity(ActorFilms.class))
			.satisfies((ex) -> {
				assertThat(ex.getFinishReason()).isEqualTo("content_filter");
				assertThat(ex.getText()).isEmpty();
				assertThat(ex.getCause()).isNull();
				assertThat(ex.getChatClientResponse().chatResponse().getResult().getMetadata().getFinishReason())
					.isEqualTo("content_filter");
			});
	}

	@Test
	void aFilteredAnswerThatCannotBeConvertedThrowsWithTheParsingErrorAsCause() {
		CannedChatModel model = CannedChatModel.answering(CannedChatModel.response("I can't help with that.", "refusal"));

		assertThatExceptionOfType(ContentFilteredException.class)
			.isThrownBy(() -> ChatClient.create(model).prompt("Generate a filmography").call().entity(ActorFilms.class))
			.satisfies((ex) -> {
				assertThat(ex.getFinishReason()).isEqualTo("refusal");
				assertThat(ex.getText()).isEqualTo("I can't help with that.");
				assertThat(ex.getCause()).isNotNull();
			});
	}

	@Test
	void aFilteredAnswerThatConvertsAllTheSameIsReturned() {
		CannedChatModel model = CannedChatModel.answering(CannedChatModel.response(FILMS, "content_filter"));

		ActorFilms films = ChatClient.create(model).prompt("Generate a filmography").call().entity(ActorFilms.class);

		assertThat(films.actor()).isEqualTo("Tom Hanks");
	}

	@Test
	void anUnfilteredAnswerThatCannotBeConvertedThrowsTheParsingError() {
		CannedChatModel model = CannedChatModel.answering(CannedChatModel.response("Here you are", "stop"));

		assertThatExceptionOfType(RuntimeException.class)
			.isThrownBy(() -> ChatClient.create(model).prompt("Generate a filmography").call().entity(ActorFilms.class))
			.isNotInstanceOf(ContentFilteredException.class);
	}

	@Test
	void responseEntityDoesNotThrowForAFilteredAnswer() {
		CannedChatModel model = CannedChatModel.answering(CannedChatModel.response("I can't help with that.", "refusal"));

		ResponseEntity<ChatResponse, ActorFilms> result = ChatClient.create(model)
			.prompt("Generate a filmography")
			.call()
			.responseEntity(ActorFilms.class);

		assertThat(result.entity()).isNull();
		assertThat(result.response().getResult().getMetadata().getFinishReason()).isEqualTo("refusal");
	}

	@Test
	void contentChatResponseAndStreamingReturnAFilteredAnswerAsItIs() {
		CannedChatModel model = CannedChatModel.answering(CannedChatModel.response("I can't help with that.", "refusal"));
		ChatClient client = ChatClient.create(model);

		assertThat(client.prompt("x").call().content()).isEqualTo("I can't help with that.");
		assertThat(client.prompt("x").call().chatResponse().getResult().getMetadata().getFinishReasonCategory())
			.isEqualTo(FinishReasonCategory.FILTERED);
		assertThat(client.prompt("x").stream().content().collectList().block())
			.containsExactly("I can't help with that.");
	}

	@Test
	void responseEntityReturnsTheResponseAndTheEntity() {
		ResponseEntity<ChatResponse, ActorFilms> result = ChatClient.create(ScriptedChatModel.answering(FILMS))
			.prompt("Generate a filmography")
			.call()
			.responseEntity(ActorFilms.class);

		assertThat(result.entity().actor()).isEqualTo("Tom Hanks");
		assertThat(result.response().getMetadata().getUsage().getTotalTokens()).isPositive();
	}

	@Test
	void responseEntityTakesAParameterizedTypeAndAConverter() {
		ChatClient client = ChatClient.create(ScriptedChatModel.answering("[" + FILMS + "]"));

		ResponseEntity<ChatResponse, List<ActorFilms>> list = client.prompt("x")
			.call()
			.responseEntity(new ParameterizedTypeReference<List<ActorFilms>>() {
			});
		ResponseEntity<ChatResponse, List<ActorFilms>> converted = client.prompt("x")
			.call()
			.responseEntity(new BeanOutputConverter<>(new ParameterizedTypeReference<List<ActorFilms>>() {
			}), (spec) -> spec.validateSchema());

		assertThat(list.entity()).hasSize(1);
		assertThat(converted.entity()).hasSize(1);
	}

	@Test
	void chatClientResponseCarriesTheContextOfTheAdvisors() {
		ChatClientResponse response = ChatClient.create(ScriptedChatModel.answering("ok"))
			.prompt("Hi")
			.advisors((a) -> a.param("tenant", "shop-1"))
			.call()
			.chatClientResponse();

		assertThat(response.chatResponse().getResult().getOutput().getText()).isEqualTo("ok");
		assertThat(response.context()).containsEntry("tenant", "shop-1");
	}

	@Test
	void streamContentGivesTheTextAsItComes() {
		List<String> pieces = ChatClient.create(ScriptedChatModel.answering("Why did the pirate laugh"))
			.prompt()
			.user("Tell me a joke")
			.stream()
			.content()
			.collectList()
			.block();

		assertThat(pieces).containsExactly("Why ", "did ", "the ", "pirate ", "laugh");
	}

	@Test
	void streamChatClientResponseCarriesTheContextWithEachChunk() {
		List<ChatClientResponse> chunks = ChatClient.create(ScriptedChatModel.answering("Two words"))
			.prompt("Hi")
			.advisors((a) -> a.param("tenant", "shop-1"))
			.stream()
			.chatClientResponse()
			.collectList()
			.block();

		assertThat(chunks).hasSize(3).allSatisfy((chunk) -> assertThat(chunk.context()).containsEntry("tenant", "shop-1"));
	}

	@Test
	void aStreamBecomesAnEntityThroughAConverter() {
		var converter = new BeanOutputConverter<>(new ParameterizedTypeReference<List<ActorFilms>>() {
		});
		ScriptedChatModel model = ScriptedChatModel.answering("[ " + FILMS + " ]");

		String content = ChatClient.create(model)
			.prompt()
			.user((u) -> u.text("""
					Generate the filmography for a random actor.
					{format}
					""").param("format", converter.getFormat()))
			.stream()
			.content()
			.collectList()
			.block()
			.stream()
			.collect(Collectors.joining());

		assertThat(converter.convert(content)).containsExactly(new ActorFilms("Tom Hanks", List.of("Big", "Cast Away")));
		assertThat(ScriptedChatModel.lastText(model.lastPrompt())).contains("Your response should be in JSON format.");
	}

	record ActorFilms(String actor, List<String> movies) {
	}

}
