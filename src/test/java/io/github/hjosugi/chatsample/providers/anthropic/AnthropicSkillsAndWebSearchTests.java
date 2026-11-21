package io.github.hjosugi.chatsample.providers.anthropic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import io.github.hjosugi.chatsample.support.StandIn;
import io.github.hjosugi.chatsample.support.StandIn.Reply;
import io.github.hjosugi.chatsample.support.StandIn.Request;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.AnthropicSkill;
import org.springframework.ai.anthropic.AnthropicSkillContainer;
import org.springframework.ai.anthropic.AnthropicSkillsResponseHelper;
import org.springframework.ai.anthropic.AnthropicWebSearchResult;
import org.springframework.ai.anthropic.AnthropicWebSearchTool;
import org.springframework.ai.anthropic.Citation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.part.TextPart;
import org.springframework.ai.chat.messages.part.UnknownPart;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * The skills and web search page: what skills and the web search tool add to the
 * request, how server tool blocks come back as {@link UnknownPart}s and go back to
 * Anthropic as they came, and how the files a skill made are found and downloaded.
 */
class AnthropicSkillsAndWebSearchTests {

	private static final String SERVER_TOOL_USE = "{\"type\":\"server_tool_use\",\"id\":\"srvtoolu_1\","
			+ "\"name\":\"web_search\",\"input\":{\"query\":\"Spring AI release\"}}";

	private static final String SEARCH_RESULT = "{\"type\":\"web_search_tool_result\",\"tool_use_id\":\"srvtoolu_1\","
			+ "\"content\":[{\"type\":\"web_search_result\",\"title\":\"Spring AI 2.1\",\"url\":\"https://spring.io/blog\","
			+ "\"encrypted_content\":\"enc-1\",\"page_age\":\"2 days\"}]}";

	private static final String CITED_ANSWER = Claude.citedText("Spring AI 2.1 is out.",
			"{\"type\":\"web_search_result_location\",\"cited_text\":\"2.1 is out\",\"url\":\"https://spring.io/blog\","
					+ "\"title\":\"Spring AI 2.1\",\"encrypted_index\":\"idx-1\"}");

	/**
	 * The page does not say what becomes of an {@code anthropic-beta} header of the
	 * request. {@code createRequest} merges the skill betas into its value and puts the
	 * result with {@code putAdditionalHeader}, which adds a header rather than replacing
	 * one, so the request carries both: {@code my-beta} appears twice in the combined
	 * value.
	 */
	@Test
	void skillsAddTheirContainerTheCodeExecutionToolAndTheBetaHeaders() {
		try (StandIn anthropic = new StandIn(Claude.answering("end_turn", Claude.text("Made it.")))) {
			AnthropicChatModel model = Claude.model(anthropic);

			model.call(new Prompt("Make a spreadsheet and a deck.", AnthropicChatOptions.builder()
				.skill(AnthropicSkill.XLSX)
				.skill(AnthropicSkill.PPTX, "20251013")
				.build()));
			model.call(new Prompt("Make a report.", AnthropicChatOptions.builder()
				.skillContainer(AnthropicSkillContainer.builder().skill("my-report-skill").build())
				.httpHeaders(Map.of("anthropic-beta", "my-beta"))
				.build()));

			JsonNode body = anthropic.requests.get(0).body();
			assertThat(body.get("container").get("skills")).hasSize(2);
			assertThat(body.get("container").get("skills").get(0).get("skill_id").asString()).isEqualTo("xlsx");
			assertThat(body.get("container").get("skills").get(0).get("type").asString()).isEqualTo("anthropic");
			assertThat(body.get("container").get("skills").get(0).get("version").asString()).isEqualTo("latest");
			assertThat(body.get("container").get("skills").get(1).get("version").asString()).isEqualTo("20251013");
			assertThat(body.get("tools")).extracting((tool) -> tool.get("type").asString())
				.containsExactly("code_execution_20260120");
			assertThat(anthropic.requests.get(0).header("anthropic-beta"))
				.isEqualTo("skills-2025-10-02,code-execution-2025-08-25,files-api-2025-04-14");
			JsonNode custom = anthropic.requests.get(1).body().get("container").get("skills").get(0);
			assertThat(custom.get("type").asString()).isEqualTo("custom");
			assertThat(custom.get("skill_id").asString()).isEqualTo("my-report-skill");
			assertThat(betaHeaders(anthropic.requests.get(1)))
				.as("the merged value is added to the header of the request, not put in its place")
				.containsExactly("my-beta", "my-beta,skills-2025-10-02,code-execution-2025-08-25,files-api-2025-04-14");
		}
	}

	private static List<String> betaHeaders(Request request) {
		return request.headers()
			.entrySet()
			.stream()
			.filter((header) -> header.getKey().equalsIgnoreCase("anthropic-beta"))
			.flatMap((header) -> header.getValue().stream())
			.toList();
	}

