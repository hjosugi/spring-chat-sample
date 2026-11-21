package io.github.hjosugi.chatsample.rag;

import java.util.ArrayList;
import java.util.List;

import io.github.hjosugi.chatsample.guidelines.GuidelinesAutoConfiguration;
import io.github.hjosugi.chatsample.support.StandIn;
import org.junit.jupiter.api.Test;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The team's coding guidelines, which {@link GuidelinesAutoConfiguration} reads into a vector store at
 * startup when the application has an embedding model, and only then.
 */
class GuidelinesDocumentsTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(GuidelinesAutoConfiguration.class));

	@Test
	void eachSectionOfTheGuidelinesBecomesADocumentWithItsTitleAndSource() {
		this.runner.withBean(EmbeddingModel.class, BagOfWordsEmbeddingModel::new).run((context) -> {
			SimpleVectorStore guidelines = context.getBean(GuidelinesAutoConfiguration.BEAN_NAME, SimpleVectorStore.class);
			BagOfWordsEmbeddingModel model = context.getBean(BagOfWordsEmbeddingModel.class);

			// Three pages with eight sections; a heading without text of its own makes
			// none.
			assertThat(model.embeddedTexts()).hasSize(8);
			List<Document> all = guidelines.similaritySearch(SearchRequest.builder().query("guidelines").topK(100).build());
			assertThat(all).extracting((document) -> document.getMetadata().get("source"))
				.containsOnly("java.md", "testing.md", "git.md");
			assertThat(all).extracting((document) -> document.getMetadata().get("title"))
				.contains("Style", "Null handling", "Names", "Commits");
		});
	}

	@Test
	void aQuestionFindsTheSectionThatAnswersIt() {
		this.runner.withBean(EmbeddingModel.class, BagOfWordsEmbeddingModel::new).run((context) -> {
			SimpleVectorStore guidelines = context.getBean(GuidelinesAutoConfiguration.BEAN_NAME, SimpleVectorStore.class);

			assertThat(guidelines.similaritySearch("Can a public method return null?").get(0).getMetadata())
				.containsEntry("source", "java.md")
				.containsEntry("title", "Null handling");
			assertThat(guidelines.similaritySearch("How should I write a commit message?").get(0).getMetadata())
				.containsEntry("source", "git.md")
				.containsEntry("title", "Commits");
			assertThat(guidelines.similaritySearch("How do I wait for a condition in a test?").get(0).getMetadata())
				.containsEntry("source", "testing.md");
		});
	}

	@Test
	void withoutAnEmbeddingModelThereAreNoGuidelineDocuments() {
		this.runner.run((context) -> assertThat(context).hasNotFailed()
			.doesNotHaveBean(GuidelinesAutoConfiguration.BEAN_NAME)
			.doesNotHaveBean(SimpleVectorStore.class));
	}

	@Test
	void theApplicationPicksTheGuidelinesUpAsAnAutoConfiguration() {
		assertThat(ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader()).getCandidates())
			.contains(GuidelinesAutoConfiguration.class.getName());
	}

	/**
	 * The embedding model of Spring AI's own auto-configuration, which the guidelines
	 * must come after, through Spring AI's OpenAI client and a stand-in for the API.
	 * <p>
	 * OpenAI's model embeds the metadata with the text; the guidelines keep the
	 * splitter's bookkeeping out of it, so a section is embedded with its title and
	 * source only. And before the first section, the store asks the model for its
	 * dimensions, which costs a call that embeds "Hello World": the model does not know
	 * them, although {@code text-embedding-ada-002} is in Spring AI's table of known
	 * dimensions.
	 */
	@Test
	void theEmbeddingModelOfAProviderEmbedsTheGuidelinesAtStartup() {
		try (StandIn openAi = new StandIn(BagOfWordsEmbeddingModel::openAiEmbeddings)) {
			this.runner.withConfiguration(AutoConfigurations.of(OpenAiEmbeddingAutoConfiguration.class))
				.withPropertyValues("spring.ai.model.embedding=openai", "spring.ai.openai.api-key=test-key",
						"spring.ai.openai.base-url=" + openAi.url())
				.run((context) -> {
					assertThat(context).hasNotFailed().hasBean(GuidelinesAutoConfiguration.BEAN_NAME);
					assertThat(openAi.requests).isNotEmpty().allSatisfy((request) -> {
						assertThat(request.path()).endsWith("/embeddings");
						assertThat(request.apiKey()).isEqualTo("test-key");
						assertThat(request.body().get("model").asString()).isEqualTo("text-embedding-ada-002");
					});
					List<String> embedded = new ArrayList<>();
					openAi.requests.forEach((
							request) -> request.body().get("input").forEach((input) -> embedded.add(input.asString())));
					assertThat(embedded.get(0)).isEqualTo("Hello World");
					assertThat(embedded.subList(1, embedded.size())).hasSize(8)
						// The category is that of the last node the reader met, such as inline code.
						.anySatisfy((text) -> assertThat(text)
							.matches("(?s)source: testing\\.md\ncategory: \\w+\ntitle: Names\n\n.*a sentence about the behavior.*"))
						.noneSatisfy((text) -> assertThat(text).contains("parent_document_id", "chunk_index"));

					SimpleVectorStore guidelines = context.getBean(GuidelinesAutoConfiguration.BEAN_NAME, SimpleVectorStore.class);
					assertThat(guidelines.similaritySearch("How should I name a test?").get(0).getMetadata())
						.containsEntry("title", "Names")
						.containsKey("parent_document_id");
				});
		}
	}

	/**
	 * What the application has by default: the provider's auto-configuration, told to
	 * make no embedding model.
	 */
	@Test
	void withTheDefaultPropertiesTheApplicationReadsNoGuidelines() {
		this.runner.withConfiguration(AutoConfigurations.of(OpenAiEmbeddingAutoConfiguration.class))
			.withPropertyValues("spring.ai.model.embedding=none", "spring.ai.openai.api-key=test-key")
			.run((context) -> assertThat(context).hasNotFailed()
				.doesNotHaveBean(EmbeddingModel.class)
				.doesNotHaveBean(GuidelinesAutoConfiguration.BEAN_NAME));
	}

}
