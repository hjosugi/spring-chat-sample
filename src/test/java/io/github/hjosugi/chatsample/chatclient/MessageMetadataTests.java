package io.github.hjosugi.chatsample.chatclient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.hjosugi.chatsample.support.ScriptedChatModel;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Metadata on the user and system messages of a {@code ChatClient} call, as the page
 * "Message Metadata" shows it: set per call or as a default, validated, and seen by the
 * advisors and the model.
 */
class MessageMetadataTests {

	private final ScriptedChatModel model = ScriptedChatModel.answering("Sunny");

	private final ChatClient chatClient = ChatClient.create(this.model);

	@Test
	void theUserMessageCarriesTheMetadataGivenOneByOneOrAsAMap() {
		this.chatClient.prompt()
			.user((u) -> u.text("What's the weather like?")
				.metadata("messageId", "msg-123")
				.metadata("userId", "user-456")
				.metadata("priority", "high"))
			.call()
			.content();
		Map<String, Object> first = this.model.lastPrompt().getUserMessage().getMetadata();

		this.chatClient.prompt()
			.user((u) -> u.text("What's the weather like?")
				.metadata(Map.of("messageId", "msg-123", "userId", "user-456", "timestamp", 1700000000000L)))
			.call()
			.content();
		Map<String, Object> second = this.model.lastPrompt().getUserMessage().getMetadata();

		assertThat(first).containsEntry("messageId", "msg-123")
			.containsEntry("userId", "user-456")
			.containsEntry("priority", "high");
		assertThat(second).containsEntry("messageId", "msg-123").containsEntry("timestamp", 1700000000000L);
	}

	@Test
	void theSystemMessageCarriesItsMetadata() {
		this.chatClient.prompt()
			.system((s) -> s.text("You are a helpful assistant.").metadata("version", "1.0").metadata("model", "gpt-5.4-mini"))
			.user("Tell me a joke")
			.call()
			.content();

		assertThat(this.model.lastPrompt().getSystemMessage().getMetadata()).containsEntry("version", "1.0")
			.containsEntry("model", "gpt-5.4-mini");
		assertThat(this.model.lastPrompt().getUserMessage().getMetadata()).doesNotContainKey("version");
	}

	@Test
	void theDefaultsOfTheBuilderCarryMetadataToo() {
		ChatClient client = ChatClient.builder(this.model)
			.defaultSystem((s) -> s.text("You are a helpful assistant")
				.metadata("assistantType", "general")
				.metadata("version", "1.0"))
			.defaultUser((u) -> u.text("Default user context").metadata("sessionId", "default-session"))
			.build();

		client.prompt().call().content();

		assertThat(this.model.lastPrompt().getSystemMessage().getMetadata()).containsEntry("assistantType", "general")
			.containsEntry("version", "1.0");
		assertThat(this.model.lastPrompt().getUserMessage().getText()).isEqualTo("Default user context");
		assertThat(this.model.lastPrompt().getUserMessage().getMetadata()).containsEntry("sessionId", "default-session");
	}

	@Test
	void aNullOrEmptyKeyIsRejected() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> this.chatClient.prompt().user((u) -> u.text("Hello").metadata(null, "value")));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> this.chatClient.prompt().user((u) -> u.text("Hello").metadata("", "value")));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> this.chatClient.prompt().system((s) -> s.text("Hello").metadata(null, "value")));
	}

	@Test
	void aNullValueIsRejected() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> this.chatClient.prompt().user((u) -> u.text("Hello").metadata("key", null)));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> this.chatClient.prompt().system((s) -> s.text("Hello").metadata("key", null)));
	}

	@Test
	void aMapWithANullKeyOrValueIsRejected() {
		Map<String, Object> nullValue = new HashMap<>();
		nullValue.put("key", null);
		Map<String, Object> nullKey = new HashMap<>();
		nullKey.put(null, "value");

		assertThatIllegalArgumentException()
			.isThrownBy(() -> this.chatClient.prompt().user((u) -> u.text("Hello").metadata(nullValue)));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> this.chatClient.prompt().user((u) -> u.text("Hello").metadata(nullKey)));
		assertThat(this.model.prompts).isEmpty();
	}

	@Test
	void anAdvisorReadsTheMetadataOfTheMessages() {
		List<Object> seen = new CopyOnWriteArrayList<>();
		BaseAdvisor reader = new BaseAdvisor() {

			@Override
			public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
				seen.add(request.prompt().getUserMessage().getMetadata().get("userId"));
				seen.add(request.prompt().getSystemMessage().getMetadata().get("version"));
				return request;
			}

			@Override
			public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
				return response;
			}

			@Override
			public int getOrder() {
				return 0;
			}

			// An anonymous class has no simple name to give.
			@Override
			public String getName() {
				return "reader";
			}

		};

		this.chatClient.prompt()
			.system((s) -> s.text("Be brief").metadata("version", "1.0"))
			.user((u) -> u.text("Hi").metadata("userId", "user-456"))
			.advisors(reader)
			.call()
			.content();

		assertThat(seen).containsExactly("user-456", "1.0");
	}

}
