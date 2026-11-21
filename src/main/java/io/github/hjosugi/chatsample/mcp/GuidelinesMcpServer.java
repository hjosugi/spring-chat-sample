package io.github.hjosugi.chatsample.mcp;

import java.util.List;

import tools.jackson.databind.json.JsonMapper;

import org.springframework.ai.mcp.annotation.McpComplete;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;

/**
 * What an MCP client may ask the agent: the team's coding guidelines, as two tools for a
 * model and two resources for an application, with completion of the topic ids.
 * Everything here only reads, and nothing here knows who asks or reaches a workspace.
 */
class GuidelinesMcpServer {

	private static final JsonMapper json = JsonMapper.shared();

	private final Guidelines guidelines;

	GuidelinesMcpServer(Guidelines guidelines) {
		this.guidelines = guidelines;
	}

	@McpTool(name = "topics", title = "Topics",
			description = "Lists the topics of the team's coding guidelines, such as java, testing and git",
			annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
					openWorldHint = false))
	List<Guidelines.Topic> topics() {
		return this.guidelines.topics();
	}

	@McpTool(name = "guideline", title = "Guideline", description = "Gives the text of one topic of the team's coding guidelines",
			annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
					openWorldHint = false))
	String guideline(@McpToolParam(description = "The topic's id, such as java") String id) {
		return this.guidelines.find(id).map(Guidelines.Guideline::text).orElse("There is no guideline " + id);
	}

	@McpResource(uri = "guidelines://topics", name = "topics", title = "Topics",
			description = "The topics of the team's coding guidelines", mimeType = "application/json")
	String topicsResource() {
		return json.writeValueAsString(this.guidelines.topics());
	}

	@McpResource(uri = "guidelines://topics/{id}", name = "guideline", title = "Guideline",
			description = "One topic of the team's coding guidelines", mimeType = "text/markdown")
	String guidelineResource(String id) {
		return this.guidelines.find(id).map(Guidelines.Guideline::text).orElse("There is no guideline " + id);
	}

	@McpComplete(uri = "guidelines://topics/{id}")
	List<String> topicIds(String prefix) {
		return this.guidelines.topics().stream().map(Guidelines.Topic::id).filter((id) -> id.startsWith(prefix)).toList();
	}

}
