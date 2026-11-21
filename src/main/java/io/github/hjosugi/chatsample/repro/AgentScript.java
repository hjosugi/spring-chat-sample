package io.github.hjosugi.chatsample.repro;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the stand-in model of the coding agent answers, by what the user wrote: it asks
 * for the tool that the request calls for, answers with what the tool returned, and
 * quotes the coding guidelines when it is given some. It reads the request in the three
 * formats it speaks, and knows nothing else: any other question gets a hint of what to
 * ask.
 */
final class AgentScript {

	static final String HINT = "I am a stand-in for the model, and understand only a few requests: "
			+ "List the files. Write notes.txt: hello. Read notes.txt. Search for hello. Run ls -l. "
			+ "How should I name a test? Ask for a long reply to see one that you can stop.";

	private static final JsonMapper JSON = JsonMapper.shared();

	private static final Pattern RUN = Pattern.compile("(?is)^\\s*(?:please\\s+)?(?:run|execute)\\s+(.+?)\\s*$");

	private static final Pattern REPLACE = Pattern
		.compile("(?is)^\\s*replace\\s+[\"'`](.+?)[\"'`]\\s+with\\s+[\"'`](.*?)[\"'`]\\s+in\\s+(\\S+?)\\s*$");

	private static final Pattern WRITE = Pattern
		.compile("(?is)^\\s*(?:please\\s+)?(?:write|create)\\s+(?:a\\s+)?(?:file\\s+)?(\\S+?)\\s*(?::|\\bwith\\b|\\bcontaining\\b)\\s*(.+?)\\s*$");

	private static final Pattern READ = Pattern
		.compile("(?is)^\\s*(?:please\\s+)?(?:read|show|open|cat)\\s+(?:me\\s+)?(?:the\\s+)?(?:file\\s+)?([\\w./-]*[./][\\w./-]*)\\s*$");

	private static final Pattern SEARCH = Pattern
		.compile("(?is)^\\s*(?:please\\s+)?(?:search|find|grep)\\s+(?:for\\s+)?[\"'`]?(.+?)[\"'`]?\\s*$");

	private static final Pattern LIST = Pattern.compile("(?is)^\\s*(?:please\\s+)?(?:list|ls)\\b(?:\\s+(?:the\\s+|my\\s+|all\\s+)?files)?(?:\\s+in)?\\s*([\\w./-]*)\\s*$");

	/**
	 * What Spring AI's advisor puts around the sections of the guidelines.
	 */
	private static final String BAR = "---------------------";

	private static final int QUOTED_WORDS = 60;

	private static final int ANSWER_CHARS = 600;

	private AgentScript() {
	}

	/**
	 * What a reply is: a call of a tool, a text, or the long reply that a stopped reply is
	 * tried with.
	 */
	record Plan(String tool, String arguments, String text) {

		static final Plan LONG = new Plan(null, null, null);

		static Plan call(String tool, String arguments) {
			return new Plan(tool, arguments, null);
		}

		static Plan text(String text) {
			return new Plan(null, null, text);
		}

	}

	/**
	 * The last thing the user wrote, the results of the tools that ran since, and the
	 * tools that the request offers.
	 */
	record Turn(String text, List<String> results, Set<String> tools) {
	}

	static Plan plan(SlowModel.Dialect dialect, JsonNode body) {
		Turn turn = turn(dialect, body);
		if (!turn.results().isEmpty()) {
			return Plan.text("Here is what I found:\n" + shorten(String.join("\n", turn.results().stream().map(AgentScript::readable).toList())));
		}
		// The question comes first: the guidelines are added after it.
		String question = turn.text().split("\n\n", 2)[0].strip();
		Plan call = call(question, turn.tools());
		if (call != null) {
			return call;
		}
		String sections = sections(turn.text());
		if (sections != null) {
			return Plan.text("The guidelines say: " + sections);
		}
		if (question.toLowerCase(Locale.ROOT).contains("long reply")) {
			return Plan.LONG;
		}
		return Plan.text(HINT);
	}

