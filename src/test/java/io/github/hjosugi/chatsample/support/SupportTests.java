package io.github.hjosugi.chatsample.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import io.github.hjosugi.chatsample.support.StandIn.Reply;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.annotation.Tool;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the other tests stand on: the scripted model and the stand-in server.
 */
class SupportTests {

	@Test
	void theScriptedModelAnswersACallAndAStream() {
		ScriptedChatModel model = ScriptedChatModel.answering("Two words");
		ChatClient client = ChatClient.create(model);

		assertThat(client.prompt("hello").call().content()).isEqualTo("Two words");
		assertThat(client.prompt("hello").stream().content().collectList().block()).containsExactly("Two ", "words");
		assertThat(model.prompts).hasSize(2);
		assertThat(ScriptedChatModel.lastText(model.lastPrompt())).isEqualTo("hello");
	}

	@Test
	void theChatClientRunsTheToolsTheScriptedModelAsksFor() {
		ScriptedChatModel model = new ScriptedChatModel((prompt) -> ScriptedChatModel.endsWithToolResult(prompt)
				? ScriptedChatModel.text("It is " + ScriptedChatModel.lastText(prompt))
				: ScriptedChatModel.toolCall("time", "{}"));
		ChatClient client = ChatClient.create(model);

		assertThat(client.prompt("What time is it?").tools(new Clock()).call().content()).isEqualTo("It is \"noon\"");
		assertThat(client.prompt("And now?").tools(new Clock()).stream().content().collectList().block())
			.containsExactly("It ", "is ", "\"noon\"");
		assertThat(model.prompts).hasSize(4);
		assertThat(model.lastPrompt().getInstructions()).extracting((message) -> message.getMessageType())
			.containsExactly(MessageType.USER, MessageType.ASSISTANT, MessageType.TOOL);
	}

	@Test
	void theScriptedModelReportsWhyItStoppedAndWhatItUsed() {
		ChatResponse response = ChatClient.create(ScriptedChatModel.answering("Two words"))
			.prompt("one two three")
			.call()
			.chatResponse();

		assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("stop");
		assertThat(response.getMetadata().getUsage().getPromptTokens()).isEqualTo(3);
		assertThat(response.getMetadata().getUsage().getCompletionTokens()).isEqualTo(2);
	}

	@Test
	void theStandInKeepsTheRequestAndAnswersFromItsScript() throws Exception {
		try (StandIn standIn = new StandIn((request) -> request.path().equals("/events")
				? Reply.events(List.of(StandIn.event("one", "{}"), StandIn.PAUSE, StandIn.data("[DONE]")))
				: Reply.json("{\"echo\":" + request.body().get("say") + "}"))) {
			HttpClient http = HttpClient.newHttpClient();

			HttpResponse<String> json = http.send(HttpRequest.newBuilder(URI.create(standIn.url() + "/v1/echo?x=1"))
				.header("Authorization", "Bearer test-key")
				.POST(HttpRequest.BodyPublishers.ofString("{\"say\":\"hi\"}"))
				.build(), HttpResponse.BodyHandlers.ofString());
			HttpResponse<String> events = http.send(HttpRequest.newBuilder(URI.create(standIn.url() + "/events"))
				.POST(HttpRequest.BodyPublishers.ofString("{}"))
				.build(), HttpResponse.BodyHandlers.ofString());

			assertThat(json.body()).isEqualTo("{\"echo\":\"hi\"}");
			assertThat(json.headers().firstValue("Content-Type")).hasValue("application/json");
			assertThat(events.headers().firstValue("Content-Type")).hasValue("text/event-stream");
			assertThat(events.body()).isEqualTo("event: one\ndata: {}\n\ndata: [DONE]\n\n");
			assertThat(standIn.requests).hasSize(2);
			StandIn.Request first = standIn.requests.get(0);
			assertThat(first.method()).isEqualTo("POST");
			assertThat(first.path()).isEqualTo("/v1/echo");
			assertThat(first.query()).isEqualTo("x=1");
			assertThat(first.apiKey()).isEqualTo("test-key");
			assertThat(standIn.last().path()).isEqualTo("/events");
		}
	}

	@Test
	void aScriptThatFailsAnswersWithAnErrorThatSaysSo() throws Exception {
		try (StandIn standIn = new StandIn((request) -> {
			throw new IllegalStateException("no reply for " + request.path());
		})) {
			HttpResponse<String> response = HttpClient.newHttpClient()
				.send(HttpRequest.newBuilder(URI.create(standIn.url() + "/v1/unknown")).build(),
						HttpResponse.BodyHandlers.ofString());

			assertThat(response.statusCode()).isEqualTo(500);
			assertThat(response.body()).contains("The stand-in's script failed", "no reply for /v1/unknown");
		}
	}

	static class Clock {

		@Tool(description = "Tells the time")
		String time() {
			return "noon";
		}

	}

}
