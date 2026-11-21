package io.github.hjosugi.chatsample;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a model named {@code fake} does in the application: it asks for the tools of the
 * coding agent by what the user wrote, the chat asks the user before a file changes or a
 * command runs, and, with {@code EMBEDDING_PROVIDER=fake}, the guidelines answer a
 * question about testing. One subclass for each of the three formats the stand-in speaks,
 * each with Spring AI's own client of that provider.
 */
abstract class FakeModelTests {

	private static final Pattern DELTA = Pattern.compile("\"delta\":\"((?:[^\"\\\\]|\\\\.)*)\"");

	private static final Pattern CALL_ID = Pattern.compile("event:confirm\\s+data: ?\\{.*?\"calls\":\\[\\{\"id\":\"([^\"]+)\"");

	@Value("${local.server.port}")
	private int port;

	@Autowired
	private Workspace workspace;

	@BeforeEach
	void emptyTheWorkspaces() throws IOException {
		for (String user : new String[] { "alice", "bob" }) {
			try (Stream<Path> files = Files.list(this.workspace.of(user))) {
				for (Path file : (Iterable<Path>) files::iterator) {
					try (Stream<Path> tree = Files.walk(file)) {
						for (Path path : (Iterable<Path>) tree.sorted(Comparator.reverseOrder())::iterator) {
							Files.delete(path);
						}
					}
				}
			}
		}
	}

	@Test
	void aUserListsTheirFiles() throws Exception {
		Files.writeString(this.workspace.of("alice").resolve("notes.txt"), "hello");

		String reply = Browser.signIn(this.port, "alice").chat("List the files");

		assertThat(text(reply)).contains("notes.txt (5 bytes)");
		assertThat(reply).contains("event:done").doesNotContain("event:error");
	}

	@Test
	void aUserOnlySeesTheirOwnWorkspace() throws Exception {
		Files.writeString(this.workspace.of("alice").resolve("alices.txt"), "a");
		Files.writeString(this.workspace.of("bob").resolve("bobs.txt"), "b");

		assertThat(text(Browser.signIn(this.port, "bob").chat("List my files"))).contains("bobs.txt")
			.doesNotContain("alices.txt");
	}

	@Test
	void aUserReadsAndSearchesAFile() throws Exception {
		Files.writeString(this.workspace.of("alice").resolve("notes.txt"), "first line\nhello there\n");
		Browser alice = Browser.signIn(this.port, "alice");

		assertThat(text(alice.chat("Read notes.txt"))).contains("2\thello there");
		assertThat(text(alice.chat("Search for hello"))).contains("notes.txt:2: hello there");
	}

	@Test
	void theChatAsksBeforeAFileIsWritten() throws Exception {
		Browser alice = Browser.signIn(this.port, "alice");
		String question = "Write fresh.txt: made by the agent";

		String paused = alice.chat(question);
		assertThat(paused).contains("event:confirm", "writeFile", "fresh.txt").doesNotContain("event:done", "event:error");
		assertThat(this.workspace.of("alice").resolve("fresh.txt")).doesNotExist();

		String answer = alice.decide(question, callId(paused), true);

		assertThat(text(answer)).contains("Created fresh.txt");
		assertThat(answer).contains("event:done").doesNotContain("event:error");
		assertThat(this.workspace.of("alice").resolve("fresh.txt")).hasContent("made by the agent");
	}

	@Test
	void aFileIsNotWrittenWhenTheUserSaysNo() throws Exception {
		Browser alice = Browser.signIn(this.port, "alice");
		String question = "Please write unwanted.txt: no";

		String paused = alice.chat(question);
		String answer = alice.decide(question, callId(paused), false);

		assertThat(answer).contains("event:done").doesNotContain("event:error");
		assertThat(this.workspace.of("alice").resolve("unwanted.txt")).doesNotExist();
	}

	@Test
	void aPieceOfAFileIsReplacedAfterTheUserAllowed() throws Exception {
		Files.writeString(this.workspace.of("alice").resolve("story.txt"), "once upon a time");
		Browser alice = Browser.signIn(this.port, "alice");
		String question = "Replace 'once' with 'twice' in story.txt";

		String paused = alice.chat(question);
		assertThat(paused).contains("event:confirm", "replaceInFile");
		assertThat(this.workspace.of("alice").resolve("story.txt")).hasContent("once upon a time");

		assertThat(text(alice.decide(question, callId(paused), true))).contains("Replaced the text in story.txt");
		assertThat(this.workspace.of("alice").resolve("story.txt")).hasContent("twice upon a time");
	}

	@Test
	void aCommandRunsInTheWorkspaceAfterTheUserAllowedIt() throws Exception {
		Browser alice = Browser.signIn(this.port, "alice");
		String question = "Run echo hi > out.txt && echo done";

		String paused = alice.chat(question);
		assertThat(paused).contains("event:confirm", "runCommand", "echo hi");
		assertThat(this.workspace.of("alice").resolve("out.txt")).doesNotExist();

		String answer = alice.decide(question, callId(paused), true);

		assertThat(text(answer)).contains("exit code 0", "done");
		assertThat(this.workspace.of("alice").resolve("out.txt")).exists();
	}

	@Test
	void bobCannotWriteInAlicesWorkspaceEvenWhenHeAllowsIt() throws Exception {
		Browser bob = Browser.signIn(this.port, "bob");
		String question = "Write ../alice/planted.txt: x";

		String paused = bob.chat(question);
		String answer = bob.decide(question, callId(paused), true);

		assertThat(text(answer)).contains("is outside the workspace");
		assertThat(this.workspace.of("alice").resolve("planted.txt")).doesNotExist();
	}

	@Test
	void theGuidelinesAnswerAQuestionAboutTesting() throws Exception {
		String reply = Browser.signIn(this.port, "alice").chat("How should I name a test?");

		// Quoted from the page about testing: a bag of words finds a page by the words it
		// shares with the question, which are few, and not always the section that answers it.
		assertThat(text(reply)).startsWith("The guidelines say:").contains("test");
	}

	@Test
	void aRequestItDoesNotKnowGetsAHint() throws Exception {
		String reply = Browser.signIn(this.port, "alice").chat("Tell me a joke");

		assertThat(text(reply)).contains("stand-in", "List the files");
	}

	/**
	 * What the user reads: the pieces of the reply, together.
	 */
	private static String text(String events) {
		StringBuilder text = new StringBuilder();
		Matcher delta = DELTA.matcher(events);
		while (delta.find()) {
			text.append(delta.group(1).replace("\\\"", "\"").replace("\\n", "\n").replace("\\t", "\t"));
		}
		return text.toString();
	}

	private static String callId(String confirm) {
		Matcher id = CALL_ID.matcher(confirm.replace("\n", " "));
		assertThat(id.find()).as("the call that the chat asks about, in " + confirm).isTrue();
		return id.group(1);
	}

}
