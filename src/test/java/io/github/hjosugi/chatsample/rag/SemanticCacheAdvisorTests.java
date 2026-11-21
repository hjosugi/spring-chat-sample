package io.github.hjosugi.chatsample.rag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

import io.github.hjosugi.chatsample.support.ScriptedChatModel;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.cache.semantic.SemanticCache;
import org.springframework.ai.chat.cache.semantic.SemanticCacheAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.redis.cache.semantic.DefaultSemanticCache;

import static io.github.hjosugi.chatsample.support.ScriptedChatModel.lastText;
import static io.github.hjosugi.chatsample.support.ScriptedChatModel.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The semantic cache page without Redis: the {@code SemanticCacheAdvisor} and the
 * {@code DefaultSemanticCache} on a {@code SimpleVectorStore}, which the cache accepts in
 * place of Redis. Similar questions are judged by the bag of words, so the thresholds
 * here are lower than the page's. {@code RedisSemanticCacheTests} has the real thing.
 */
class SemanticCacheAdvisorTests {

	private final SimpleVectorStore store = SimpleVectorStore.builder(new BagOfWordsEmbeddingModel()).build();

	private final SemanticCache semanticCache = DefaultSemanticCache.builder()
		.vectorStore(this.store)
		.similarityThreshold(0.7)
		.build();

	private final ScriptedChatModel chatModel = new ScriptedChatModel(
			(prompt) -> text("Answer " + (this.answers++) + " to: " + lastText(prompt)));

	private int answers = 1;

	@Test
	void aSimilarQuestionIsAnsweredFromTheCacheWithoutCallingTheModel() {
		SemanticCacheAdvisor cacheAdvisor = SemanticCacheAdvisor.builder()
			.cache(this.semanticCache)
			.scopeResolver((request) -> "shared")
			.build();

		ChatResponse response1 = ChatClient.builder(this.chatModel)
			.build()
			.prompt("What is the capital of France?")
			.advisors(cacheAdvisor)
			.call()
			.chatResponse();
		ChatResponse response2 = ChatClient.builder(this.chatModel)
			.build()
			.prompt("Tell me the capital city of France")
			.advisors(cacheAdvisor)
			.call()
			.chatResponse();

		assertThat(this.chatModel.prompts).hasSize(1);
		assertThat(response2.getResult().getOutput().getText()).isEqualTo(response1.getResult().getOutput().getText())
			.isEqualTo("Answer 1 to: What is the capital of France?");
	}

	/**
	 * Not on the page: the cache keeps the text of a response and nothing else, so a
	 * cached response has no finish reason, usage or model.
	 */
	@Test
	void aCachedResponseHasTheTextButNotTheMetadata() {
		ChatClient chatClient = ChatClient.builder(this.chatModel)
			.defaultAdvisors(SemanticCacheAdvisor.builder()
				.cache(this.semanticCache)
				.scopeResolver((request) -> "shared")
				.build())
			.build();

		ChatResponse fresh = chatClient.prompt("What is the capital of France?").call().chatResponse();
		ChatResponse cached = chatClient.prompt("What is the capital of France?").call().chatResponse();

		assertThat(fresh.getResult().getMetadata().getFinishReason()).isEqualTo("stop");
		assertThat(fresh.getMetadata().getUsage().getPromptTokens()).isPositive();
		assertThat(cached.getResult().getOutput().getText()).isEqualTo(fresh.getResult().getOutput().getText());
		assertThat(cached.getResult().getMetadata().getFinishReason()).isNull();
		assertThat(cached.getMetadata().getUsage().getPromptTokens()).isZero();
		assertThat(cached.getMetadata().getModel()).isEmpty();
	}

