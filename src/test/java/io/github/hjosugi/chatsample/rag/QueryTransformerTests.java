package io.github.hjosugi.chatsample.rag;

import java.util.Map;

import io.github.hjosugi.chatsample.support.ScriptedChatModel;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.CompressionQueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.TranslationQueryTransformer;

import static io.github.hjosugi.chatsample.support.ScriptedChatModel.lastText;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * The query transformers of the Modular RAG page, each asking a chat model for a better
 * query: compressed with the conversation, rewritten, or translated.
 */
class QueryTransformerTests {

	@Test
	void compressionTurnsTheHistoryAndAFollowUpIntoAStandaloneQuery() {
		ScriptedChatModel model = ScriptedChatModel.answering("What is the second largest city of Denmark?");
		Query query = Query.builder()
			.text("And what is its second largest city?")
			.history(new SystemMessage("Be brief."), new UserMessage("What is the capital of Denmark?"),
					new AssistantMessage("Copenhagen is the capital of Denmark."))
			.context(Map.of("tenant", "acme"))
			.build();

		QueryTransformer queryTransformer = CompressionQueryTransformer.builder()
			.chatClientBuilder(ChatClient.builder(model))
			.build();
		Query transformedQuery = queryTransformer.transform(query);

		assertThat(transformedQuery.text()).isEqualTo("What is the second largest city of Denmark?");
		// The rest of the query stays.
		assertThat(transformedQuery.history()).isEqualTo(query.history());
		assertThat(transformedQuery.context()).isEqualTo(query.context());
		// Only the user's and the assistant's messages make the history.
		assertThat(lastText(model.lastPrompt())).isEqualTo("""
				Given the following conversation history and a follow-up query, your task is to synthesize
				a concise, standalone query that incorporates the context from the history.
				Ensure the standalone query is clear, specific, and maintains the user's intent.

				Conversation history:
				USER: What is the capital of Denmark?
				ASSISTANT: Copenhagen is the capital of Denmark.

				Follow-up query:
				And what is its second largest city?

				Standalone query:
				""");
	}

	@Test
	void rewritingDropsWhatDoesNotHelpTheSearch() {
		ScriptedChatModel model = ScriptedChatModel.answering("What is an LLM?");

		QueryTransformer queryTransformer = RewriteQueryTransformer.builder()
			.chatClientBuilder(ChatClient.builder(model))
			.build();
		Query transformedQuery = queryTransformer
			.transform(new Query("I'm studying machine learning. What is an LLM?"));

		assertThat(transformedQuery.text()).isEqualTo("What is an LLM?");
		assertThat(lastText(model.lastPrompt()))
			.startsWith("Given a user query, rewrite it to provide better results when querying a vector store.")
			.contains("Original query:\nI'm studying machine learning. What is an LLM?\n");
	}

	@Test
	void rewritingCanAimAtAnotherSearchSystem() {
		ScriptedChatModel model = ScriptedChatModel.answering("LLM definition");

		RewriteQueryTransformer.builder()
			.chatClientBuilder(ChatClient.builder(model))
			.targetSearchSystem("web search engine")
			.build()
			.transform(new Query("What is an LLM?"));

		assertThat(lastText(model.lastPrompt())).contains("when querying a web search engine.");
	}

	@Test
	void translationAsksForTheTargetLanguage() {
		ScriptedChatModel model = ScriptedChatModel.answering("What is the capital of Denmark?");

		QueryTransformer queryTransformer = TranslationQueryTransformer.builder()
			.chatClientBuilder(ChatClient.builder(model))
			.targetLanguage("english")
			.build();
		Query transformedQuery = queryTransformer.transform(new Query("Hvad er Danmarks hovedstad?"));

		assertThat(transformedQuery.text()).isEqualTo("What is the capital of Denmark?");
		assertThat(lastText(model.lastPrompt())).isEqualTo("""
				Given a user query, translate it to english.
				If the query is already in english, return it unchanged.
				If you don't know the language of the query, return it unchanged.
				Do not add explanations nor any other text.

				Original query: Hvad er Danmarks hovedstad?

				Translated query:
				""");
	}

	/**
	 * Returning the query unchanged when it is in the target language, or in a language
	 * the model does not know, is the model's part: the transformer asks for it and takes
	 * whatever comes back. Only an empty answer leaves the query as it was.
	 */
	@Test
	void anEmptyAnswerLeavesTheQueryAsItWas() {
		Query query = new Query("Hvad er Danmarks hovedstad?");

		Query transformedQuery = TranslationQueryTransformer.builder()
			.chatClientBuilder(ChatClient.builder(ScriptedChatModel.answering("")))
			.targetLanguage("english")
			.build()
			.transform(query);

		assertThat(transformedQuery).isSameAs(query);
	}

	@Test
	void aCustomPromptTemplateReplacesTheDefaultOne() {
		ScriptedChatModel model = ScriptedChatModel.answering("capital Denmark");

		RewriteQueryTransformer.builder()
			.chatClientBuilder(ChatClient.builder(model))
			.promptTemplate(new PromptTemplate("Keywords for a {target} search: {query}"))
			.build()
			.transform(new Query("What is the capital of Denmark?"));

		assertThat(lastText(model.lastPrompt()))
			.isEqualTo("Keywords for a vector store search: What is the capital of Denmark?");
	}

	@Test
	void aCustomPromptTemplateWithoutTheRequiredPlaceholdersIsRefused() {
		ChatClient.Builder chatClientBuilder = ChatClient.builder(ScriptedChatModel.answering("x"));

		assertThatIllegalArgumentException()
			.isThrownBy(() -> CompressionQueryTransformer.builder()
				.chatClientBuilder(chatClientBuilder)
				.promptTemplate(new PromptTemplate("Make it standalone: {query}"))
				.build())
			.withMessageContaining("history");
		assertThatIllegalArgumentException()
			.isThrownBy(() -> TranslationQueryTransformer.builder()
				.chatClientBuilder(chatClientBuilder)
				.targetLanguage("english")
				.promptTemplate(new PromptTemplate("Translate: {query}"))
				.build())
			.withMessageContaining("targetLanguage");
	}

	/**
	 * The page advises a low temperature for transformers. They call the model with the
	 * options of the {@code ChatClient.Builder} they are given, so that is where it goes.
	 */
	@Test
	void theTransformerAsksWithTheOptionsOfItsChatClientBuilder() {
		ScriptedChatModel model = ScriptedChatModel.answering("What is an LLM?");

		RewriteQueryTransformer.builder()
			.chatClientBuilder(ChatClient.builder(model).defaultOptions(ChatOptions.builder().temperature(0.0)))
			.build()
			.transform(new Query("What is an LLM?"));

		assertThat(model.lastPrompt().getOptions().getTemperature()).isEqualTo(0.0);
	}

}
