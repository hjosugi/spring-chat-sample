package io.github.hjosugi.chatsample.rag;

import java.util.List;

import io.github.hjosugi.chatsample.support.ScriptedChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.template.st.StTemplateRenderer;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;

import static io.github.hjosugi.chatsample.support.ScriptedChatModel.endsWithToolResult;
import static io.github.hjosugi.chatsample.support.ScriptedChatModel.lastText;
import static io.github.hjosugi.chatsample.support.ScriptedChatModel.text;
import static io.github.hjosugi.chatsample.support.ScriptedChatModel.toolCall;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code QuestionAnswerAdvisor} of the RAG page: it searches the vector store with
 * the user's question and adds what it finds to the user message, through a template.
 */
class QuestionAnswerAdvisorTests {

	private static final String QUESTION = "How evenly does the burr grinder grind?";

	private final BagOfWordsEmbeddingModel embeddings = new BagOfWordsEmbeddingModel();

	private final SimpleVectorStore vectorStore = CoffeeGear.store(this.embeddings);

	private final ScriptedChatModel chatModel = ScriptedChatModel.answering("Evenly.");

	@BeforeEach
	void forgetTheLoading() {
		this.embeddings.requests.clear();
	}

	@Test
	void theUserMessageCarriesTheDocumentsFoundForTheQuestion() {
		ChatResponse response = ChatClient.builder(this.chatModel)
			.build()
			.prompt()
			.advisors(QuestionAnswerAdvisor.builder(this.vectorStore).build())
			.user(QUESTION)
			.call()
			.chatResponse();

		assertThat(response.getResult().getOutput().getText()).isEqualTo("Evenly.");
		String sent = lastText(this.chatModel.lastPrompt());
		assertThat(sent).startsWith(QUESTION)
			.contains("Context information is below, surrounded by ---------------------",
					"---------------------\n" + CoffeeGear.GRINDER.getText(),
					"If the answer is not in the context, inform\nthe user that you can't answer the question.");
		// The search embedded the question, and nothing else.
		assertThat(this.embeddings.embeddedTexts()).containsExactly(QUESTION);
		// The default request accepts every similarity and keeps four: the grinder first.
		List<Document> documents = response.getMetadata().get(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS);
		assertThat(documents).hasSize(SearchRequest.DEFAULT_TOP_K)
			.first()
			.extracting(Document::getId)
			.isEqualTo("grinder");
	}

	@Test
	void theSearchRequestSetsTheThresholdAndHowManyDocumentsToKeep() {
		var qaAdvisor = QuestionAnswerAdvisor.builder(this.vectorStore)
			.searchRequest(SearchRequest.builder().similarityThreshold(0.8d).topK(6).build())
			.build();

		ChatResponse response = ChatClient.builder(this.chatModel)
			.build()
			.prompt()
			.advisors(qaAdvisor)
			.user("Does the burr grinder grind coffee beans evenly, from espresso fine to coarse?")
			.call()
			.chatResponse();

		List<Document> documents = response.getMetadata().get(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS);
		assertThat(documents).extracting(Document::getId).containsExactly("grinder");
		assertThat(documents.get(0).getScore()).isGreaterThanOrEqualTo(0.8d);
		assertThat(lastText(this.chatModel.lastPrompt())).contains(CoffeeGear.GRINDER.getText())
			.doesNotContain(CoffeeGear.BEANS.getText());
	}

	@Test
	void aFilterExpressionGivenWithTheRequestNarrowsTheSearch() {
		ChatClient chatClient = ChatClient.builder(this.chatModel)
			.defaultAdvisors(QuestionAnswerAdvisor.builder(this.vectorStore)
				.searchRequest(SearchRequest.builder().build())
				.build())
			.build();

		ChatResponse response = chatClient.prompt()
			.user(QUESTION)
			.advisors((a) -> a.param(QuestionAnswerAdvisor.FILTER_EXPRESSION, "type == 'kettle' || price < 10"))
			.call()
			.chatResponse();

		List<Document> documents = response.getMetadata().get(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS);
		assertThat(documents).extracting(Document::getId).containsExactlyInAnyOrder("kettle", "filters");
		assertThat(lastText(this.chatModel.lastPrompt())).doesNotContain(CoffeeGear.GRINDER.getText());
	}

	@Test
	void theFilterExpressionOfTheSearchRequestAppliesToEveryRequest() {
		ChatClient chatClient = ChatClient.builder(this.chatModel)
			.defaultAdvisors(QuestionAnswerAdvisor.builder(this.vectorStore)
				.searchRequest(SearchRequest.builder().filterExpression("type == 'beans'").build())
				.build())
			.build();

		ChatResponse response = chatClient.prompt(QUESTION).call().chatResponse();

		assertThat(response.getMetadata().<List<Document>>get(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS))
			.extracting(Document::getId)
			.containsExactly("beans");
	}

