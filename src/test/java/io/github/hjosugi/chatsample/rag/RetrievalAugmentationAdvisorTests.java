package io.github.hjosugi.chatsample.rag;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.hjosugi.chatsample.support.ScriptedChatModel;
import io.micrometer.context.ContextRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.ai.rag.retrieval.join.ConcatenationDocumentJoiner;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

import static io.github.hjosugi.chatsample.support.ScriptedChatModel.endsWithToolResult;
import static io.github.hjosugi.chatsample.support.ScriptedChatModel.lastText;
import static io.github.hjosugi.chatsample.support.ScriptedChatModel.text;
import static io.github.hjosugi.chatsample.support.ScriptedChatModel.toolCall;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * The {@code RetrievalAugmentationAdvisor} of the RAG page: the naive flow, the empty
 * context, filters per request, the advanced flows with a query transformer, a query
 * expander, a joiner and post-processors, and where it runs relative to the tool loop.
 */
class RetrievalAugmentationAdvisorTests {

	private static final String QUESTION = "How evenly does the burr grinder grind?";

	private static final String NOTHING_KNOWN = "What time does the shop open on Sundays?";

	private final BagOfWordsEmbeddingModel embeddings = new BagOfWordsEmbeddingModel();

	private final SimpleVectorStore vectorStore = CoffeeGear.store(this.embeddings);

	private final ScriptedChatModel chatModel = ScriptedChatModel.answering("Evenly.");

	private final ChatClient chatClient = ChatClient.builder(this.chatModel).build();

	@BeforeEach
	void forgetTheLoading() {
		this.embeddings.requests.clear();
	}

	@Test
	void naiveRagPutsTheDocumentsAboveTheThresholdAndTheQueryIntoTheUserMessage() {
		Advisor retrievalAugmentationAdvisor = RetrievalAugmentationAdvisor.builder()
			.documentRetriever(VectorStoreDocumentRetriever.builder()
				.similarityThreshold(0.50)
				.vectorStore(this.vectorStore)
				.build())
			.build();

		String answer = this.chatClient.prompt().advisors(retrievalAugmentationAdvisor).user(QUESTION).call().content();

		assertThat(answer).isEqualTo("Evenly.");
		assertThat(lastText(this.chatModel.lastPrompt())).isEqualTo("""
				Context information is below.

				---------------------
				%s
				---------------------

				Given the context information and no prior knowledge, answer the query.

				Follow these rules:

				1. If the answer is not in the context, just say that you don't know.
				2. Avoid statements like "Based on the context..." or "The provided information...".

				Query: %s

				Answer:
				""".formatted(CoffeeGear.GRINDER.getText(), QUESTION));
	}

	@Test
	void byDefaultAnEmptyContextReplacesTheQuestionWithAnInstructionNotToAnswer() {
		Advisor retrievalAugmentationAdvisor = RetrievalAugmentationAdvisor.builder()
			.documentRetriever(VectorStoreDocumentRetriever.builder()
				.similarityThreshold(0.50)
				.vectorStore(this.vectorStore)
				.build())
			.build();

		this.chatClient.prompt().advisors(retrievalAugmentationAdvisor).user(NOTHING_KNOWN).call().content();

		// The model never sees the question.
		assertThat(lastText(this.chatModel.lastPrompt())).isEqualTo("""
				The user query is outside your knowledge base.
				Politely inform the user that you can't answer it.
				""");
	}

	@Test
	void anAugmenterThatAllowsAnEmptyContextLeavesTheQuestionAsItIs() {
		Advisor retrievalAugmentationAdvisor = RetrievalAugmentationAdvisor.builder()
			.documentRetriever(VectorStoreDocumentRetriever.builder()
				.similarityThreshold(0.50)
				.vectorStore(this.vectorStore)
				.build())
			.queryAugmenter(ContextualQueryAugmenter.builder().allowEmptyContext(true).build())
			.build();

		this.chatClient.prompt().advisors(retrievalAugmentationAdvisor).user(NOTHING_KNOWN).call().content();

		assertThat(lastText(this.chatModel.lastPrompt())).isEqualTo(NOTHING_KNOWN);
	}