	@Test
	void requestsOfAnotherScopeDoNotShareTheCachedResponses() {
		ChatClient chatClient = ChatClient.builder(this.chatModel)
			.defaultAdvisors(SemanticCacheAdvisor.builder()
				.cache(this.semanticCache)
				.scopeResolver((request) -> (String) request.context().get("tenantId"))
				.build())
			.build();

		String acme = chatClient.prompt()
			.user("What is my account balance?")
			.advisors((a) -> a.param("tenantId", "acme"))
			.call()
			.content();
		String globex = chatClient.prompt()
			.user("What is my account balance?")
			.advisors((a) -> a.param("tenantId", "globex"))
			.call()
			.content();
		String acmeAgain = chatClient.prompt()
			.user("What is my account balance?")
			.advisors((a) -> a.param("tenantId", "acme"))
			.call()
			.content();

		assertThat(this.chatModel.prompts).hasSize(2);
		assertThat(globex).isNotEqualTo(acme);
		assertThat(acmeAgain).isEqualTo(acme);
	}

	@Test
	void theScopeIsStoredAsItsSha256Hash() throws Exception {
		ChatClient.builder(this.chatModel)
			.build()
			.prompt("What is the capital of France?")
			.advisors(
					SemanticCacheAdvisor.builder().cache(this.semanticCache).scopeResolver((request) -> "acme").build())
			.call()
			.content();

		String hash = HexFormat.of()
			.formatHex(MessageDigest.getInstance("SHA-256").digest("acme".getBytes(StandardCharsets.UTF_8)));
		assertThat(entries()).singleElement()
			.satisfies((entry) -> assertThat(entry.getMetadata()).containsEntry("context_hash", hash)
				.containsEntry("response_text", "Answer 1 to: What is the capital of France?"));
	}

	@Test
	void withoutAScopeTheRequestNeitherReadsNorWritesTheCache() {
		ChatClient chatClient = ChatClient.builder(this.chatModel)
			.defaultAdvisors(SemanticCacheAdvisor.builder()
				.cache(this.semanticCache)
				.scopeResolver((request) -> (String) request.context().get("tenantId"))
				.build())
			.build();

		chatClient.prompt("What is the capital of France?").call().content();
		chatClient.prompt("What is the capital of France?").advisors((a) -> a.param("tenantId", " ")).call().content();

		assertThat(this.chatModel.prompts).hasSize(2);
		assertThat(entries()).isEmpty();
	}

	@Test
	void aSystemPromptInTheScopeKeepsThePersonasApart() {
		SemanticCacheAdvisor cacheAdvisor = SemanticCacheAdvisor.builder()
			.cache(this.semanticCache)
			.scopeResolver((request) -> {
				String tenantId = (String) request.context().get("tenantId");
				return (tenantId != null) ? tenantId + "|" + request.prompt().getSystemMessage().getText() : null;
			})
			.build();
		ChatClient chatClient = ChatClient.builder(this.chatModel).defaultAdvisors(cacheAdvisor).build();
		String technical = "You are a technical support specialist. Provide detailed technical answers.";
		String service = "You are a friendly customer service agent. Keep responses brief and helpful.";

		String technicalResponse = chatClient.prompt()
			.system(technical)
			.user("How do I reset my password?")
			.advisors((a) -> a.param("tenantId", "acme"))
			.call()
			.content();
		String serviceResponse = chatClient.prompt()
			.system(service)
			.user("How do I reset my password?")
			.advisors((a) -> a.param("tenantId", "acme"))
			.call()
			.content();
		String technicalAgain = chatClient.prompt()
			.system(technical)
			.user("How do I reset my password?")
			.advisors((a) -> a.param("tenantId", "acme"))
			.call()
			.content();

		assertThat(serviceResponse).isNotEqualTo(technicalResponse);
		assertThat(technicalAgain).isEqualTo(technicalResponse);
		assertThat(this.chatModel.prompts).hasSize(2);
	}