	@Test
	void aCustomTemplateWithOtherDelimitersMergesTheQuestionAndTheContext() {
		PromptTemplate customPromptTemplate = PromptTemplate.builder()
			.renderer(StTemplateRenderer.builder().startDelimiterToken('<').endDelimiterToken('>').build())
			.template("""
					<query>

					Context information is below.

					---------------------
					<question_answer_context>
					---------------------

					Given the context information and no prior knowledge, answer the query.
					""")
			.build();

		ChatClient.builder(this.chatModel)
			.build()
			.prompt(QUESTION)
			.advisors(QuestionAnswerAdvisor.builder(this.vectorStore)
				.promptTemplate(customPromptTemplate)
				.searchRequest(SearchRequest.builder().topK(1).build())
				.build())
			.call()
			.content();

		assertThat(lastText(this.chatModel.lastPrompt())).isEqualTo(QUESTION + "\n\nContext information is below.\n\n"
				+ "---------------------\n" + CoffeeGear.GRINDER.getText() + "\n---------------------\n\n"
				+ "Given the context information and no prior knowledge, answer the query.\n");
	}

	/**
	 * The page says the template must contain both placeholders. Nothing checks it: a
	 * template without {@code question_answer_context} is accepted, and the model gets
	 * the question without the documents found for it.
	 */
	@Test
	void aTemplateWithoutTheContextPlaceholderIsAcceptedAndSendsNoContext() {
		ChatResponse response = ChatClient.builder(this.chatModel)
			.build()
			.prompt(QUESTION)
			.advisors(QuestionAnswerAdvisor.builder(this.vectorStore)
				.promptTemplate(new PromptTemplate("Answer briefly: {query}"))
				.build())
			.call()
			.chatResponse();

		assertThat(lastText(this.chatModel.lastPrompt())).isEqualTo("Answer briefly: " + QUESTION);
		assertThat(response.getMetadata().<List<Document>>get(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS)).isNotEmpty();
	}

	@Test
	void theRetrievedDocumentsAreInTheResponseMetadataAndInTheAdvisorContext() {
		ChatClientResponse response = ChatClient.builder(this.chatModel)
			.build()
			.prompt(QUESTION)
			.advisors(QuestionAnswerAdvisor.builder(this.vectorStore).build())
			.call()
			.chatClientResponse();

		List<Document> inMetadata = response.chatResponse().getMetadata().get("qa_retrieved_documents");
		assertThat(inMetadata).isNotEmpty();
		assertThat(response.context()).containsEntry(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS, inMetadata);
	}

	@Test
	void whenStreamingOnlyTheResponseWithTheFinishReasonCarriesTheDocuments() {
		List<ChatResponse> chunks = ChatClient.builder(ScriptedChatModel.answering("Very evenly indeed."))
			.build()
			.prompt(QUESTION)
			.advisors(QuestionAnswerAdvisor.builder(this.vectorStore).build())
			.stream()
			.chatResponse()
			.collectList()
			.block();

		assertThat(chunks).hasSizeGreaterThan(1);
		ChatResponse last = chunks.get(chunks.size() - 1);
		assertThat(last.getResult().getMetadata().getFinishReason()).isEqualTo("stop");
		assertThat(last.getMetadata().<List<Document>>get(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS)).isNotEmpty();
		assertThat(chunks.subList(0, chunks.size() - 1)).allSatisfy(
				(chunk) -> assertThat(chunk.getMetadata().containsKey(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS))
					.isFalse());
	}

	@Test
	void byDefaultTheAdvisorRunsInsideTheToolLoopAndSearchesAgainInEveryRound() {
		ScriptedChatModel model = toolThenAnswer();
		var qaAdvisor = QuestionAnswerAdvisor.builder(this.vectorStore).build();
		assertThat(qaAdvisor.getOrder()).isZero().isGreaterThan(ToolCallingAdvisor.DEFAULT_ORDER);

		ChatClient.builder(model)
			.build()
			.prompt(QUESTION)
			.tools(new CoffeeGear.Stock())
			.advisors(qaAdvisor)
			.call()
			.content();

		assertThat(this.embeddings.embeddedTexts()).containsExactly(QUESTION, QUESTION);
		assertThat(model.prompts).hasSize(2)
			.allSatisfy((prompt) -> assertThat(userText(prompt)).startsWith(QUESTION)
				.contains(CoffeeGear.GRINDER.getText()));
	}

	@Test
	void anOrderBeforeTheToolLoopSearchesOncePerCall() {
		ScriptedChatModel model = toolThenAnswer();
		var qaAdvisor = QuestionAnswerAdvisor.builder(this.vectorStore)
			.order(ToolCallingAdvisor.DEFAULT_ORDER - 50)
			.build();

		ChatClient.builder(model)
			.build()
			.prompt(QUESTION)
			.tools(new CoffeeGear.Stock())
			.advisors(qaAdvisor)
			.call()
			.content();

		assertThat(this.embeddings.embeddedTexts()).containsExactly(QUESTION);
		// The loop sends the user message as the advisor left it, augmented.
		assertThat(model.prompts).hasSize(2)
			.allSatisfy((prompt) -> assertThat(userText(prompt)).contains(CoffeeGear.GRINDER.getText()));
	}

	private static ScriptedChatModel toolThenAnswer() {
		return new ScriptedChatModel((prompt) -> endsWithToolResult(prompt) ? text("In stock: " + lastText(prompt))
				: toolCall("stock", "{\"item\":\"grinder\"}"));
	}

	private static String userText(Prompt prompt) {
		return prompt.getInstructions()
			.stream()
			.filter((message) -> message.getMessageType() == MessageType.USER)
			.map((message) -> ((UserMessage) message).getText())
			.reduce((first, second) -> second)
			.orElseThrow();
	}

}