	@Test
	void aFilterExpressionGivenWithTheRequestNarrowsTheSearch() {
		SimpleVectorStore editions = SimpleVectorStore.builder(this.embeddings).build();
		editions.add(List.of(new Document("kettle-2025", CoffeeGear.KETTLE.getText(), Map.of("type", "Classic")),
				new Document("kettle-2026", CoffeeGear.KETTLE.getText(), Map.of("type", "Spring"))));
		Advisor retrievalAugmentationAdvisor = RetrievalAugmentationAdvisor.builder()
			.documentRetriever(
					VectorStoreDocumentRetriever.builder().similarityThreshold(0.50).vectorStore(editions).build())
			.build();

		ChatResponse response = this.chatClient.prompt()
			.advisors(retrievalAugmentationAdvisor)
			.advisors((a) -> a.param(VectorStoreDocumentRetriever.FILTER_EXPRESSION, "type == 'Spring'"))
			.user("How does the gooseneck kettle heat water?")
			.call()
			.chatResponse();

		assertThat(response.getMetadata().<List<Document>>get(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT))
			.extracting(Document::getId)
			.containsExactly("kettle-2026");
	}

	@Test
	void advancedRagSearchesWithTheRewrittenQueryAndAnswersTheOriginalOne() {
		String verbose = "I'm new to coffee and a friend said a lot of things. " + QUESTION;
		ScriptedChatModel model = new ScriptedChatModel((prompt) -> lastText(prompt).contains("Rewritten query:")
				? text("burr grinder grind evenly") : text("Evenly."));
		ChatClient.Builder chatClientBuilder = ChatClient.builder(model);
		Advisor retrievalAugmentationAdvisor = RetrievalAugmentationAdvisor.builder()
			.queryTransformers(
					RewriteQueryTransformer.builder().chatClientBuilder(chatClientBuilder.build().mutate()).build())
			.documentRetriever(VectorStoreDocumentRetriever.builder()
				.similarityThreshold(0.50)
				.vectorStore(this.vectorStore)
				.build())
			.build();

		String answer = chatClientBuilder.build()
			.prompt()
			.advisors(retrievalAugmentationAdvisor)
			.user(verbose)
			.call()
			.content();

		assertThat(answer).isEqualTo("Evenly.");
		assertThat(model.prompts).hasSize(2);
		assertThat(lastText(model.prompts.get(0))).isEqualTo("""
				Given a user query, rewrite it to provide better results when querying a vector store.
				Remove any irrelevant information, and ensure the query is concise and specific.

				Original query:
				%s

				Rewritten query:
				""".formatted(verbose));
		assertThat(this.embeddings.embeddedTexts()).containsExactly("burr grinder grind evenly");
		assertThat(lastText(model.prompts.get(1))).contains(CoffeeGear.GRINDER.getText(), "Query: " + verbose);
	}

	@Test
	void aQueryExpanderSearchesForEveryVariantOnTheTaskExecutorAndTheJoinerMergesTheResults() {
		ScriptedChatModel model = new ScriptedChatModel((prompt) -> lastText(prompt).contains("Query variants:")
				? text("burr grinder espresso fine\ngooseneck kettle water temperature\npaper filters dripper")
				: text("Here is what we have."));
		ChatClient.Builder chatClientBuilder = ChatClient.builder(model);
		List<String> threads = new CopyOnWriteArrayList<>();
		VectorStoreDocumentRetriever vectorStoreRetriever = VectorStoreDocumentRetriever.builder()
			.similarityThreshold(0.50)
			.vectorStore(this.vectorStore)
			.build();
		DocumentRetriever onWhichThread = (query) -> {
			threads.add(Thread.currentThread().getName());
			return vectorStoreRetriever.retrieve(query);
		};
		SimpleAsyncTaskExecutor taskExecutor = new SimpleAsyncTaskExecutor("rag-test-");
		Advisor retrievalAugmentationAdvisor = RetrievalAugmentationAdvisor.builder()
			.queryExpander(MultiQueryExpander.builder()
				.chatClientBuilder(chatClientBuilder.build().mutate())
				.numberOfQueries(3)
				.build())
			.documentRetriever(onWhichThread)
			.documentJoiner(new ConcatenationDocumentJoiner())
			.taskExecutor(taskExecutor)
			.build();

		ChatResponse response = chatClientBuilder.build()
			.prompt()
			.advisors(retrievalAugmentationAdvisor)
			.user("Burr grinder, kettle or filters for espresso?")
			.call()
			.chatResponse();

		// The original query and the three variants.
		assertThat(this.embeddings.embeddedTexts()).containsExactlyInAnyOrder(
				"Burr grinder, kettle or filters for espresso?", "burr grinder espresso fine",
				"gooseneck kettle water temperature", "paper filters dripper");
		assertThat(threads).hasSize(4).allSatisfy((thread) -> assertThat(thread).startsWith("rag-test-"));
		List<Document> context = response.getMetadata().get(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT);
		assertThat(context).extracting(Document::getId).containsExactlyInAnyOrder("grinder", "kettle", "filters");
		assertThat(context).extracting(Document::getScore).isSortedAccordingTo((a, b) -> Double.compare(b, a));
	}

