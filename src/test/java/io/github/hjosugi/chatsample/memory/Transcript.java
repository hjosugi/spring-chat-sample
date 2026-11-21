package io.github.hjosugi.chatsample.memory;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * Messages as one line each, so that a test can compare a prompt or a session with what
 * it expects: {@code user: Hello}, {@code assistant: Hi}, {@code system: Be brief},
 * {@code calls: orderStatus(A-1001)} for a reply that asks for tools, and
 * {@code results: "A-1001 was shipped"} for their results.
 */
public final class Transcript {

	private Transcript() {
	}

	public static List<String> of(Prompt prompt) {
		return of(prompt.getInstructions());
	}

	public static List<String> of(List<? extends Message> messages) {
		return messages.stream().map(Transcript::line).toList();
	}

	public static String line(Message message) {
		if (message instanceof AssistantMessage reply && reply.hasToolCalls()) {
			String calls = "calls: " + reply.getToolCalls()
				.stream()
				.map((call) -> call.name() + "(" + argument(call.arguments()) + ")")
				.collect(Collectors.joining(", "));
			String text = reply.getText();
			return (text == null || text.isBlank()) ? calls : "assistant: " + text.strip() + " | " + calls;
		}
		if (message instanceof ToolResponseMessage results) {
			return "results: " + results.getResponses()
				.stream()
				.map(ToolResponseMessage.ToolResponse::responseData)
				.collect(Collectors.joining(", "));
		}
		return message.getMessageType().getValue() + ": " + message.getText();
	}

	/**
	 * The value of a call's only argument, or its arguments as they are.
	 */
	private static String argument(String arguments) {
		return arguments.replaceAll("^\\{\"[^\"]+\":\"([^\"]*)\"}$", "$1");
	}

}
