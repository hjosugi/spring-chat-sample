package io.github.hjosugi.chatsample.chatclient;

import java.util.List;
import java.util.Map;

import ch.qos.logback.classic.Level;
import io.github.hjosugi.chatsample.support.ScriptedChatModel;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.part.MediaPart;
import org.springframework.ai.chat.messages.part.ReasoningPart;
import org.springframework.ai.chat.messages.part.TextPart;
import org.springframework.ai.chat.messages.part.ToolCallPart;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.ai.content.Media;
import org.springframework.ai.content.MediaContent;
import org.springframework.ai.template.NoOpTemplateRenderer;
import org.springframework.ai.template.TemplateRenderer;
import org.springframework.ai.template.ValidationMode;
import org.springframework.ai.template.st.StTemplateRenderer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.MimeTypeUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * Prompts, messages and prompt templates, as the page "Prompts" shows them: the
 * convenience methods of a {@code Prompt}, the roles, the parts of a message and how the
 * builders replace them, and {@code PromptTemplate} with its renderers.
 */
class PromptTests {

	private static final ToolResponseMessage TOOL_RESULT = ToolResponseMessage.builder()
		.responses(List.of(new ToolResponseMessage.ToolResponse("call_1", "time", "noon")))
		.build();

	private final Prompt conversation = new Prompt(List.of(new SystemMessage("First system"),
			new UserMessage("First question"), new AssistantMessage("First answer"), new SystemMessage("Second system"),
			new UserMessage("Second question"), TOOL_RESULT));

	@Test
	void thePromptFindsTheLastUserMessageAndTheFirstSystemMessage() {
		assertThat(this.conversation.getUserMessage().getText()).isEqualTo("Second question");
		assertThat(this.conversation.getSystemMessage().getText()).isEqualTo("First system");
		assertThat(this.conversation.getLastUserOrToolResponseMessage()).isSameAs(TOOL_RESULT);
	}

	@Test
	void thePromptListsTheMessagesOfARoleInOrder() {
		assertThat(this.conversation.getUserMessages()).extracting(UserMessage::getText)
			.containsExactly("First question", "Second question");
		assertThat(this.conversation.getSystemMessages()).extracting(SystemMessage::getText)
			.containsExactly("First system", "Second system");
	}

	@Test
	void withoutSuchAMessageThePromptGivesAnEmptyOne() {
		Prompt prompt = new Prompt(List.of(new AssistantMessage("Hello")));

		assertThat(prompt.getUserMessage().getText()).isEmpty();
		assertThat(prompt.getSystemMessage().getText()).isEmpty();
		assertThat(prompt.getLastUserOrToolResponseMessage()).isInstanceOf(UserMessage.class);
		assertThat(prompt.getUserMessages()).isEmpty();
	}

	@Test
	void eachMessageHasTheTypeOfItsRole() {
		assertThat(this.conversation.getInstructions()).extracting(Message::getMessageType)
			.containsExactly(MessageType.SYSTEM, MessageType.USER, MessageType.ASSISTANT, MessageType.SYSTEM,
					MessageType.USER, MessageType.TOOL);
		assertThat(MessageType.values()).extracting(MessageType::getValue)
			.containsExactly("user", "assistant", "system", "tool");
		assertThat(new UserMessage("x")).isInstanceOf(MediaContent.class);
		assertThat(new AssistantMessage("x")).isInstanceOf(MediaContent.class);
	}

	@Test
	void textAndMediaInterleaveInTheOrderOfTheParts() {
		UserMessage message = UserMessage.builder()
			.part(TextPart.of("What is in this picture?"))
			.part(MediaPart.of(png("picture")))
			.part(TextPart.of("Answer in one sentence."))
			.build();

		assertThat(message.getParts()).extracting((part) -> part.getClass().getSimpleName())
			.containsExactly("TextPart", "MediaPart", "TextPart");
		assertThat(message.getText()).isEqualTo("What is in this picture?Answer in one sentence.");
		assertThat(message.getMedia()).extracting(Media::getName).containsExactly("picture");
	}

