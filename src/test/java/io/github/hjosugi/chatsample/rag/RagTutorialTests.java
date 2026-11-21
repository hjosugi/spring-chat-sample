package io.github.hjosugi.chatsample.rag;

import java.util.List;

import io.github.hjosugi.chatsample.support.ScriptedChatModel;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static io.github.hjosugi.chatsample.support.ScriptedChatModel.lastText;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The "Chat with Your Documents" tutorial, with its own classes: a vector store bean, a
 * runner that loads a handbook at startup, and a controller whose chat client has the
 * {@code QuestionAnswerAdvisor}. The bag of words stands in for OpenAI's embedding model,
 * a scripted model for its chat model, and the controller is called as a bean rather than
 * over HTTP.
 */
class RagTutorialTests {

	@Test
	void theHandbookIsLoadedAtStartupAndAQuestionIsAnsweredFromIt() {
		try (ConfigurableApplicationContext context = new SpringApplicationBuilder(Tutorial.class)
			.web(WebApplicationType.NONE)
			.run()) {
			HandbookController controller = context.getBean(HandbookController.class);
			ScriptedChatModel chatModel = context.getBean(ScriptedChatModel.class);
			BagOfWordsEmbeddingModel embeddingModel = context.getBean(BagOfWordsEmbeddingModel.class);

			String answer = controller.ask("How many vacation days do I get");

			assertThat(answer).isEqualTo("28 days a year.");
			// The runner embedded the handbook at startup, the advisor the question.
			assertThat(embeddingModel.embeddedTexts()).hasSize(2).last().isEqualTo("How many vacation days do I get");
			assertThat(lastText(chatModel.lastPrompt())).startsWith("How many vacation days do I get")
				.contains("every employee gets 28 vacation days a year");
		}
	}

	@Test
	void theRunnerSplitsTheHandbookBeforeAddingIt() {
		try (ConfigurableApplicationContext context = new SpringApplicationBuilder(Tutorial.class)
			.web(WebApplicationType.NONE)
			.run()) {
			SimpleVectorStore vectorStore = context.getBean(SimpleVectorStore.class);

			List<Document> chunks = vectorStore.similaritySearch("vacation days");

			// Small enough for one chunk: the whole file, with the source of the reader.
			assertThat(chunks).hasSize(1);
			assertThat(chunks.get(0).getMetadata()).containsEntry("source", "handbook.txt").containsKey("chunk_index");
		}
	}

	@Configuration(proxyBeanMethods = false)
	@Import({ ChatClientAutoConfiguration.class, RagConfiguration.class, HandbookController.class })
	static class Tutorial {

		@Bean
		ScriptedChatModel chatModel() {
			return ScriptedChatModel.answering("28 days a year.");
		}

		@Bean
		BagOfWordsEmbeddingModel embeddingModel() {
			return new BagOfWordsEmbeddingModel();
		}

		@Bean
		ApplicationRunner loadDocuments(VectorStore vectorStore,
				@Value("classpath:rag/handbook.txt") Resource handbook) {
			return (args) -> {
				List<Document> documents = new TextReader(handbook).read();
				List<Document> chunks = TokenTextSplitter.builder().build().apply(documents);
				vectorStore.add(chunks);
			};
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class RagConfiguration {

		@Bean
		VectorStore vectorStore(EmbeddingModel embeddingModel) {
			return SimpleVectorStore.builder(embeddingModel).build();
		}

	}

	@RestController
	static class HandbookController {

		private final ChatClient chatClient;

		HandbookController(ChatClient.Builder chatClientBuilder, VectorStore vectorStore) {
			this.chatClient = chatClientBuilder.defaultAdvisors(QuestionAnswerAdvisor.builder(vectorStore).build())
				.build();
		}

		@GetMapping("/ask")
		String ask(@RequestParam String question) {
			return this.chatClient.prompt().user(question).call().content();
		}

	}

}
