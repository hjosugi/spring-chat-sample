package io.github.hjosugi.chatsample.mcp;

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

import org.springframework.ai.mcp.annotation.McpArg;
import org.springframework.ai.mcp.annotation.McpComplete;
import org.springframework.ai.mcp.annotation.McpPrompt;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * A tool, two resources, a prompt and two completions with no parameter that needs a
 * session, so that every protocol serves them: the legacy ones with and without a
 * session, and the 2026-07-28 one.
 */
public class Brewing {

	static final Map<String, String> RECIPES = Map.of("pour-over", "15 g of coffee, 250 g of water at 94 C",
			"french-press", "30 g of coffee, 500 g of water, 4 minutes", "espresso", "18 g in, 36 g out, 28 seconds");

	@McpTool(name = "water", description = "Grams of water for grams of coffee at a brew ratio")
	public int water(@McpToolParam(description = "Grams of coffee") int coffee,
			@McpToolParam(description = "Grams of water for each gram of coffee", required = false) Integer ratio) {
		return coffee * ((ratio != null) ? ratio : 16);
	}

	@McpResource(uri = "recipe://{method}", name = "recipe", description = "How to brew with a method")
	public String recipe(String method) {
		return RECIPES.getOrDefault(method, "No recipe for " + method);
	}

	@McpResource(uri = "shop://hours", name = "hours", description = "When the shop answers", mimeType = "text/plain")
	public String hours() {
		return "9 to 17, Monday to Friday";
	}

	@McpPrompt(name = "brew-guide", description = "Asks for a guide to a brewing method")
	public GetPromptResult brewGuide(@McpArg(name = "method", description = "The brewing method", required = true) String method) {
		return GetPromptResult
			.builder(List.of(PromptMessage.builder(Role.USER, TextContent.builder("Explain how to brew " + method).build()).build()))
			.description("A guide to " + method)
			.build();
	}

	@McpComplete(prompt = "brew-guide")
	public List<String> methods(String prefix) {
		return RECIPES.keySet().stream().filter((method) -> method.startsWith(prefix)).sorted().toList();
	}

	@McpComplete(uri = "recipe://{method}")
	public List<String> recipes(String prefix) {
		return methods(prefix);
	}

	/**
	 * Brewing as a bean.
	 */
	@TestConfiguration(proxyBeanMethods = false)
	public static class Beans {

		@Bean
		Brewing brewing() {
			return new Brewing();
		}

	}

}