	@Test
	void moreThanEightSkillsAreRefused() {
		AnthropicSkillContainer.Builder container = AnthropicSkillContainer.builder();
		for (int i = 0; i < 9; i++) {
			container.skill("skill-" + i);
		}

		assertThatIllegalArgumentException().isThrownBy(container::build)
			.withMessageContaining("Maximum of 8 skills per request");
	}

	/**
	 * Unlike every other option, the skills of the model are used when the options of a
	 * request have none: {@code createRequest} falls back to them on its own.
	 */
	@Test
	void theSkillsOfTheModelApplyToARequestWhoseOptionsHaveNone() {
		try (StandIn anthropic = new StandIn(Claude.answering("end_turn", Claude.text("Made it.")))) {
			AnthropicChatModel model = Claude.model(anthropic, AnthropicChatOptions.builder().skill(AnthropicSkill.DOCX));

			model.call(new Prompt("Make a letter.", AnthropicChatOptions.builder().maxTokens(1000).build()));

			assertThat(anthropic.single().body().get("container").get("skills").get(0).get("skill_id").asString())
				.isEqualTo("docx");
		}
	}

	@Test
	void theFilesAndTheContainerOfASkillReplyAreFoundAndDownloaded(@TempDir Path directory) throws Exception {
		String message = Claude.message("end_turn", Claude.text("Here is your spreadsheet."),
				"{\"type\":\"container_upload\",\"file_id\":\"file_1\"}")
			.replaceFirst("\\{", "{\"container\":{\"id\":\"cntr_1\",\"expires_at\":\"2026-11-22T00:00:00Z\",\"skills\":[]},");
		try (StandIn anthropic = new StandIn((request) -> switch (request.path()) {
			case "/v1/files/file_1" -> Reply.json("{\"id\":\"file_1\",\"type\":\"file\",\"filename\":\"sales.xlsx\","
					+ "\"mime_type\":\"application/vnd.ms-excel\",\"size_bytes\":5,\"created_at\":\"2026-11-21T00:00:00Z\"}");
			case "/v1/files/file_1/content" -> Reply.bytes("application/octet-stream", "bytes".getBytes());
			default -> Reply.json(message);
		})) {
			AnthropicChatModel model = Claude.model(anthropic);
			ChatResponse response = model.call(new Prompt("Make a spreadsheet.",
					AnthropicChatOptions.builder().skill(AnthropicSkill.XLSX).build()));

			List<Path> files = AnthropicSkillsResponseHelper.downloadAllFiles(response, model.getAnthropicClient(),
					directory);

			assertThat(AnthropicSkillsResponseHelper.extractFileIds(response)).containsExactly("file_1");
			assertThat(AnthropicSkillsResponseHelper.extractContainerId(response)).isEqualTo("cntr_1");
			assertThat(files).containsExactly(directory.resolve("sales.xlsx"));
			assertThat(Files.readString(files.get(0))).isEqualTo("bytes");
			assertThat(anthropic.requests).extracting(Request::path)
				.containsExactly("/v1/messages", "/v1/files/file_1", "/v1/files/file_1/content");
			assertThat(anthropic.requests.get(2).apiKey()).isEqualTo("test-key");
		}
	}

