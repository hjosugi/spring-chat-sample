package io.github.hjosugi.chatsample.providers.anthropic;

import java.util.Base64;
import java.util.List;

import io.github.hjosugi.chatsample.support.StandIn;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.ai.anthropic.AnthropicCacheOptions;
import org.springframework.ai.anthropic.AnthropicCacheStrategy;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.AnthropicCitationDocument;
import org.springframework.ai.anthropic.Citation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * The citations page: how {@link AnthropicCitationDocument}s go into the request, and
 * how the citations of the reply come back in the response metadata.
 */
class AnthropicCitationsTests {

	private static final AnthropicCitationDocument EIFFEL = AnthropicCitationDocument.builder()
		.plainText("The Eiffel Tower was completed in 1889. It stands 330 meters tall.")
		.title("Eiffel Tower Facts")
		.context("From a travel guide")
		.citationsEnabled(true)
		.build();

	@Test
	void aPlainTextDocumentGoesBeforeTheQuestionWithItsTitleAndContext() {
		try (StandIn anthropic = new StandIn(Claude.answering("end_turn", Claude.text("In 1889.")))) {
			Claude.model(anthropic)
				.call(new Prompt("When was it built?", AnthropicChatOptions.builder().citationDocuments(EIFFEL).build()));

			JsonNode content = Claude.lastContent(anthropic.single());
			assertThat(content).hasSize(2);
			JsonNode document = content.get(0);
			assertThat(document.get("type").asString()).isEqualTo("document");
			assertThat(document.get("source").toString())
				.isEqualTo("{\"data\":\"The Eiffel Tower was completed in 1889. It stands 330 meters tall.\","
						+ "\"media_type\":\"text/plain\",\"type\":\"text\"}");
			assertThat(document.get("title").asString()).isEqualTo("Eiffel Tower Facts");
			assertThat(document.get("context").asString()).isEqualTo("From a travel guide");
			assertThat(document.get("citations").toString()).isEqualTo("{\"enabled\":true}");
			assertThat(content.get(1).get("text").asString()).isEqualTo("When was it built?");
		}
	}

	@Test
	void aPdfAndCustomContentHaveTheirOwnSourcesAndCitationsAreOffUnlessEnabled() {
		byte[] pdf = "%PDF-1.4 manual".getBytes();
		try (StandIn anthropic = new StandIn(Claude.answering("end_turn", Claude.text("Long.")))) {
			Claude.model(anthropic)
				.call(new Prompt("How long is the wall?", AnthropicChatOptions.builder()
					.citationDocuments(AnthropicCitationDocument.builder().pdf(pdf).title("Manual").build(),
							AnthropicCitationDocument.builder()
								.customContent("The wall is 21,196 km long.", "It was built over centuries.")
								.title("Great Wall Facts")
								.build())
					.build()));

			JsonNode content = Claude.lastContent(anthropic.single());
			assertThat(content.get(0).get("source").toString()).isEqualTo("{\"data\":\""
					+ Base64.getEncoder().encodeToString(pdf) + "\",\"media_type\":\"application/pdf\",\"type\":\"base64\"}");
			assertThat(content.get(1).get("source").toString())
				.isEqualTo("{\"content\":[{\"text\":\"The wall is 21,196 km long.\",\"type\":\"text\"},"
						+ "{\"text\":\"It was built over centuries.\",\"type\":\"text\"}],\"type\":\"content\"}");
			assertThat(content.get(0).get("citations").toString()).isEqualTo("{\"enabled\":false}");
			assertThat(content.get(1).get("citations").toString()).isEqualTo("{\"enabled\":false}");
		}
	}

	@Test
	void theDocumentsGoWithTheFirstUserMessageOnly() {
		try (StandIn anthropic = new StandIn(Claude.answering("end_turn", Claude.text("330 meters.")))) {
			Claude.model(anthropic)
				.call(new Prompt(
						List.of(new UserMessage("When was it built?"), new AssistantMessage("In 1889."),
								new UserMessage("How tall is it?")),
						AnthropicChatOptions.builder().citationDocuments(EIFFEL).build()));

			JsonNode messages = Claude.messages(anthropic.single());
			assertThat(Claude.content(messages.get(0)).get(0).get("type").asString()).isEqualTo("document");
			assertThat(messages.get(2).get("content").asString()).isEqualTo("How tall is it?");
		}
	}

