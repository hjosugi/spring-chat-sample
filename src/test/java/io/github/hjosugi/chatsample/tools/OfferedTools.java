package io.github.hjosugi.chatsample.tools;

import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * What a prompt offers the model: the tools in its options, which a chat model turns into
 * the tool definitions of its request, and the tool context, which it never sends.
 */
final class OfferedTools {

	private OfferedTools() {
	}

	static List<String> names(Prompt prompt) {
		return callbacks(prompt).stream().map((tool) -> tool.getToolDefinition().name()).toList();
	}

	static ToolDefinition definition(Prompt prompt, String name) {
		return callbacks(prompt).stream()
			.map(ToolCallback::getToolDefinition)
			.filter((definition) -> definition.name().equals(name))
			.findFirst()
			.orElseThrow(() -> new AssertionError("The prompt offers no tool " + name + " but " + names(prompt)));
	}

	static Map<String, Object> toolContext(Prompt prompt) {
		Map<String, Object> context = ((ToolCallingChatOptions) prompt.getOptions()).getToolContext();
		return (context != null) ? context : Map.of();
	}

	private static List<ToolCallback> callbacks(Prompt prompt) {
		if (!(prompt.getOptions() instanceof ToolCallingChatOptions options) || options.getToolCallbacks() == null) {
			return List.of();
		}
		return options.getToolCallbacks();
	}

}
