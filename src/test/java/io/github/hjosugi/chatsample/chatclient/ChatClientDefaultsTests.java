package io.github.hjosugi.chatsample.chatclient;

import java.util.List;
import java.util.Map;

import io.github.hjosugi.chatsample.support.ScriptedChatModel;
import io.github.hjosugi.chatsample.support.StandIn;
import io.github.hjosugi.chatsample.support.StandIn.Reply;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.template.st.StTemplateRenderer;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.core.io.ByteArrayResource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The defaults of a {@code ChatClient} and how the values of a request combine with
 * them, as the page "Defaults and Mutation" shows it, and the copies that
 * {@code mutate()} and {@code clone()} make.
 */
class ChatClientDefaultsTests {

	@Test
	void theDefaultSystemTextGoesWithEveryRequest() {
		ScriptedChatModel model = ScriptedChatModel.answering("Arrr");
		ChatClient client = ChatClient.builder(model)
			.defaultSystem("You are a friendly chat bot that answers question in the voice of a Pirate")
			.build();

		client.prompt().user("Tell me a joke").call().content();
		client.prompt().user("Tell me another").call().content();

		assertThat(model.prompts).allSatisfy((prompt) -> assertThat(prompt.getSystemMessage().getText())
			.isEqualTo("You are a friendly chat bot that answers question in the voice of a Pirate"));
	}

	@Test
	void theRequestFillsInTheParamsOfTheDefaultSystemText() {
		ScriptedChatModel model = ScriptedChatModel.answering("You talkin' to me?");
		ChatClient client = ChatClient.builder(model)
			.defaultSystem("You are a friendly chat bot that answers question in the voice of a {voice}")
			.build();

		client.prompt().system((sp) -> sp.param("voice", "Robert DeNiro")).user("Tell me a joke").call().content();

		assertThat(model.lastPrompt().getSystemMessage().getText())
			.isEqualTo("You are a friendly chat bot that answers question in the voice of a Robert DeNiro");
	}

	@Test
	void theDefaultSystemAndUserTextComeFromResources() {
		ScriptedChatModel model = ScriptedChatModel.answering("ok");
		ChatClient client = ChatClient.builder(model)
			.defaultSystem(new ByteArrayResource("Be brief".getBytes()))
			.defaultUser(new ByteArrayResource("What is new?".getBytes()))
			.build();

		client.prompt().call().content();

		assertThat(model.lastPrompt().getSystemMessage().getText()).isEqualTo("Be brief");
		assertThat(model.lastPrompt().getUserMessage().getText()).isEqualTo("What is new?");
	}

	@Test
	void aRequestTextReplacesTheDefaultAndAddsItsParamsAndMetadata() {
		ScriptedChatModel model = ScriptedChatModel.answering("ok");
		ChatClient client = ChatClient.builder(model)
			.defaultSystem((s) -> s.text("Answer as {role} in {language}")
				.param("role", "a clerk")
				.param("language", "English")
				.metadata("version", "1"))
			.defaultUser((u) -> u.text("Default question").metadata("sessionId", "s-1"))
			.build();

		client.prompt()
			.system((s) -> s.param("language", "French").metadata("tone", "kind"))
			.user((u) -> u.text("Where is {order}?").param("order", "A-1001").metadata("messageId", "m-1"))
			.call()
			.content();

		assertThat(model.lastPrompt().getSystemMessage().getText()).isEqualTo("Answer as a clerk in French");
		assertThat(model.lastPrompt().getSystemMessage().getMetadata()).containsEntry("version", "1")
			.containsEntry("tone", "kind");
		UserMessage user = model.lastPrompt().getUserMessage();
		assertThat(user.getText()).isEqualTo("Where is A-1001?");
		assertThat(user.getMetadata()).containsEntry("sessionId", "s-1").containsEntry("messageId", "m-1");
	}

	/**
	 * {@code prompt(String)} is no {@code user(...)}: its text becomes a message of the
	 * prompt, and the default user text follows it as a second user message.
	 */
	@Test
	void promptWithAStringKeepsTheDefaultUserTextAsASecondUserMessage() {
		ScriptedChatModel model = ScriptedChatModel.answering("ok");
		ChatClient client = ChatClient.builder(model).defaultUser("Default question").build();

		client.prompt("My question").call().content();

		assertThat(model.lastPrompt().getUserMessages()).extracting(UserMessage::getText)
			.containsExactly("My question", "Default question");
	}

	@Test
	void requestToolsAreAddedToTheDefaultTools() {
		ScriptedChatModel model = ScriptedChatModel.answering("ok");
		ChatClient client = ChatClient.builder(model).defaultTools(new Clock()).build();

		client.prompt("Hi").tools(new Weather()).call().content();

		ToolCallingChatOptions options = (ToolCallingChatOptions) model.lastPrompt().getOptions();
		assertThat(options.getToolCallbacks()).extracting((tool) -> tool.getToolDefinition().name())
			.containsExactlyInAnyOrder("time", "weather");
	}