	@Test
	void rewritingTheTextOfAUserMessageKeepsItsMedia() {
		UserMessage message = UserMessage.builder()
			.part(TextPart.of("What is in this picture?"))
			.part(MediaPart.of(png("picture")))
			.build();

		UserMessage rewritten = message.mutate().text("Describe the picture.").build();

		assertThat(rewritten.getText()).isEqualTo("Describe the picture.");
		assertThat(rewritten.getMedia()).extracting(Media::getName).containsExactly("picture");
		assertThat(rewritten.getParts()).extracting((part) -> part.getClass().getSimpleName())
			.containsExactly("TextPart", "MediaPart");
	}

	@Test
	void withoutPartsTheBuilderPutsTheTextBeforeTheMedia() {
		UserMessage message = UserMessage.builder().media(png("picture")).text("What is this?").build();

		assertThat(message.getParts()).extracting((part) -> part.getClass().getSimpleName())
			.containsExactly("TextPart", "MediaPart");
	}

	@Test
	void rewritingTheContentOfAnAnswerKeepsTheReasoningAndTheToolCallsInPlace() {
		AssistantMessage answer = AssistantMessage.builder()
			.part(ReasoningPart.of("Think"))
			.part(TextPart.of("Let me look."))
			.part(ToolCallPart.of(new AssistantMessage.ToolCall("call_1", "function", "time", "{}")))
			.build();

		AssistantMessage rewritten = answer.mutate().content("One moment.").build();
		AssistantMessage withoutCalls = answer.mutate().toolCalls(List.of()).build();

		assertThat(rewritten.getParts()).extracting((part) -> part.getClass().getSimpleName())
			.containsExactly("ReasoningPart", "TextPart", "ToolCallPart");
		assertThat(rewritten.getText()).isEqualTo("One moment.");
		assertThat(withoutCalls.hasToolCalls()).isFalse();
		assertThat(withoutCalls.getParts()).extracting((part) -> part.getClass().getSimpleName())
			.containsExactly("ReasoningPart", "TextPart");
	}

	@Test
	void aSetterTakesEffectWhenTheMessageIsBuilt() {
		// The text is set first; the text part added after it is replaced all the same.
		UserMessage message = UserMessage.builder()
			.text("From the setter")
			.part(MediaPart.of(png("picture")))
			.part(TextPart.of("Added later"))
			.build();

		assertThat(message.getText()).isEqualTo("From the setter");
		assertThat(message.getParts()).hasSize(2);
	}

	@Test
	void aSetterWithoutAPartOfItsTypePutsItsPartsWhereTheLegacyOrderHasThem() {
		AssistantMessage answer = AssistantMessage.builder()
			.part(MediaPart.of(png("chart")))
			.toolCalls(List.of(new AssistantMessage.ToolCall("call_1", "function", "time", "{}")))
			.content("Here")
			.build();

		assertThat(answer.getParts()).extracting((part) -> part.getClass().getSimpleName())
			.containsExactly("TextPart", "ToolCallPart", "MediaPart");
	}

	@Test
	void responsesReplaceTheResultsOfAToolResponseMessage() {
		ToolResponseMessage replaced = TOOL_RESULT.mutate()
			.responses(List.of(new ToolResponseMessage.ToolResponse("call_2", "date", "Sunday")))
			.build();

		assertThat(replaced.getResponses()).extracting(ToolResponseMessage.ToolResponse::name).containsExactly("date");
	}

	@Test
	void theMediaListIsAnUnmodifiableView() {
		UserMessage message = UserMessage.builder().text("What is this?").media(png("picture")).build();

		assertThatExceptionOfType(UnsupportedOperationException.class)
			.isThrownBy(() -> message.getMedia().add(png("other")));
	}