	/**
	 * Without a joiner or an executor of its own, the advisor joins with a
	 * {@code ConcatenationDocumentJoiner} and retrieves on its own pool, whose threads
	 * see the thread-local context of the caller.
	 */
	@Test
	void theDefaultsRemoveDuplicatesSortByScoreAndPropagateTheCallersContext() {
		ThreadLocal<String> tenant = new ThreadLocal<>();
		ContextRegistry.getInstance()
			.registerThreadLocalAccessor("rag-test-tenant", tenant::get, tenant::set, tenant::remove);
		try {
			ScriptedChatModel model = new ScriptedChatModel((prompt) -> lastText(prompt).contains("Query variants:")
					? text("burr grinder\nburr grinder espresso\ngooseneck kettle") : text("Here."));
			List<String> seen = new CopyOnWriteArrayList<>();
			List<String> retrieved = new CopyOnWriteArrayList<>();
			DocumentRetriever retriever = (query) -> {
				seen.add(Thread.currentThread().getName() + " for " + tenant.get());
				List<Document> documents = this.vectorStore.similaritySearch(query.text());
				documents.forEach((document) -> retrieved.add(document.getId()));
				return documents;
			};
			Advisor retrievalAugmentationAdvisor = RetrievalAugmentationAdvisor.builder()
				.queryExpander(MultiQueryExpander.builder()
					.chatClientBuilder(ChatClient.builder(model))
					.includeOriginal(false)
					.build())
				.documentRetriever(retriever)
				.build();
			tenant.set("acme");

			ChatResponse response = ChatClient.builder(model)
				.build()
				.prompt("Grinder or kettle?")
				.advisors(retrievalAugmentationAdvisor)
				.call()
				.chatResponse();

			assertThat(seen).hasSize(3)
				.allSatisfy((entry) -> assertThat(entry).startsWith("ai-advisor-").endsWith(" for acme"));
			List<Document> context = response.getMetadata().get(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT);
			// Three searches of four documents each, of the five there are.
			assertThat(retrieved).hasSize(12);
			assertThat(context).extracting(Document::getId)
				.containsExactlyInAnyOrderElementsOf(retrieved.stream().distinct().toList());
			assertThat(context).extracting(Document::getScore).isSortedAccordingTo((a, b) -> Double.compare(b, a));
			// The advisor's own pool outlives the call: nothing shuts it down, and its
			// threads keep a JVM alive.
			assertThat(Thread.getAllStackTraces().keySet()).anySatisfy((thread) -> assertThat(
					thread.getName().startsWith("ai-advisor-") && thread.isAlive() && !thread.isDaemon())
				.isTrue());
		}
		finally {
			tenant.remove();
			ContextRegistry.getInstance().removeThreadLocalAccessor("rag-test-tenant");
		}
	}