	@Test
	void requestToolContextIsAddedToTheDefaultAndWinsForTheSameKey() {
		ToolContextCapture capture = new ToolContextCapture();
		ScriptedChatModel model = new ScriptedChatModel((prompt) -> ScriptedChatModel.endsWithToolResult(prompt)
				? ScriptedChatModel.text("done") : ScriptedChatModel.toolCall("capture", "{}"));
		ChatClient client = ChatClient.builder(model)
			.defaultTools(capture)
			.defaultToolContext(Map.of("tenant", "shop-1", "user", "default"))
			.build();

		client.prompt("Hi").toolContext(Map.of("user", "alice")).call().content();

		assertThat(capture.context).containsEntry("tenant", "shop-1").containsEntry("user", "alice");
	}

	@Test
	void requestAdvisorsAreAddedToTheDefaultAdvisors() {
		ScriptedChatModel model = ScriptedChatModel.answering("ok");
		ChatClient client = ChatClient.builder(model).defaultAdvisors(new Tag("default", 10)).build();

		client.prompt("Hi").advisors(new Tag("request", 20)).call().content();

		assertThat(ScriptedChatModel.lastText(model.lastPrompt())).isEqualTo("Hi [default] [request]");
	}

	@Test
	void theDefaultTemplateRendererRendersTheRequestTemplates() {
		ScriptedChatModel model = ScriptedChatModel.answering("ok");
		ChatClient client = ChatClient.builder(model)
			.defaultTemplateRenderer(StTemplateRenderer.builder().startDelimiterToken('<').endDelimiterToken('>').build())
			.build();

		client.prompt().user((u) -> u.text("{\"order\": \"<order>\"}").param("order", "A-1001")).call().content();

		assertThat(ScriptedChatModel.lastText(model.lastPrompt())).isEqualTo("{\"order\": \"A-1001\"}");
	}

	@Test
	void theDefaultOptionsCombineOverTheModelsOwn() {
		ScriptedChatModel model = ScriptedChatModel.answering("ok");
		ChatClient client = ChatClient.builder(model).defaultOptions(ChatOptions.builder().model("big").maxTokens(100)).build();

		client.prompt("Hi").call().content();

		assertThat(model.lastPrompt().getOptions().getModel()).isEqualTo("big");
		assertThat(model.lastPrompt().getOptions().getMaxTokens()).isEqualTo(100);
		assertThat(model.lastPrompt().getOptions()).isInstanceOf(ToolCallingChatOptions.class);
	}

	@Test
	void requestOptionsReplaceTheDefaultOptions() {
		ScriptedChatModel model = ScriptedChatModel.answering("ok");
		ChatClient client = ChatClient.builder(model).defaultOptions(ChatOptions.builder().model("big").maxTokens(100)).build();

		client.prompt("Hi").options(ChatOptions.builder().temperature(0.2)).call().content();

		ChatOptions options = model.lastPrompt().getOptions();
		assertThat(options.getTemperature()).isEqualTo(0.2);
		assertThat(options.getModel()).isNull();
		assertThat(options.getMaxTokens()).isNull();
	}

	/**
	 * The options of a {@code Prompt} given to {@code prompt(Prompt)} are not replaced
	 * like those of {@code options(...)}: they are combined over the client's default
	 * options.
	 */
	@Test
	void theOptionsOfAPromptCombineOverTheDefaultOptionsUnlikeRequestOptions() {
		ScriptedChatModel model = ScriptedChatModel.answering("ok");
		ChatClient client = ChatClient.builder(model).defaultOptions(ChatOptions.builder().model("big").maxTokens(100)).build();

		client.prompt(new Prompt("Hi", ChatOptions.builder().temperature(0.2).build())).call().content();
		ChatOptions fromPrompt = model.lastPrompt().getOptions();
		client.prompt("Hi").options(ChatOptions.builder().temperature(0.2)).call().content();
		ChatOptions fromRequest = model.lastPrompt().getOptions();

		assertThat(fromPrompt.getTemperature()).isEqualTo(0.2);
		assertThat(fromPrompt.getModel()).isEqualTo("big");
		assertThat(fromPrompt.getMaxTokens()).isEqualTo(100);
		assertThat(fromRequest.getTemperature()).isEqualTo(0.2);
		assertThat(fromRequest.getModel()).isNull();
	}