	@Test
	void aTemplateWithOtherDelimitersRendersItsVariables() {
		PromptTemplate promptTemplate = PromptTemplate.builder()
			.renderer(StTemplateRenderer.builder().startDelimiterToken('<').endDelimiterToken('>').build())
			.template("""
					Tell me the names of 5 movies whose soundtrack was composed by <composer>.
					""")
			.build();

		String prompt = promptTemplate.render(Map.of("composer", "John Williams"));

		assertThat(prompt).isEqualTo("Tell me the names of 5 movies whose soundtrack was composed by John Williams.\n");
	}

	@Test
	void aTemplateRendersToAStringAMessageOrAPrompt() {
		PromptTemplate template = PromptTemplate.builder()
			.template("Tell me a {adjective} joke about {topic}")
			.variables(Map.of("adjective", "dry"))
			.build();
		ChatOptions options = ChatOptions.builder().maxTokens(50).build();

		assertThat(template.render(Map.of("topic", "cows"))).isEqualTo("Tell me a dry joke about cows");
		assertThat(template.createMessage(Map.of("topic", "cows"))).isEqualTo(new UserMessage("Tell me a dry joke about cows"));
		assertThat(template.create(Map.of("topic", "cows")).getInstructions())
			.containsExactly(new UserMessage("Tell me a dry joke about cows"));
		assertThat(template.create(Map.of("topic", "cows"), options).getOptions()).isSameAs(options);
	}

	@Test
	void aTemplateWithoutVariablesRendersAsItIs() {
		PromptTemplate template = new PromptTemplate("Tell me a joke");
		ChatOptions options = ChatOptions.builder().maxTokens(50).build();

		assertThat(template.render()).isEqualTo("Tell me a joke");
		assertThat(template.createMessage()).isEqualTo(new UserMessage("Tell me a joke"));
		assertThat(((UserMessage) template.createMessage(List.of(png("picture")))).getMedia()).hasSize(1);
		assertThat(template.create().getInstructions()).containsExactly(new UserMessage("Tell me a joke"));
		assertThat(template.create(options).getOptions()).isSameAs(options);
	}

	@Test
	void theJokeExampleAsksTheModel() {
		ScriptedChatModel chatModel = ScriptedChatModel.answering("Moo");
		PromptTemplate promptTemplate = new PromptTemplate("Tell me a {adjective} joke about {topic}");

		Prompt prompt = promptTemplate.create(Map.of("adjective", "dry", "topic", "cows"));
		Generation result = chatModel.call(prompt).getResult();

		assertThat(result.getOutput().getText()).isEqualTo("Moo");
		assertThat(ScriptedChatModel.lastText(chatModel.lastPrompt())).isEqualTo("Tell me a dry joke about cows");
	}

	@Test
	void theRolesExampleCombinesAUserAndASystemMessage() {
		ScriptedChatModel chatModel = ScriptedChatModel.answering("Arrr");
		Message userMessage = new UserMessage("Tell me about three famous pirates.");
		SystemPromptTemplate systemPromptTemplate = new SystemPromptTemplate("""
				You are a helpful AI assistant that helps people find information.
				Your name is {name}
				You should reply to the user's request with your name and also in the style of a {voice}.
				""");
		Message systemMessage = systemPromptTemplate.createMessage(Map.of("name", "Bob", "voice", "pirate"));

		List<Generation> response = chatModel.call(new Prompt(List.of(userMessage, systemMessage))).getResults();

		assertThat(response).hasSize(1);
		assertThat(systemMessage).isInstanceOf(SystemMessage.class);
		assertThat(chatModel.lastPrompt().getSystemMessage().getText()).contains("Your name is Bob",
				"in the style of a pirate.");
		// The prompt keeps the messages in the order they were given.
		assertThat(chatModel.lastPrompt().getInstructions()).extracting(Message::getMessageType)
			.containsExactly(MessageType.USER, MessageType.SYSTEM);
	}