	/**
	 * The tool that a request calls for, if the request offers it.
	 */
	private static Plan call(String question, Set<String> tools) {
		Matcher run = RUN.matcher(question);
		if (run.matches() && tools.contains("runCommand")) {
			return Plan.call("runCommand", "{\"command\":" + JSON.writeValueAsString(unquote(run.group(1))) + "}");
		}
		Matcher replace = REPLACE.matcher(question);
		if (replace.matches() && tools.contains("replaceInFile")) {
			return Plan.call("replaceInFile", "{\"path\":" + JSON.writeValueAsString(replace.group(3)) + ",\"oldText\":"
					+ JSON.writeValueAsString(replace.group(1)) + ",\"newText\":" + JSON.writeValueAsString(replace.group(2)) + "}");
		}
		Matcher write = WRITE.matcher(question);
		if (write.matches() && tools.contains("writeFile")) {
			return Plan.call("writeFile", "{\"path\":" + JSON.writeValueAsString(write.group(1)) + ",\"content\":"
					+ JSON.writeValueAsString(unquote(write.group(2))) + "}");
		}
		Matcher read = READ.matcher(question);
		if (read.matches() && tools.contains("readFile")) {
			return Plan.call("readFile", "{\"path\":" + JSON.writeValueAsString(read.group(1)) + "}");
		}
		Matcher search = SEARCH.matcher(question);
		if (search.matches() && tools.contains("searchFiles")) {
			return Plan.call("searchFiles", "{\"pattern\":" + JSON.writeValueAsString(Pattern.quote(unquote(search.group(1)))) + "}");
		}
		Matcher list = LIST.matcher(question);
		if (list.matches() && tools.contains("listFiles")) {
			return Plan.call("listFiles", "{\"path\":" + JSON.writeValueAsString(list.group(1).isEmpty() ? "." : list.group(1)) + "}");
		}
		return null;
	}

	private static String unquote(String text) {
		String stripped = text.strip();
		if (stripped.length() > 1 && "\"'`".indexOf(stripped.charAt(0)) >= 0 && stripped.charAt(stripped.length() - 1) == stripped.charAt(0)) {
			return stripped.substring(1, stripped.length() - 1);
		}
		return stripped;
	}

	/**
	 * A tool's result is short enough to read in a chat: the start of it, to a line's end.
	 */
	private static String shorten(String text) {
		if (text.length() <= ANSWER_CHARS) {
			return text;
		}
		int end = text.lastIndexOf('\n', ANSWER_CHARS);
		return text.substring(0, (end > 0) ? end : ANSWER_CHARS) + "\n...";
	}

	static Turn turn(SlowModel.Dialect dialect, JsonNode body) {
		return switch (dialect) {
			case ANTHROPIC -> anthropic(body);
			case OPENAI -> openAi(body);
			case GEMINI -> gemini(body);
		};
	}

	private static Turn anthropic(JsonNode body) {
		JsonNode messages = body.path("messages");
		int last = -1;
		String text = "";
		for (int i = 0; i < messages.size(); i++) {
			JsonNode message = messages.get(i);
			if ("user".equals(message.path("role").asString(""))) {
				String written = textOf(message.get("content"));
				if (!written.isEmpty()) {
					last = i;
					text = written;
				}
			}
		}
		List<String> results = new ArrayList<>();
		for (int i = last + 1; i < messages.size(); i++) {
			JsonNode content = messages.get(i).get("content");
			if (content != null && content.isArray()) {
				for (JsonNode block : content) {
					if ("tool_result".equals(block.path("type").asString(""))) {
						results.add(textOf(block.get("content")));
					}
				}
			}
		}
		Set<String> tools = new LinkedHashSet<>();
		for (JsonNode tool : body.path("tools")) {
			tools.add(tool.path("name").asString(""));
		}
		return new Turn(text, results, tools);
	}

