package io.github.hjosugi.chatsample;

import java.util.List;
import java.util.Map;

import io.github.hjosugi.chatsample.help.HelpAutoConfiguration;
import io.github.hjosugi.springchat.ChatPromptCustomizer;

import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.util.StringUtils;

@Configuration(proxyBeanMethods = false)
class ChatConfiguration {

	private static final Map<String, String> KEYS = Map.of("anthropic", "ANTHROPIC_API_KEY", "openai",
			"OPENAI_API_KEY", "google-genai", "GEMINI_API_KEY");

	/**
	 * What the user's question becomes when help pages are close to it. The default of
	 * Spring AI tells the model to answer from the pages and nothing else, which would
	 * keep it from looking up an order.
	 */
	static final String HELP_PAGES_PROMPT = """
			{query}

			These sections of the shop's help pages may answer the question. Use them for questions \
			about shipping, returns and the warranty. For the user's own orders, use the tools.

			---------------------
			{context}
			---------------------
			""";

	/**
	 * Spring AI starts without a key and fails at the first message; the sample stops at
	 * the start instead, and says what to set.
	 */
	ChatConfiguration(Environment environment) {
		String provider = environment.getProperty("spring.ai.model.chat");
		if (!KEYS.containsKey(provider)) {
			throw new InvalidConfigurationPropertyValueException("spring.ai.model.chat", provider,
					"Set the CHAT_PROVIDER environment variable to anthropic, openai or gemini.");
		}
		String property = "spring.ai." + provider.replace('-', '.') + ".api-key";
		if (!StringUtils.hasText(environment.getProperty(property))) {
			throw new InvalidConfigurationPropertyValueException(property, "",
					"The chat answers with %s: set the %s environment variable, or name its model \"fake\"."
						.formatted(provider, KEYS.get(provider)));
		}
		String embedding = environment.getProperty("spring.ai.model.embedding", "none");
		if (!List.of("none", "openai").contains(embedding)) {
			throw new InvalidConfigurationPropertyValueException("spring.ai.model.embedding", embedding,
					"Set the EMBEDDING_PROVIDER environment variable to openai, or leave it unset.");
		}
		String openAiKey = environment.getProperty("spring.ai.openai.api-key");
		// The stand-in of a model named "fake" writes replies, and embeds nothing.
		if (embedding.equals("openai") && (!StringUtils.hasText(openAiKey) || openAiKey.equalsIgnoreCase("fake"))) {
			throw new InvalidConfigurationPropertyValueException("spring.ai.openai.api-key", openAiKey,
					"The help pages are embedded with OpenAI, and not by a model named \"fake\": "
							+ "set the OPENAI_API_KEY environment variable, or leave EMBEDDING_PROVIDER unset.");
		}
	}

	/**
	 * Gives every chat prompt the order tools, for the user who asked. The system prompt
	 * and the conversation memory of spring-chat stay.
	 */
	@Bean
	ChatPromptCustomizer orderToolsForTheUser(OrderTools tools) {
		// SecurityConfiguration lets no one chat without signing in, so there is a principal.
		return (prompt, request) -> prompt.tools(tools)
			.toolContext(Map.of(OrderTools.USER, request.principal().getName()));
	}

	/**
	 * Lets a reply use the shop's help pages, when the application has them, which takes
	 * an embedding model: see {@link HelpAutoConfiguration}. The sections close to the
	 * question go to the model with it, and nowhere else: the advisor comes after the
	 * conversation memory, so the conversation keeps the question as the user wrote it.
	 * That puts it inside the tool loop, where it searches once for each round of a turn.
	 * A question that no section is close to goes to the model as it is.
	 */
	@Bean
	ChatPromptCustomizer helpPagesForTheQuestion(
			@Qualifier(HelpAutoConfiguration.BEAN_NAME) ObjectProvider<VectorStore> helpPages,
			@Value("${shop.help.similarity-threshold}") double similarityThreshold) {
		VectorStore pages = helpPages.getIfAvailable();
		if (pages == null) {
			return (prompt, request) -> {
			};
		}
		Advisor advisor = RetrievalAugmentationAdvisor.builder()
			.documentRetriever(VectorStoreDocumentRetriever.builder()
				.vectorStore(pages)
				.topK(3)
				.similarityThreshold(similarityThreshold)
				.build())
			.queryAugmenter(ContextualQueryAugmenter.builder()
				.promptTemplate(new PromptTemplate(HELP_PAGES_PROMPT))
				// Without it, a question that no section is close to never reaches the model.
				.allowEmptyContext(true)
				.build())
			// One question, one search: on the thread that asks. The advisor's own pool of
			// threads is for several queries at once, and nothing ever shuts it down.
			.taskExecutor(new SyncTaskExecutor())
			.build();
		return (prompt, request) -> prompt.advisors(advisor);
	}

}