	@Test
	void mixingDocumentsWithAndWithoutCitationsFailsWhenTheOptionsAreBuilt() {
		AnthropicCitationDocument background = AnthropicCitationDocument.builder().plainText("Background").build();

		assertThatIllegalArgumentException()
			.isThrownBy(() -> AnthropicChatOptions.builder().citationDocuments(EIFFEL, background).build())
			.withMessageContaining("consistent citation settings");
	}

	@Test
	void theLastDocumentIsCachedUnlessTheStrategyIsToolsOnly() {
		try (StandIn anthropic = new StandIn(Claude.answering("end_turn", Claude.text("In 1889.")))) {
			AnthropicChatModel model = Claude.model(anthropic);
			AnthropicCitationDocument second = AnthropicCitationDocument.builder()
				.plainText("Paris is the capital of France.")
				.citationsEnabled(true)
				.build();

			for (AnthropicCacheStrategy strategy : List.of(AnthropicCacheStrategy.SYSTEM_ONLY,
					AnthropicCacheStrategy.TOOLS_ONLY)) {
				model.call(new Prompt("When was it built?", AnthropicChatOptions.builder()
					.citationDocuments(EIFFEL, second)
					.cacheOptions(AnthropicCacheOptions.builder().strategy(strategy).build())
					.build()));
			}

			assertThat(AnthropicPromptCachingTests.cached(anthropic.requests.get(0).body()))
				.containsExactly("messages[0].content[1]");
			assertThat(AnthropicPromptCachingTests.cached(anthropic.requests.get(1).body())).isEmpty();
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	void theCitationsOfTheReplyAreInTheMetadataOfACallAndAStream() {
		String reply = Claude.citedText("Built in 1889, 330 meters tall.",
				"{\"type\":\"char_location\",\"cited_text\":\"completed in 1889\",\"document_index\":0,"
						+ "\"document_title\":\"Eiffel Tower Facts\",\"start_char_index\":22,\"end_char_index\":39}",
				"{\"type\":\"page_location\",\"cited_text\":\"330 meters\",\"document_index\":1,"
						+ "\"document_title\":\"Manual\",\"start_page_number\":2,\"end_page_number\":3}",
				"{\"type\":\"content_block_location\",\"cited_text\":\"tall\",\"document_index\":2,"
						+ "\"document_title\":null,\"start_block_index\":0,\"end_block_index\":2}");
		try (StandIn anthropic = new StandIn(Claude.answering("end_turn", reply))) {
			AnthropicChatModel model = Claude.model(anthropic);
			Prompt prompt = new Prompt("When was it built?", AnthropicChatOptions.builder().citationDocuments(EIFFEL).build());

			ChatResponse called = model.call(prompt);
			List<ChatResponse> chunks = model.stream(prompt).collectList().block();

			for (ChatResponse response : List.of(called, chunks.get(chunks.size() - 1))) {
				List<Citation> citations = (List<Citation>) response.getMetadata().get("citations");
				assertThat((Integer) response.getMetadata().get("citationCount")).isEqualTo(3);
				assertThat(citations).extracting(Citation::getType)
					.containsExactly(Citation.LocationType.CHAR_LOCATION, Citation.LocationType.PAGE_LOCATION,
							Citation.LocationType.CONTENT_BLOCK_LOCATION);
				Citation chars = citations.get(0);
				assertThat(chars.getCitedText()).isEqualTo("completed in 1889");
				assertThat(chars.getDocumentTitle()).isEqualTo("Eiffel Tower Facts");
				assertThat(chars.getStartCharIndex()).isEqualTo(22);
				assertThat(chars.getEndCharIndex()).isEqualTo(39);
				assertThat(chars.getLocationDescription()).isEqualTo("Characters 22-39");
				assertThat(citations.get(1).getDocumentIndex()).isEqualTo(1);
				assertThat(citations.get(1).getStartPageNumber()).isEqualTo(2);
				assertThat(citations.get(1).getLocationDescription()).isEqualTo("Page 2");
				assertThat(citations.get(2).getStartBlockIndex()).isZero();
				assertThat(citations.get(2).getLocationDescription()).isEqualTo("Blocks 0-1");
			}
			assertThat(called.getResult().getOutput().getText()).isEqualTo("Built in 1889, 330 meters tall.");
		}
	}

	@Test
	void aReplyWithoutCitationsHasNoCitationsKey() {
		try (StandIn anthropic = new StandIn(Claude.answering("end_turn", Claude.text("In 1889.")))) {
			ChatResponse response = Claude.model(anthropic).call(new Prompt("When?"));

			assertThat(response.getMetadata().containsKey("citations")).isFalse();
		}
	}

}
