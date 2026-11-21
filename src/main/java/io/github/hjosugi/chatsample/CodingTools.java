package io.github.hjosugi.chatsample;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * What the coding agent does: it looks at the files of the user's workspace, which needs
 * no one's leave, and changes them or runs a command, which waits for the user's answer.
 * The user comes from the tool context, which the chat fills from the signed-in user,
 * never from the model.
 */
@Component
class CodingTools {

	static final String USER = "user";

	static final int MAX_ENTRIES = 200;

	static final int MAX_LINES = 400;

	static final int MAX_MATCHES = 50;

	static final int MAX_FILE_BYTES = 1_000_000;

	static final int MAX_OUTPUT_CHARS = 20_000;

	private final Workspace workspace;

	private final Duration commandTimeout;

	CodingTools(Workspace workspace, @Value("${agent.command-timeout}") Duration commandTimeout) {
		this.workspace = workspace;
		this.commandTimeout = commandTimeout;
	}

	@Tool(description = "Lists the files and directories of a directory of the workspace")
	String listFiles(@ToolParam(description = "The directory, relative to the workspace; . is the workspace itself",
			required = false) String path, ToolContext context) {
		String user = user(context);
		try {
			Path directory = this.workspace.resolve(user, path);
			if (!Files.isDirectory(directory)) {
				return this.workspace.show(user, directory) + " is not a directory";
			}
			try (Stream<Path> entries = Files.list(directory)) {
				List<String> names = entries.sorted()
					.map((entry) -> Files.isDirectory(entry) ? entry.getFileName() + "/" : entry.getFileName() + " ("
							+ size(entry) + " bytes)")
					.toList();
				if (names.isEmpty()) {
					return "The directory is empty";
				}
				return String.join("\n", names.subList(0, Math.min(names.size(), MAX_ENTRIES)))
						+ ((names.size() > MAX_ENTRIES) ? "\n... and " + (names.size() - MAX_ENTRIES) + " more" : "");
			}
		}
		catch (IllegalArgumentException | IOException ex) {
			return "Cannot list: " + ex.getMessage();
		}
	}

	@Tool(description = "Reads a text file of the workspace, with line numbers. Without lines it reads from the start")
	String readFile(@ToolParam(description = "The file, relative to the workspace") String path,
			@ToolParam(description = "The first line to read, from 1", required = false) Integer startLine,
			@ToolParam(description = "The last line to read, at most " + MAX_LINES + " after the first",
					required = false) Integer endLine,
			ToolContext context) {
		try {
			Path file = this.workspace.resolve(user(context), path);
			if (!Files.isRegularFile(file)) {
				return path + " is not a file";
			}
			if (Files.size(file) > MAX_FILE_BYTES) {
				return path + " is larger than " + MAX_FILE_BYTES + " bytes";
			}
			byte[] bytes = Files.readAllBytes(file);
			if (isBinary(bytes)) {
				return path + " is a binary file";
			}
			List<String> lines = new String(bytes, StandardCharsets.UTF_8).lines().toList();
			int from = Math.max(1, (startLine != null) ? startLine : 1);
			int to = Math.min(lines.size(), Math.min(from + MAX_LINES - 1, (endLine != null) ? endLine : lines.size()));
			if (from > to) {
				return path + " has " + lines.size() + " lines";
			}
			StringBuilder text = new StringBuilder();
			for (int line = from; line <= to; line++) {
				text.append(line).append('\t').append(lines.get(line - 1)).append('\n');
			}
			if (to < lines.size()) {
				text.append("... ").append(lines.size() - to).append(" more lines");
			}
			return text.toString();
		}
		catch (IllegalArgumentException | IOException ex) {
			return "Cannot read: " + ex.getMessage();
		}
	}

