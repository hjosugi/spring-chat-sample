package io.github.hjosugi.chatsample.rag;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import io.github.hjosugi.chatsample.support.ScriptedChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import redis.clients.jedis.RedisClient;

import org.springframework.ai.chat.cache.semantic.SemanticCache;
import org.springframework.ai.chat.cache.semantic.SemanticCacheAdvisor;
import org.springframework.ai.chat.cache.semantic.SemanticCacheScopeResolver;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformers.TransformersEmbeddingModel;
import org.springframework.ai.vectorstore.redis.cache.semantic.DefaultSemanticCache;
import org.springframework.ai.vectorstore.redis.cache.semantic.autoconfigure.RedisSemanticCacheAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static io.github.hjosugi.chatsample.support.ScriptedChatModel.lastText;
import static io.github.hjosugi.chatsample.support.ScriptedChatModel.text;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The semantic cache page with Redis, which Testcontainers starts (Redis 8, which has the
 * search and JSON the cache needs): the cache built by hand and by the
 * auto-configuration, its properties, the scope, expiry and clearing.
 */
@Testcontainers(disabledWithoutDocker = true)
class RedisSemanticCacheTests {

	@Container
	static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:8.2"))
		.withExposedPorts(6379);

	private final RedisClient jedisClient = RedisClient.builder()
		.hostAndPort(redis.getHost(), redis.getMappedPort(6379))
		.build();

	private final BagOfWordsEmbeddingModel embeddingModel = new BagOfWordsEmbeddingModel();

	private final ScriptedChatModel chatModel = new ScriptedChatModel(
			(prompt) -> text("Answer " + (this.answers++) + " to: " + lastText(prompt)));

	private int answers = 1;

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(
				AutoConfigurations.of(DataRedisAutoConfiguration.class, RedisSemanticCacheAutoConfiguration.class))
		.withBean(EmbeddingModel.class, () -> this.embeddingModel)
		.withPropertyValues("spring.data.redis.host=" + redis.getHost(),
				"spring.data.redis.port=" + redis.getMappedPort(6379), "spring.data.redis.client-type=jedis");

	@AfterEach
	void emptyRedis() {
		this.jedisClient.flushAll();
		this.jedisClient.close();
	}

	@Test
	void aCacheBuiltByHandStoresUnderItsIndexAndPrefixAndFindsTheSameQuestion() {
		SemanticCache semanticCache = DefaultSemanticCache.builder()
			.jedisClient(this.jedisClient)
			.embeddingModel(this.embeddingModel)
			.distanceThreshold(0.3)
			.indexName("my-semantic-cache")
			.prefix("cache:")
			.build();

		semanticCache.set("What is the capital of France?", response("Paris."));

		assertThat(this.jedisClient.ftList()).contains("my-semantic-cache");
		assertThat(this.jedisClient.keys("cache:*")).hasSize(1);
		assertThat(semanticCache.get("Capital of France?")).map(RedisSemanticCacheTests::textOf).hasValue("Paris.");
		assertThat(semanticCache.get("How do I descale the kettle?")).isEmpty();
	}

	@Test
	void theAdvisorAnswersASimilarQuestionOfTheSameScopeFromRedis() {
		SemanticCacheAdvisor cacheAdvisor = SemanticCacheAdvisor.builder()
			.cache(DefaultSemanticCache.builder()
				.jedisClient(this.jedisClient)
				.embeddingModel(this.embeddingModel)
				.build())
			.scopeResolver((request) -> (String) request.context().get("tenantId"))
			.build();
		ChatClient chatClient = ChatClient.builder(this.chatModel).defaultAdvisors(cacheAdvisor).build();

		String acme = chatClient.prompt("What is the capital of France?")
			.advisors((a) -> a.param("tenantId", "acme"))
			.call()
			.content();
		String acmeAgain = chatClient.prompt("Capital of France?")
			.advisors((a) -> a.param("tenantId", "acme"))
			.call()
			.content();
		String globex = chatClient.prompt("Capital of France?")
			.advisors((a) -> a.param("tenantId", "globex"))
			.call()
			.content();

		assertThat(acmeAgain).isEqualTo(acme);
		assertThat(globex).isNotEqualTo(acme);
		assertThat(this.chatModel.prompts).hasSize(2);
	}

	@Test
	void theTtlOfTheAdvisorMakesTheEntryExpire() {
		SemanticCacheAdvisor cacheAdvisor = SemanticCacheAdvisor.builder()
			.cache(DefaultSemanticCache.builder()
				.jedisClient(this.jedisClient)
				.embeddingModel(this.embeddingModel)
				.build())
			.scopeResolver((request) -> "shared")
			.ttl(Duration.ofHours(1))
			.build();

		ChatClient.builder(this.chatModel)
			.build()
			.prompt("What is the capital of France?")
			.advisors(cacheAdvisor)
			.call()
			.content();

		Set<String> keys = this.jedisClient.keys("semantic-cache:*");
		assertThat(keys).singleElement()
			.satisfies((key) -> assertThat(this.jedisClient.pttl(key)).isBetween(3_500_000L, 3_600_000L));
	}