	/**
	 * The advisor collects the documents of each query into a map keyed by the query. A
	 * model that repeats the original query among its variants, with the original kept as
	 * the default does, gives two equal keys, and the request fails.
	 */
	@Test
	void aVariantThatRepeatsTheOriginalQueryFailsTheRequest() {
		ScriptedChatModel model = new ScriptedChatModel((prompt) -> lastText(prompt).contains("Query variants:")
				? text(QUESTION + "\nburr grinder grind size\ngrinder consistency") : text("Evenly."));
		Advisor retrievalAugmentationAdvisor = RetrievalAugmentationAdvisor.builder()
			.queryExpander(MultiQueryExpander.builder().chatClientBuilder(ChatClient.builder(model)).build())
			.documentRetriever(VectorStoreDocumentRetriever.builder().vectorStore(this.vectorStore).build())
			.build();

		assertThatIllegalStateException()
			.isThrownBy(() -> ChatClient.builder(model)
				.build()
				.prompt(QUESTION)
				.advisors(retrievalAugmentationAdvisor)
				.call()
				.content())
			.withMessageContaining("Duplicate key");
	}

	@Test
	void postProcessorsSeeTheOriginalQueryAndTheirResultIsTheContext() {
		ScriptedChatModel model = new ScriptedChatModel((prompt) -> lastText(prompt).contains("Rewritten query:")
				? text("burr grinder kettle") : text("Here."));
		List<Query> queries = new CopyOnWriteArrayList<>();
		DocumentPostProcessor onlyTheBest = (query, documents) -> {
			queries.add(query);
			return documents.subList(0, 1);
		};
		Advisor retrievalAugmentationAdvisor = RetrievalAugmentationAdvisor.builder()
			.queryTransformers(RewriteQueryTransformer.builder().chatClientBuilder(ChatClient.builder(model)).build())
			.documentRetriever(VectorStoreDocumentRetriever.builder().vectorStore(this.vectorStore).build())
			.documentPostProcessors(onlyTheBest)
			.build();

		ChatClientResponse response = ChatClient.builder(model)
			.build()
			.prompt("Which grinder and which kettle do you sell?")
			.advisors(retrievalAugmentationAdvisor)
			.call()
			.chatClientResponse();

		assertThat(queries).extracting(Query::text).containsExactly("Which grinder and which kettle do you sell?");
		List<Document> context = response.chatResponse().getMetadata().get("rag_document_context");
		assertThat(context).hasSize(1);
		assertThat(response.context()).containsEntry(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT, context);
		assertThat(lastText(model.lastPrompt())).contains(context.get(0).getText());
	}

	@Test
	void byDefaultTheAdvisorRunsInsideTheToolLoopAndSearchesAgainInEveryRound() {
		ScriptedChatModel model = toolThenAnswer();
		RetrievalAugmentationAdvisor advisor = RetrievalAugmentationAdvisor.builder()
			.documentRetriever(VectorStoreDocumentRetriever.builder().vectorStore(this.vectorStore).build())
			.build();
		assertThat(advisor.getOrder()).isZero().isGreaterThan(ToolCallingAdvisor.DEFAULT_ORDER);

		ChatClient.builder(model)
			.build()
			.prompt(QUESTION)
			.tools(new CoffeeGear.Stock())
			.advisors(advisor)
			.call()
			.content();

		assertThat(this.embeddings.embeddedTexts()).containsExactly(QUESTION, QUESTION);
	}

	@Test
	void anOrderBeforeTheToolLoopRetrievesOncePerCall() {
		ScriptedChatModel model = toolThenAnswer();
		RetrievalAugmentationAdvisor advisor = RetrievalAugmentationAdvisor.builder()
			.documentRetriever(VectorStoreDocumentRetriever.builder().vectorStore(this.vectorStore).build())
			.order(ToolCallingAdvisor.DEFAULT_ORDER - 50)
			.build();

		ChatResponse response = ChatClient.builder(model)
			.build()
			.prompt(QUESTION)
			.tools(new CoffeeGear.Stock())
			.advisors(advisor)
			.call()
			.chatResponse();

		assertThat(this.embeddings.embeddedTexts()).containsExactly(QUESTION);
		assertThat(model.prompts).hasSize(2);
		assertThat(response.getMetadata().<List<Document>>get(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT))
			.isNotEmpty();
	}

	private static ScriptedChatModel toolThenAnswer() {
		return new ScriptedChatModel((prompt) -> endsWithToolResult(prompt) ? text("In stock: " + lastText(prompt))
				: toolCall("stock", "{\"item\":\"grinder\"}"));
	}

}
