package io.github.hjosugi.chatsample.rag;

import java.util.ArrayList;
import java.util.List;

import io.github.hjosugi.chatsample.help.HelpAutoConfiguration;
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
 * The shop's help pages, which {@link HelpAutoConfiguration} reads into a vector store at
 * startup when the application has an embedding model, and only then.
 */
class HelpDocumentsTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HelpAutoConfiguration.class));

	@Test
	void eachSectionOfTheHelpPagesBecomesADocumentWithItsTitleAndSource() {
		this.runner.withBean(EmbeddingModel.class, BagOfWordsEmbeddingModel::new).run((context) -> {
			SimpleVectorStore help = context.getBean(HelpAutoConfiguration.BEAN_NAME, SimpleVectorStore.class);
			BagOfWordsEmbeddingModel model = context.getBean(BagOfWordsEmbeddingModel.class);

			// Three pages with eight sections; a heading without text of its own makes
			// none.
			assertThat(model.embeddedTexts()).hasSize(8);
			List<Document> all = help.similaritySearch(SearchRequest.builder().query("shop").topK(100).build());
			assertThat(all).extracting((document) -> document.getMetadata().get("source"))
				.containsOnly("shipping.md", "returns.md", "warranty.md");
			assertThat(all).extracting((document) -> document.getMetadata().get("title"))
				.contains("Delivery times", "Refunds", "Making a warranty claim");
		});
	}

	@Test
	void aQuestionFindsTheSectionThatAnswersIt() {
		this.runner.withBean(EmbeddingModel.class, BagOfWordsEmbeddingModel::new).run((context) -> {
			SimpleVectorStore help = context.getBean(HelpAutoConfiguration.BEAN_NAME, SimpleVectorStore.class);

			assertThat(help.similaritySearch("Can I return opened coffee beans?").get(0).getMetadata())
				.containsEntry("source", "returns.md")
				.containsEntry("title", "What cannot be returned");
			assertThat(help.similaritySearch("How much does express delivery cost?").get(0).getMetadata())
				.containsEntry("title", "Shipping costs");
			assertThat(
					help.similaritySearch("My grinder is broken, is it covered by the warranty?").get(0).getMetadata())
				.containsEntry("source", "warranty.md");
		});
	}

	@Test
	void withoutAnEmbeddingModelThereAreNoHelpDocuments() {
		this.runner.run((context) -> assertThat(context).hasNotFailed()
			.doesNotHaveBean(HelpAutoConfiguration.BEAN_NAME)
			.doesNotHaveBean(SimpleVectorStore.class));
	}

	@Test
	void theApplicationPicksTheHelpPagesUpAsAnAutoConfiguration() {
		assertThat(ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader()).getCandidates())
			.contains(HelpAutoConfiguration.class.getName());
	}

	/**
	 * The embedding model of Spring AI's own auto-configuration, which the help pages
	 * must come after, through Spring AI's OpenAI client and a stand-in for the API.
	 * <p>
	 * OpenAI's model embeds the metadata with the text; the help pages keep the
	 * splitter's bookkeeping out of it, so a section is embedded with its title and
	 * source only. And before the first section, the store asks the model for its
	 * dimensions, which costs a call that embeds "Hello World": the model does not know
	 * them, although {@code text-embedding-ada-002} is in Spring AI's table of known
	 * dimensions.
	 */
	@Test
	void theEmbeddingModelOfAProviderEmbedsTheHelpPagesAtStartup() {
		try (StandIn openAi = new StandIn(BagOfWordsEmbeddingModel::openAiEmbeddings)) {
			this.runner.withConfiguration(AutoConfigurations.of(OpenAiEmbeddingAutoConfiguration.class))
				.withPropertyValues("spring.ai.model.embedding=openai", "spring.ai.openai.api-key=test-key",
						"spring.ai.openai.base-url=" + openAi.url())
				.run((context) -> {
					assertThat(context).hasNotFailed().hasBean(HelpAutoConfiguration.BEAN_NAME);
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
						.contains("source: returns.md\ncategory: header_2\ntitle: Refunds\n\n"
								+ "The refund goes back to the original payment method within 5 working days"
								+ " of the return reaching the warehouse.")
						.noneSatisfy((text) -> assertThat(text).contains("parent_document_id", "chunk_index"));

					SimpleVectorStore help = context.getBean(HelpAutoConfiguration.BEAN_NAME, SimpleVectorStore.class);
					assertThat(help.similaritySearch("When does my refund arrive?").get(0).getMetadata())
						.containsEntry("title", "Refunds")
						.containsKey("parent_document_id");
				});
		}
	}

	/**
	 * What the application has by default: the provider's auto-configuration, told to
	 * make no embedding model.
	 */
	@Test
	void withTheDefaultPropertiesTheApplicationReadsNoHelpPages() {
		this.runner.withConfiguration(AutoConfigurations.of(OpenAiEmbeddingAutoConfiguration.class))
			.withPropertyValues("spring.ai.model.embedding=none", "spring.ai.openai.api-key=test-key")
			.run((context) -> assertThat(context).hasNotFailed()
				.doesNotHaveBean(EmbeddingModel.class)
				.doesNotHaveBean(HelpAutoConfiguration.BEAN_NAME));
	}

}
