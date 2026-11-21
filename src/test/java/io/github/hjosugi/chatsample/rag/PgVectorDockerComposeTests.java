package io.github.hjosugi.chatsample.rag;

import java.io.File;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The PGvector page's tip that PGvector can run as a Spring Boot dev service with Docker
 * Compose, its URL, user and password then coming from the running service. Spring Boot
 * starts {@code rag/compose.yaml} and stops it after; Testcontainers is used only to skip
 * the test without Docker.
 * <p>
 * Spring Boot recognizes a service by its image, and {@code pgvector/pgvector} is not one
 * it knows: only the service with the label
 * {@code org.springframework.boot.service-connection} becomes a connection, the other is
 * left alone.
 */
@Testcontainers(disabledWithoutDocker = true)
class PgVectorDockerComposeTests {

	@Test
	void theLabelledPgvectorServiceBecomesTheDataSourceOfTheStore() throws Exception {
		File compose = new ClassPathResource("rag/compose.yaml").getFile();

		try (ConfigurableApplicationContext context = new SpringApplicationBuilder(Application.class)
			.web(WebApplicationType.NONE)
			.run("--spring.docker.compose.file=" + compose.getAbsolutePath(),
					"--spring.docker.compose.skip.in-tests=false", "--spring.docker.compose.stop.command=down",
					// The test configuration of the application turns these off.
					"--spring.autoconfigure.exclude=", "--spring.ai.vectorstore.type=pgvector",
					"--spring.ai.vectorstore.pgvector.initialize-schema=true")) {
			Map<String, JdbcConnectionDetails> connections = context.getBeansOfType(JdbcConnectionDetails.class);
			VectorStore vectorStore = context.getBean(VectorStore.class);

			assertThat(connections.values()).singleElement().satisfies((connection) -> {
				assertThat(connection.getUsername()).isEqualTo("shop");
				assertThat(connection.getJdbcUrl()).startsWith("jdbc:postgresql://").contains("/help?");
			});
			assertThat(vectorStore).isInstanceOf(PgVectorStore.class);
			vectorStore.add(CoffeeGear.ALL.stream()
				.map((document) -> new Document(document.getText(), document.getMetadata()))
				.toList());
			assertThat(vectorStore.similaritySearch("burr grinder").get(0).getText())
				.isEqualTo(CoffeeGear.GRINDER.getText());
		}
	}

	@Configuration(proxyBeanMethods = false)
	@ImportAutoConfiguration({ DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class,
			PgVectorStoreAutoConfiguration.class })
	static class Application {

		@Bean
		EmbeddingModel embeddingModel() {
			return new BagOfWordsEmbeddingModel();
		}

	}

}