	@Test
	void aStreamedAnswerIsCachedWhenTheStreamEnds() {
		ChatClient chatClient = ChatClient.builder(this.chatModel)
			.defaultAdvisors(SemanticCacheAdvisor.builder()
				.cache(this.semanticCache)
				.scopeResolver((request) -> "shared")
				.build())
			.build();

		List<String> streamed = chatClient.prompt("What is the capital of France?")
			.stream()
			.content()
			.collectList()
			.block();
		List<String> fromCache = chatClient.prompt("What is the capital of France?")
			.stream()
			.content()
			.collectList()
			.block();

		assertThat(this.chatModel.prompts).hasSize(1);
		assertThat(streamed).hasSizeGreaterThan(1);
		assertThat(fromCache).containsExactly(String.join("", streamed));
	}

	/**
	 * The page's note: a TTL needs a {@code RedisVectorStore}. With another store the
	 * advisor fails when it stores the response, after the model has answered.
	 */
	@Test
	void aTtlWithAStoreOtherThanRedisFailsWhenTheResponseIsStored() {
		ChatClient chatClient = ChatClient.builder(this.chatModel)
			.defaultAdvisors(SemanticCacheAdvisor.builder()
				.cache(this.semanticCache)
				.scopeResolver((request) -> "shared")
				.ttl(Duration.ofHours(1))
				.build())
			.build();

		assertThatThrownBy(() -> chatClient.prompt("What is the capital of France?").call().content())
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageStartingWith("TTL requires a RedisVectorStore");
		assertThat(this.chatModel.prompts).hasSize(1);
		assertThat(entries()).isEmpty();
	}

	@Test
	void theCacheCanBeUsedDirectlyWithAndWithoutAContext() {
		ChatResponse paris = response("Paris.");

		this.semanticCache.set("What is the capital of France?", paris);
		this.semanticCache.set("How do I reset my password?", response("Technical answer."),
				"technical-support-context");

		assertThat(this.semanticCache.get("Tell me the capital city of France")).map(SemanticCacheAdvisorTests::textOf)
			.hasValue("Paris.");
		assertThat(this.semanticCache.get("How do I reset my password?", "technical-support-context"))
			.map(SemanticCacheAdvisorTests::textOf)
			.hasValue("Technical answer.");
		assertThat(this.semanticCache.get("How do I reset my password?", "billing-context")).isEmpty();
		assertThat(this.semanticCache.getStore()).isSameAs(this.store);
	}

	@Test
	void storingASimilarQuestionAgainReplacesTheEarlierEntry() {
		this.semanticCache.set("What is the capital of France?", response("Paris."));
		this.semanticCache.set("What is the capital of France?", response("Paris, still."));

		assertThat(entries()).singleElement()
			.satisfies((entry) -> assertThat(entry.getMetadata()).containsEntry("response_text", "Paris, still."));
	}

	/**
	 * With a distance threshold, the lookup turns the distance into a similarity, but
	 * storing without a context does not: it takes the distance for a similarity, so an
	 * entry that is only a little similar counts as the same question and is replaced.
	 */
	@Test
	void withADistanceThresholdStoringWithoutAContextReplacesALooselySimilarEntry() {
		SemanticCache distanceCache = DefaultSemanticCache.builder()
			.vectorStore(this.store)
			.distanceThreshold(0.3)
			.build();

		distanceCache.set("What is the capital of France?", response("Paris."));
		distanceCache.set("What is the capital of Spain?", response("Madrid."));

		assertThat(entries()).extracting(Document::getText).containsExactly("What is the capital of Spain?");
		assertThat(distanceCache.get("What is the capital of France?")).isEmpty();
	}

	/**
	 * {@code clear()} deletes through the Redis client of the store; on another store it
	 * does nothing, without saying so.
	 */
	@Test
	void clearOnAStoreOtherThanRedisDoesNothing() {
		this.semanticCache.set("What is the capital of France?", response("Paris."));

		this.semanticCache.clear();

		assertThat(this.semanticCache.get("What is the capital of France?")).isPresent();
	}

	private List<Document> entries() {
		return this.store.similaritySearch(SearchRequest.builder().query("question").topK(100).build());
	}

	private static ChatResponse response(String text) {
		return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
	}

	private static String textOf(ChatResponse response) {
		return response.getResult().getOutput().getText();
	}

}