	@Test
	void theWebSearchToolAndItsOptionsReachTheRequest() {
		try (StandIn anthropic = new StandIn(Claude.answering("end_turn", Claude.text("2.1")))) {
			Claude.model(anthropic)
				.call(new Prompt("Latest Spring AI?", AnthropicChatOptions.builder()
					.webSearchTool(AnthropicWebSearchTool.builder()
						.allowedDomains(List.of("docs.spring.io", "github.com"))
						.blockedDomains(List.of("example.com"))
						.maxUses(5)
						.userLocation("San Francisco", "US", "California", "America/Los_Angeles")
						.build())
					.build()));

			JsonNode tool = anthropic.single().body().get("tools").get(0);
			assertThat(tool.get("type").asString()).isEqualTo("web_search_20260209");
			assertThat(tool.get("name").asString()).isEqualTo("web_search");
			assertThat(tool.get("max_uses").asInt()).isEqualTo(5);
			assertThat(tool.get("allowed_domains").toString()).isEqualTo("[\"docs.spring.io\",\"github.com\"]");
			assertThat(tool.get("blocked_domains").toString()).isEqualTo("[\"example.com\"]");
			assertThat(tool.get("user_location").get("type").asString()).isEqualTo("approximate");
			assertThat(tool.get("user_location").get("city").asString()).isEqualTo("San Francisco");
			assertThat(tool.get("user_location").get("country").asString()).isEqualTo("US");
			assertThat(tool.get("user_location").get("region").asString()).isEqualTo("California");
			assertThat(tool.get("user_location").get("timezone").asString()).isEqualTo("America/Los_Angeles");
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	void serverToolBlocksBecomeUnknownPartsAndTheResultsAndCitationsMetadata() {
		try (StandIn anthropic = new StandIn(Claude.answering("end_turn", SERVER_TOOL_USE, SEARCH_RESULT, CITED_ANSWER))) {
			AnthropicChatModel model = Claude.model(anthropic);
			Prompt prompt = new Prompt("Latest Spring AI?",
					AnthropicChatOptions.builder().webSearchTool(AnthropicWebSearchTool.builder().build()).build());

			ChatResponse called = model.call(prompt);
			AtomicReference<ChatResponse> aggregated = new AtomicReference<>();
			List<ChatResponse> chunks = new MessageAggregator().aggregate(model.stream(prompt), aggregated::set)
				.collectList()
				.block();

			for (AssistantMessage message : List.of(called.getResult().getOutput(),
					aggregated.get().getResult().getOutput())) {
				assertThat(message.getParts()).hasSize(3);
				assertThat(message.getParts().get(0)).isInstanceOfSatisfying(UnknownPart.class, (part) -> {
					assertThat(part.provider()).isEqualTo("anthropic");
					assertThat(part.kind()).isEqualTo("server_tool_use");
					assertThat(part.rawJson()).contains("\"query\":\"Spring AI release\"", "\"id\":\"srvtoolu_1\"");
				});
				assertThat(message.getParts().get(1)).isInstanceOfSatisfying(UnknownPart.class, (part) -> {
					assertThat(part.kind()).isEqualTo("web_search_tool_result");
					assertThat(part.rawJson()).contains("\"encrypted_content\":\"enc-1\"");
				});
				assertThat(message.getParts().get(2)).isEqualTo(TextPart.of("Spring AI 2.1 is out."));
				assertThat(message.getToolCalls()).as("server tools are not tool calls").isEmpty();
			}
			for (ChatResponse response : List.of(called, chunks.get(chunks.size() - 1))) {
				assertThat((List<AnthropicWebSearchResult>) response.getMetadata().get("web-search-results"))
					.containsExactly(new AnthropicWebSearchResult("Spring AI 2.1", "https://spring.io/blog", "2 days"));
				List<Citation> citations = (List<Citation>) response.getMetadata().get("citations");
				assertThat(citations).singleElement().satisfies((citation) -> {
					assertThat(citation.getType()).isEqualTo(Citation.LocationType.WEB_SEARCH_RESULT_LOCATION);
					assertThat(citation.getUrl()).isEqualTo("https://spring.io/blog");
					assertThat(citation.getDocumentTitle()).isEqualTo("Spring AI 2.1");
					assertThat(citation.getCitedText()).isEqualTo("2.1 is out");
				});
			}
		}
	}

	/**
	 * A turn paused with {@code pause_turn} goes on when the paused message is sent back:
	 * its server tool blocks are replayed as they came, after a call and after a stream.
	 * An unknown part of another provider is not.
	 */
	@Test
	void serverToolBlocksAreReplayedToAnthropicAsTheyCame() {
		try (StandIn anthropic = new StandIn((request) -> (Claude.messages(request).size() == 1)
				? Claude.reply(request, "pause_turn", SERVER_TOOL_USE, SEARCH_RESULT)
				: Claude.reply(request, "end_turn", CITED_ANSWER))) {
			AnthropicChatModel model = Claude.model(anthropic);
			AnthropicChatOptions options = AnthropicChatOptions.builder()
				.webSearchTool(AnthropicWebSearchTool.builder().build())
				.build();
			UserMessage question = new UserMessage("Latest Spring AI?");

			ChatResponse paused = model.call(new Prompt(question, options));
			AtomicReference<ChatResponse> streamed = new AtomicReference<>();
			new MessageAggregator().aggregate(model.stream(new Prompt(question, options)), streamed::set).blockLast();
			for (ChatResponse response : List.of(paused, streamed.get())) {
				AssistantMessage withForeignPart = response.getResult()
					.getOutput()
					.mutate()
					.part(new UnknownPart("google", "toolCall", "{\"toolCall\":{}}", null, Map.of()))
					.build();
				model.call(new Prompt(List.of(question, withForeignPart), options));
			}

			assertThat(paused.getResult().getMetadata().getFinishReason()).isEqualTo("pause_turn");
			for (Request resumed : List.of(anthropic.requests.get(2), anthropic.requests.get(3))) {
				JsonNode assistant = Claude.content(Claude.messages(resumed).get(1));
				assertThat(assistant).hasSize(2);
				assertThat(assistant.get(0).get("type").asString()).isEqualTo("server_tool_use");
				assertThat(assistant.get(0).get("input").toString()).isEqualTo("{\"query\":\"Spring AI release\"}");
				assertThat(assistant.get(1).get("type").asString()).isEqualTo("web_search_tool_result");
				assertThat(assistant.get(1).get("tool_use_id").asString()).isEqualTo("srvtoolu_1");
				assertThat(assistant.get(1).get("content").get(0).get("encrypted_content").asString()).isEqualTo("enc-1");
			}
		}
	}

}