	/**
	 * The page's example with OpenAI: the client default asks for {@code gpt-5.4}, the
	 * request options replace it, and the model of the model's own options comes back.
	 */
	@Test
	void requestOptionsGoOverTheModelsOptionsAtOpenAi() {
		try (StandIn openAi = new StandIn((request) -> Reply.json(Providers.openAiText("A summary")))) {
			ChatClient client = ChatClient.builder(Providers.openAi(openAi))
				.defaultOptions(ChatOptions.builder().model("gpt-5.4"))
				.build();

			client.prompt("Summarize this contract").call().content();
			client.prompt("Summarize this contract")
				.options(OpenAiChatOptions.builder().reasoningEffort("low"))
				.call()
				.content();

			JsonNode first = openAi.requests.get(0).body();
			JsonNode second = openAi.requests.get(1).body();
			assertThat(first.get("model").asString()).isEqualTo("gpt-5.4");
			assertThat(first.has("reasoning_effort")).isFalse();
			assertThat(second.get("model").asString()).isEqualTo(Providers.OPENAI_MODEL);
			assertThat(second.get("reasoning_effort").asString()).isEqualTo("low");
		}
	}

	/**
	 * The stop sequences of the request are not set over those of the model, as the other
	 * options are, but added to them.
	 */
	@Test
	void requestStopSequencesAreAddedToThoseOfTheModel() {
		try (StandIn openAi = new StandIn((request) -> Reply.json(Providers.openAiText("ok")))) {
			ChatClient client = ChatClient.create(Providers.openAi(openAi, ObservationRegistry.NOOP,
					(options) -> options.stopSequences(List.of("END"))));

			client.prompt("Hi").options(ChatOptions.builder().stopSequences(List.of("STOP"))).call().content();

			assertThat(openAi.single().body().get("stop").toString()).isEqualTo("[\"END\",\"STOP\"]");
		}
	}

	@Test
	void mutateOnAClientStartsABuilderWithItsDefaults() {
		ScriptedChatModel model = ScriptedChatModel.answering("ok");
		ChatClient pirate = ChatClient.builder(model).defaultSystem("Talk like a pirate").build();

		ChatClient briefPirate = pirate.mutate().defaultAdvisors(new Tag("brief", 0)).build();
		briefPirate.prompt("Hi").call().content();
		pirate.prompt("Hi").call().content();

		assertThat(model.prompts.get(0).getSystemMessage().getText()).isEqualTo("Talk like a pirate");
		assertThat(ScriptedChatModel.lastText(model.prompts.get(0))).isEqualTo("Hi [brief]");
		assertThat(ScriptedChatModel.lastText(model.prompts.get(1))).isEqualTo("Hi");
	}

	@Test
	void mutateOnARequestStartsABuilderWithTheRequestsSettings() {
		ScriptedChatModel model = ScriptedChatModel.answering("ok");
		ChatClient.ChatClientRequestSpec request = ChatClient.create(model)
			.prompt()
			.system("Answer in {language}")
			.system((s) -> s.param("language", "French"))
			.messages(new UserMessage("Earlier question"))
			.options(ChatOptions.builder().maxTokens(50));

		ChatClient derived = request.mutate().build();
		derived.prompt().user("New question").call().content();

		assertThat(model.lastPrompt().getInstructions()).extracting((message) -> message.getMessageType())
			.containsExactly(MessageType.SYSTEM, MessageType.USER, MessageType.USER);
		assertThat(model.lastPrompt().getSystemMessage().getText()).isEqualTo("Answer in French");
		assertThat(model.lastPrompt().getOptions().getMaxTokens()).isEqualTo(50);
	}

	@Test
	void changesToACloneDoNotReachTheOriginalBuilder() {
		ScriptedChatModel model = ScriptedChatModel.answering("ok");
		ChatClient.Builder original = ChatClient.builder(model).defaultSystem("Original");

		ChatClient.Builder copy = original.clone().defaultSystem("Copy").defaultAdvisors(new Tag("copy", 0));
		original.build().prompt("Hi").call().content();
		copy.build().prompt("Hi").call().content();

		assertThat(model.prompts.get(0).getSystemMessage().getText()).isEqualTo("Original");
		assertThat(ScriptedChatModel.lastText(model.prompts.get(0))).isEqualTo("Hi");
		assertThat(model.prompts.get(1).getSystemMessage().getText()).isEqualTo("Copy");
		assertThat(ScriptedChatModel.lastText(model.prompts.get(1))).isEqualTo("Hi [copy]");
	}

	static class Clock {

		@Tool(description = "Tells the time")
		String time() {
			return "noon";
		}

	}

	static class Weather {

		@Tool(description = "Tells the weather")
		String weather() {
			return "sunny";
		}

	}

	static class ToolContextCapture {

		volatile Map<String, Object> context;

		@Tool(description = "Captures the tool context")
		String capture(ToolContext toolContext) {
			this.context = toolContext.getContext();
			return "captured";
		}

	}

	/**
	 * Appends its name to the user text, so that a test sees which advisors ran.
	 */
	record Tag(String name, int order) implements BaseAdvisor {

		@Override
		public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
			return request.mutate()
				.prompt(request.prompt()
					.augmentUserMessage(request.prompt().getUserMessage().getText() + " [" + this.name + "]"))
				.build();
		}

		@Override
		public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
			return response;
		}

		@Override
		public int getOrder() {
			return this.order;
		}

	}

}