	/**
	 * Storing with a TTL and without a context sets the expiry in whole seconds, so it
	 * drops what is under a second: 1.5 seconds become one, and half a second removes the
	 * entry at once. With a context, as the advisor stores, it keeps the milliseconds.
	 */
	@Test
	void aTtlWithoutAContextIsCutToWholeSeconds() {
		SemanticCache semanticCache = DefaultSemanticCache.builder()
			.jedisClient(this.jedisClient)
			.embeddingModel(this.embeddingModel)
			.build();

		semanticCache.set("What is the capital of France?", response("Paris."), Duration.ofMillis(1500));
		semanticCache.set("How do I descale the kettle?", response("With citric acid."), "kettles",
				Duration.ofMillis(1500));
		semanticCache.set("Which grinder for espresso?", response("A burr grinder."), Duration.ofMillis(500));

		assertThat(this.jedisClient.keys("semantic-cache:*")).hasSize(2)
			.extracting(this.jedisClient::pttl)
			.anySatisfy((ttl) -> assertThat(ttl).isBetween(1L, 1000L))
			.anySatisfy((ttl) -> assertThat(ttl).isBetween(1001L, 1500L));
		assertThat(semanticCache.get("Which grinder for espresso?")).isEmpty();
	}

	@Test
	void clearRemovesEveryEntry() {
		SemanticCache semanticCache = DefaultSemanticCache.builder()
			.jedisClient(this.jedisClient)
			.embeddingModel(this.embeddingModel)
			.build();
		semanticCache.set("What is the capital of France?", response("Paris."));
		semanticCache.set("How do I descale the kettle?", response("With citric acid."), "kettles");

		semanticCache.clear();

		assertThat(this.jedisClient.keys("semantic-cache:*")).isEmpty();
		assertThat(semanticCache.get("What is the capital of France?")).isEmpty();
	}

	/**
	 * The same as with {@code SimpleVectorStore} in {@code SemanticCacheAdvisorTests}:
	 * storing without a context takes the distance threshold for a similarity threshold.
	 */
	@Test
	void withADistanceThresholdStoringWithoutAContextReplacesALooselySimilarEntry() {
		SemanticCache semanticCache = DefaultSemanticCache.builder()
			.jedisClient(this.jedisClient)
			.embeddingModel(this.embeddingModel)
			.distanceThreshold(0.3)
			.build();

		semanticCache.set("What is the capital of France?", response("Paris."));
		semanticCache.set("What is the capital of Spain?", response("Madrid."));

		assertThat(this.jedisClient.keys("semantic-cache:*")).hasSize(1);
		assertThat(semanticCache.get("What is the capital of France?")).isEmpty();
	}

	@Test
	void theAutoConfigurationMakesTheCacheFromItsPropertiesAndTheAdvisorWhenThereIsAScopeResolver() {
		this.runner
			.withPropertyValues("spring.ai.vectorstore.redis.semantic-cache.index-name=my-app-cache",
					"spring.ai.vectorstore.redis.semantic-cache.prefix=my-app:semantic-cache:",
					"spring.ai.vectorstore.redis.semantic-cache.similarity-threshold=0.85",
					"spring.ai.vectorstore.redis.semantic-cache.ttl=1h")
			.withBean(SemanticCacheScopeResolver.class, () -> (request) -> "shared")
			.run((context) -> {
				SemanticCacheAdvisor advisor = context.getBean(SemanticCacheAdvisor.class);
				assertThat(context).hasSingleBean(SemanticCache.class).hasSingleBean(RedisClient.class);

				ChatClient.builder(this.chatModel)
					.build()
					.prompt("What is the capital of France?")
					.advisors(advisor)
					.call()
					.content();
				ChatClient.builder(this.chatModel)
					.build()
					.prompt("Capital of France?")
					.advisors(advisor)
					.call()
					.content();

				assertThat(this.chatModel.prompts).hasSize(1);
				assertThat(this.jedisClient.ftList()).contains("my-app-cache");
				assertThat(this.jedisClient.keys("my-app:semantic-cache:*")).singleElement()
					.satisfies((key) -> assertThat(this.jedisClient.pttl(key)).isPositive());
			});
	}

	@Test
	void withoutAScopeResolverThereIsACacheButNoAdvisor() {
		this.runner.run((context) -> assertThat(context).hasSingleBean(SemanticCache.class)
			.doesNotHaveBean(SemanticCacheAdvisor.class));
	}

	@Test
	void theAutoConfigurationNeedsJedisTheTransformersModuleAndCanBeTurnedOff() {
		this.runner.withPropertyValues("spring.data.redis.client-type=lettuce")
			.run((context) -> assertThat(context).hasFailed()
				.getFailure()
				.hasMessageContaining("JedisConnectionFactory"));
		this.runner.withClassLoader(new FilteredClassLoader(TransformersEmbeddingModel.class))
			.run((context) -> assertThat(context).hasNotFailed().doesNotHaveBean(SemanticCache.class));
		this.runner.withPropertyValues("spring.ai.vectorstore.redis.semantic-cache.enabled=false")
			.run((context) -> assertThat(context).hasNotFailed().doesNotHaveBean(SemanticCache.class));
	}

	private static ChatResponse response(String text) {
		return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
	}

	private static String textOf(ChatResponse response) {
		return response.getResult().getOutput().getText();
	}

}