	@Tool(description = "Searches the text files of the workspace for a regular expression. Gives the matching "
			+ "lines as path:line: text")
	String searchFiles(@ToolParam(description = "A regular expression, as Java reads it") String pattern,
			@ToolParam(description = "The directory to search, relative to the workspace; the whole workspace "
					+ "by default", required = false) String path,
			ToolContext context) {
		String user = user(context);
		Pattern regex;
		try {
			regex = Pattern.compile(pattern);
		}
		catch (PatternSyntaxException ex) {
			return "Not a regular expression: " + ex.getDescription();
		}
		try {
			Path start = this.workspace.resolve(user, path);
			List<String> matches = new ArrayList<>();
			try (Stream<Path> files = Files.walk(start)) {
				for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile).sorted()::iterator) {
					// Not into a repository's own files.
					if (!Path.of(this.workspace.show(user, file)).toString().matches("(.*/)?\\.git/.*")) {
						search(user, file, regex, matches);
					}
					if (matches.size() >= MAX_MATCHES) {
						break;
					}
				}
			}
			return matches.isEmpty() ? "No match" : String.join("\n", matches);
		}
		catch (IllegalArgumentException | IOException ex) {
			return "Cannot search: " + ex.getMessage();
		}
	}

	/**
	 * Changes a file, so it needs the user's confirmation: Spring AI pauses the reply when
	 * the model calls it, and the chat asks the user to allow the call.
	 */
	@Tool(description = "Writes a file of the workspace with the content, and replaces the file if there is one. "
			+ "Makes the directories", requiresConfirmation = true)
	String writeFile(@ToolParam(description = "The file, relative to the workspace") String path,
			@ToolParam(description = "The whole content of the file") String content, ToolContext context) {
		try {
			Path file = this.workspace.resolve(user(context), path);
			if (Files.isDirectory(file)) {
				return path + " is a directory";
			}
			boolean existed = Files.exists(file);
			Files.createDirectories(file.getParent());
			Files.writeString(file, content);
			return (existed ? "Replaced " : "Created ") + path + " (" + content.getBytes(StandardCharsets.UTF_8).length
					+ " bytes)";
		}
		catch (IllegalArgumentException | IOException ex) {
			return "Cannot write: " + ex.getMessage();
		}
	}

	@Tool(description = "Replaces a piece of text in a file of the workspace with another. The piece has to be "
			+ "in the file exactly once", requiresConfirmation = true)
	String replaceInFile(@ToolParam(description = "The file, relative to the workspace") String path,
			@ToolParam(description = "The text to replace, exactly as the file has it") String oldText,
			@ToolParam(description = "The text that takes its place") String newText, ToolContext context) {
		try {
			Path file = this.workspace.resolve(user(context), path);
			if (!Files.isRegularFile(file)) {
				return path + " is not a file";
			}
			if (oldText.isEmpty()) {
				return "The text to replace is empty";
			}
			String content = Files.readString(file);
			int first = content.indexOf(oldText);
			if (first < 0) {
				return "The text is not in " + path;
			}
			if (content.indexOf(oldText, first + oldText.length()) >= 0) {
				return "The text is in " + path + " more than once: say more of it";
			}
			Files.writeString(file, content.substring(0, first) + newText + content.substring(first + oldText.length()));
			return "Replaced the text in " + path;
		}
		catch (IllegalArgumentException | IOException ex) {
			return "Cannot replace: " + ex.getMessage();
		}
	}

	/**
	 * Runs a command with the rights of the application, so it needs the user's
	 * confirmation. It gets no secret of the application's environment.
	 */
	@Tool(description = "Runs a shell command in the workspace and gives its exit code and output", requiresConfirmation = true)
	String runCommand(@ToolParam(description = "The command, which /bin/sh runs") String command, ToolContext context) {
		try {
			Path root = this.workspace.of(user(context));
			ProcessBuilder builder = new ProcessBuilder("/bin/sh", "-c", command).directory(root.toFile())
				.redirectErrorStream(true);
			// Not the keys of the model providers that the application was started with.
			builder.environment().clear();
			builder.environment().put("PATH", System.getenv().getOrDefault("PATH", "/usr/bin:/bin"));
			builder.environment().put("HOME", root.toString());
			builder.environment().put("LANG", "C.UTF-8");
			Process process = builder.start();
			StringBuilder output = new StringBuilder();
			Thread reader = Thread.ofVirtual().start(() -> read(process.getInputStream(), output));
			boolean finished = process.waitFor(this.commandTimeout.toMillis(), TimeUnit.MILLISECONDS);
			if (!finished) {
				process.destroyForcibly();
			}
			reader.join(1000);
			String text;
			synchronized (output) {
				text = output.toString();
			}
			return (finished ? "exit code " + process.exitValue() : "stopped after " + this.commandTimeout.toSeconds()
					+ " seconds") + "\n" + text;
		}
		catch (IllegalArgumentException | IOException ex) {
			return "Cannot run: " + ex.getMessage();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return "Interrupted";
		}
	}

	private void search(String user, Path file, Pattern regex, List<String> matches) {
		try {
			if (Files.size(file) > MAX_FILE_BYTES) {
				return;
			}
			byte[] bytes = Files.readAllBytes(file);
			if (isBinary(bytes)) {
				return;
			}
			List<String> lines = new String(bytes, StandardCharsets.UTF_8).lines().toList();
			for (int i = 0; i < lines.size() && matches.size() < MAX_MATCHES; i++) {
				if (regex.matcher(lines.get(i)).find()) {
					String line = lines.get(i).strip();
					matches.add(this.workspace.show(user, file) + ":" + (i + 1) + ": "
							+ ((line.length() > 200) ? line.substring(0, 200) + "..." : line));
				}
			}
		}
		catch (IOException ex) {
			// A file that cannot be read has no match.
		}
	}

	/**
	 * Reads what a command writes, up to a limit: more is read and dropped, so the command
	 * does not block on a full pipe.
	 */
	private static void read(InputStream stream, StringBuilder output) {
		byte[] buffer = new byte[4096];
		try {
			int length;
			while ((length = stream.read(buffer)) >= 0) {
				synchronized (output) {
					if (output.length() < MAX_OUTPUT_CHARS) {
						output.append(new String(buffer, 0, length, StandardCharsets.UTF_8));
						if (output.length() >= MAX_OUTPUT_CHARS) {
							output.setLength(MAX_OUTPUT_CHARS);
							output.append("\n... the output goes on");
						}
					}
				}
			}
		}
		catch (IOException ex) {
			// The command is gone.
		}
	}

	private static boolean isBinary(byte[] bytes) {
		for (int i = 0; i < Math.min(bytes.length, 8192); i++) {
			if (bytes[i] == 0) {
				return true;
			}
		}
		return false;
	}

	private static long size(Path file) {
		try {
			return Files.size(file);
		}
		catch (IOException ex) {
			return 0;
		}
	}

	private static String user(ToolContext context) {
		return (String) context.getContext().get(USER);
	}

}