	@Test
	void aTemplateRendererOfYourOwnRendersTheTemplate() {
		TemplateRenderer upperCase = (template, variables) -> template.replace("$name", (String) variables.get("name"))
			.toUpperCase();

		String rendered = PromptTemplate.builder()
			.renderer(upperCase)
			.template("Hello $name")
			.variables(Map.of("name", "Alice"))
			.build()
			.render();

		assertThat(rendered).isEqualTo("HELLO ALICE");
	}

	@Test
	void theNoOpRendererReturnsTheTemplateUnchanged() {
		String rendered = PromptTemplate.builder()
			.renderer(new NoOpTemplateRenderer())
			.template("Hello {name}")
			.variables(Map.of("name", "Alice"))
			.build()
			.render();

		assertThat(rendered).isEqualTo("Hello {name}");
	}

	@Test
	void aMissingVariableThrowsByDefault() {
		PromptTemplate template = new PromptTemplate("Tell me a {adjective} joke about {topic}");

		assertThatIllegalStateException().isThrownBy(() -> template.render(Map.of("adjective", "dry")))
			.withMessageContaining("topic");
	}

	@Test
	void aMissingVariableOnlyWarnsInWarnMode() {
		PromptTemplate promptTemplate = PromptTemplate.builder()
			.renderer(StTemplateRenderer.builder().validationMode(ValidationMode.WARN).build())
			.template("Tell me a {adjective} joke about {topic}")
			.build();
		try (LogCapture log = new LogCapture(StTemplateRenderer.class, Level.WARN)) {
			String rendered = promptTemplate.render(Map.of("adjective", "dry"));

			assertThat(rendered).isEqualTo("Tell me a dry joke about ");
			assertThat(log.messages()).anySatisfy((message) -> assertThat(message).contains("topic"));
		}
	}

	@Test
	void aMissingVariableIsNotCheckedInNoneMode() {
		PromptTemplate promptTemplate = PromptTemplate.builder()
			.renderer(StTemplateRenderer.builder().validationMode(ValidationMode.NONE).build())
			.template("Tell me a {adjective} joke about {topic}")
			.build();
		try (LogCapture log = new LogCapture(StTemplateRenderer.class, Level.WARN)) {
			assertThat(promptTemplate.render(Map.of("adjective", "dry"))).isEqualTo("Tell me a dry joke about ");
			assertThat(log.messages()).noneSatisfy((message) -> assertThat(message).contains("Missing variable"));
		}
	}

	/**
	 * The page says that names of StringTemplate functions count as variables in the
	 * check, and that {@code validateStFunctions()} excludes them. It is the other way
	 * round: by default they are left out of the check, and {@code validateStFunctions()}
	 * puts them in.
	 */
	@Test
	void functionNamesAreLeftOutOfTheCheckUnlessValidateStFunctionsPutsThemIn() {
		Map<String, Object> variables = Map.of("topic", "cows");

		String byDefault = PromptTemplate.builder()
			.template("{first} joke about {topic}")
			.variables(variables)
			.build()
			.render();

		assertThat(byDefault).endsWith("joke about cows");
		assertThatIllegalStateException()
			.isThrownBy(() -> PromptTemplate.builder()
				.renderer(StTemplateRenderer.builder().validateStFunctions().build())
				.template("{first} joke about {topic}")
				.variables(variables)
				.build()
				.render())
			.withMessageContaining("first");
	}

	@Test
	void aSystemPromptTemplateReadsItsTemplateFromAResource() {
		SystemPromptTemplate systemPromptTemplate = new SystemPromptTemplate(
				new ClassPathResource("chatclient/prompts/system-message.st"));

		Message message = systemPromptTemplate.createMessage(Map.of("name", "Bob", "voice", "pirate"));

		assertThat(message).isInstanceOf(SystemMessage.class);
		assertThat(message.getText()).isEqualTo("You are Bob, and you talk like a pirate.\n");
	}

	static Media png(String name) {
		return Media.builder().mimeType(MimeTypeUtils.IMAGE_PNG).data(new byte[] { 1, 2, 3 }).name(name).build();
	}

}