	private static Turn openAi(JsonNode body) {
		JsonNode messages = body.path("messages");
		int last = -1;
		String text = "";
		for (int i = 0; i < messages.size(); i++) {
			JsonNode message = messages.get(i);
			if ("user".equals(message.path("role").asString(""))) {
				last = i;
				text = textOf(message.get("content"));
			}
		}
		List<String> results = new ArrayList<>();
		for (int i = last + 1; i < messages.size(); i++) {
			JsonNode message = messages.get(i);
			if ("tool".equals(message.path("role").asString(""))) {
				results.add(textOf(message.get("content")));
			}
		}
		Set<String> tools = new LinkedHashSet<>();
		for (JsonNode tool : body.path("tools")) {
			tools.add(tool.path("function").path("name").asString(""));
		}
		return new Turn(text, results, tools);
	}

	private static Turn gemini(JsonNode body) {
		JsonNode contents = body.path("contents");
		int last = -1;
		String text = "";
		for (int i = 0; i < contents.size(); i++) {
			JsonNode content = contents.get(i);
			if (!"model".equals(content.path("role").asString(""))) {
				StringBuilder written = new StringBuilder();
				for (JsonNode part : content.path("parts")) {
					written.append(part.path("text").asString(""));
				}
				if (!written.isEmpty()) {
					last = i;
					text = written.toString();
				}
			}
		}
		List<String> results = new ArrayList<>();
		for (int i = last + 1; i < contents.size(); i++) {
			for (JsonNode part : contents.get(i).path("parts")) {
				JsonNode response = part.path("functionResponse").path("response");
				if (!response.isMissingNode()) {
					// Spring AI wraps a result in an object of one field.
					JsonNode value = (response.isObject() && response.size() == 1) ? response.iterator().next() : response;
					results.add(value.isString() ? value.asString() : value.toString());
				}
			}
		}
		Set<String> tools = new LinkedHashSet<>();
		for (JsonNode tool : body.path("tools")) {
			for (JsonNode declaration : tool.path("functionDeclarations")) {
				tools.add(declaration.path("name").asString(""));
			}
		}
		return new Turn(text, results, tools);
	}

	/**
	 * The text of a message's content, which is a string or a list of parts.
	 */
	private static String textOf(JsonNode content) {
		if (content == null || content.isNull() || content.isMissingNode()) {
			return "";
		}
		if (content.isString()) {
			return content.asString();
		}
		if (content.isArray()) {
			StringBuilder text = new StringBuilder();
			for (JsonNode part : content) {
				if (part.isString()) {
					text.append(part.asString());
				}
				else if (part.has("text")) {
					text.append(part.get("text").asString(""));
				}
			}
			return text.toString();
		}
		return content.toString();
	}

	/**
	 * A tool's result as a person would say it. Spring AI sends a string as JSON, with
	 * its quotes.
	 */
	static String readable(String raw) {
		String result = raw.strip();
		try {
			JsonNode node = JSON.readTree(result);
			if (node.isString()) {
				return node.asString();
			}
		}
		catch (RuntimeException ex) {
			// Not JSON: a tool's text as it is.
		}
		return result;
	}

	/**
	 * The start of the sections that the advisor of the guidelines put between two lines
	 * of dashes, or null if the question came without any.
	 */
	private static String sections(String text) {
		int start = text.indexOf(BAR);
		int end = (start < 0) ? -1 : text.indexOf(BAR, start + BAR.length());
		if (end < 0) {
			return null;
		}
		String[] words = text.substring(start + BAR.length(), end).replaceAll("[#*`]", " ").strip().split("\\s+");
		if (words.length == 1 && words[0].isEmpty()) {
			return null;
		}
		String quoted = String.join(" ", List.of(words).subList(0, Math.min(words.length, QUOTED_WORDS)));
		return (words.length > QUOTED_WORDS) ? quoted + " ..." : quoted;
	}

}
