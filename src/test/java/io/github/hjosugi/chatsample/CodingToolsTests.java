package io.github.hjosugi.chatsample;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.util.ReflectionUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What each tool of the coding agent does, without a model: the files of a temporary
 * workspace, and what the model is told when it asks for something that is not there, is
 * too large or leaves the workspace.
 */
class CodingToolsTests {

	@TempDir
	Path base;

	private Workspace workspace;

	private CodingTools tools;

	private final ToolContext alice = new ToolContext(Map.of(CodingTools.USER, "alice"));

	@BeforeEach
	void tools() {
		this.workspace = new Workspace(this.base.resolve("workspaces"));
		this.tools = new CodingTools(this.workspace, Duration.ofSeconds(5));
	}

	private Path file(String path, String content) throws IOException {
		Path file = this.workspace.of("alice").resolve(path);
		Files.createDirectories(file.getParent());
		return Files.writeString(file, content);
	}

	@Test
	void theToolsThatChangeSomethingWaitForTheUserAndTheOthersDoNot() {
		Map<String, Boolean> confirmation = new java.util.TreeMap<>();
		ReflectionUtils.doWithMethods(CodingTools.class, (method) -> confirmation.put(method.getName(),
				method.getAnnotation(Tool.class).requiresConfirmation()), (method) -> method.isAnnotationPresent(Tool.class));

		assertThat(confirmation).containsExactlyInAnyOrderEntriesOf(Map.of("listFiles", false, "readFile", false,
				"searchFiles", false, "writeFile", true, "replaceInFile", true, "runCommand", true));
	}

	@Test
	void listFilesShowsDirectoriesAndSizes() throws IOException {
		file("b.txt", "12345");
		file("src/Main.java", "class Main {}");

		assertThat(this.tools.listFiles(null, this.alice)).isEqualTo("b.txt (5 bytes)\nsrc/");
		assertThat(this.tools.listFiles("src", this.alice)).isEqualTo("Main.java (13 bytes)");
		assertThat(this.tools.listFiles("b.txt", this.alice)).isEqualTo("b.txt is not a directory");
	}

	@Test
	void listFilesOfAnEmptyWorkspaceSaysSo() {
		assertThat(this.tools.listFiles(".", this.alice)).isEqualTo("The directory is empty");
	}

	@Test
	void listFilesStopsAfterTheFirstEntries() throws IOException {
		for (int i = 0; i < CodingTools.MAX_ENTRIES + 5; i++) {
			file("f%03d.txt".formatted(i), "");
		}

		String listing = this.tools.listFiles(".", this.alice);

		assertThat(listing.lines()).hasSize(CodingTools.MAX_ENTRIES + 1);
		assertThat(listing).endsWith("... and 5 more");
	}

	@Test
	void readFileNumbersTheLines() throws IOException {
		file("a.txt", "one\ntwo\nthree\n");

		assertThat(this.tools.readFile("a.txt", null, null, this.alice)).isEqualTo("1\tone\n2\ttwo\n3\tthree\n");
		assertThat(this.tools.readFile("a.txt", 2, 2, this.alice)).isEqualTo("2\ttwo\n... 1 more lines");
		assertThat(this.tools.readFile("a.txt", 3, null, this.alice)).isEqualTo("3\tthree\n");
		assertThat(this.tools.readFile("a.txt", 9, null, this.alice)).isEqualTo("a.txt has 3 lines");
	}

	@Test
	void readFileGivesALimitedNumberOfLines() throws IOException {
		file("long.txt", "x\n".repeat(CodingTools.MAX_LINES + 10));

		String text = this.tools.readFile("long.txt", null, null, this.alice);

		assertThat(text.lines().count()).isEqualTo(CodingTools.MAX_LINES + 1);
		assertThat(text).endsWith("... 10 more lines");
	}

	@Test
	void readFileRefusesWhatIsNotAText() throws IOException {
		Files.write(this.workspace.of("alice").resolve("image.bin"), new byte[] { 1, 0, 2 });
		Files.write(this.workspace.of("alice").resolve("big.txt"), new byte[CodingTools.MAX_FILE_BYTES + 1]);
		Files.createDirectory(this.workspace.of("alice").resolve("dir"));

		assertThat(this.tools.readFile("image.bin", null, null, this.alice)).isEqualTo("image.bin is a binary file");
		assertThat(this.tools.readFile("big.txt", null, null, this.alice)).contains("is larger than");
		assertThat(this.tools.readFile("dir", null, null, this.alice)).isEqualTo("dir is not a file");
		assertThat(this.tools.readFile("missing.txt", null, null, this.alice)).isEqualTo("missing.txt is not a file");
	}

	@Test
	void everyToolRefusesAPathOutsideTheWorkspaceAndSaysSo() throws IOException {
		Files.writeString(this.workspace.of("bob").resolve("secret.txt"), "bobs secret");
		String outside = "../bob/secret.txt";

		assertThat(this.tools.listFiles("../bob", this.alice)).contains("outside the workspace");
		assertThat(this.tools.readFile(outside, null, null, this.alice)).contains("outside the workspace")
			.doesNotContain("bobs secret");
		assertThat(this.tools.searchFiles("secret", "../bob", this.alice)).contains("outside the workspace");
		assertThat(this.tools.writeFile(outside, "x", this.alice)).contains("outside the workspace");
		assertThat(this.tools.replaceInFile(outside, "bobs", "alices", this.alice)).contains("outside the workspace");
		assertThat(this.workspace.of("bob").resolve("secret.txt")).hasContent("bobs secret");
	}

	@Test
	void searchFilesGivesPathLineAndText() throws IOException {
		file("a.txt", "nothing\nfind me here\n");
		file("src/B.java", "// find me too\nclass B {}\n");
		file("c.txt", "other");

		assertThat(this.tools.searchFiles("find me", null, this.alice))
			.isEqualTo("a.txt:2: find me here\nsrc/B.java:1: // find me too");
		assertThat(this.tools.searchFiles("find me", "src", this.alice)).isEqualTo("src/B.java:1: // find me too");
		assertThat(this.tools.searchFiles("absent", null, this.alice)).isEqualTo("No match");
		assertThat(this.tools.searchFiles("class \\w", null, this.alice)).isEqualTo("src/B.java:2: class B {}");
	}

	@Test
	void searchFilesSaysWhenThePatternIsNotARegularExpression() {
		assertThat(this.tools.searchFiles("(", null, this.alice)).startsWith("Not a regular expression");
	}

	@Test
	void searchFilesSkipsBinaryFilesAndTheFilesOfARepository() throws IOException {
		Files.write(this.workspace.of("alice").resolve("image.bin"), new byte[] { 'n', 'e', 'e', 'd', 'l', 'e', 0 });
		file(".git/config", "needle");
		file("a.txt", "needle");

		assertThat(this.tools.searchFiles("needle", null, this.alice)).isEqualTo("a.txt:1: needle");
	}

	@Test
	void searchFilesStopsAfterTheFirstMatches() throws IOException {
		file("many.txt", "match\n".repeat(CodingTools.MAX_MATCHES + 20));

		assertThat(this.tools.searchFiles("match", null, this.alice).lines()).hasSize(CodingTools.MAX_MATCHES);
	}

	@Test
	void writeFileMakesTheDirectoriesAndReplacesWhatIsThere() throws IOException {
		assertThat(this.tools.writeFile("src/new/A.java", "class A {}", this.alice)).isEqualTo("Created src/new/A.java (10 bytes)");
		assertThat(this.workspace.of("alice").resolve("src/new/A.java")).hasContent("class A {}");

		assertThat(this.tools.writeFile("src/new/A.java", "class A2 {}", this.alice)).isEqualTo("Replaced src/new/A.java (11 bytes)");
		assertThat(this.workspace.of("alice").resolve("src/new/A.java")).hasContent("class A2 {}");
		assertThat(this.tools.writeFile("src", "x", this.alice)).isEqualTo("src is a directory");
	}

	@Test
	void replaceInFileReplacesOneExactPiece() throws IOException {
		Path file = file("a.txt", "let x = 1;\nlet y = 2;\n");

		assertThat(this.tools.replaceInFile("a.txt", "x = 1", "x = 10", this.alice)).isEqualTo("Replaced the text in a.txt");
		assertThat(file).hasContent("let x = 10;\nlet y = 2;\n");
	}

	@Test
	void replaceInFileChangesNothingWhenThePieceIsMissingOrNotUnique() throws IOException {
		Path file = file("a.txt", "a b a");

		assertThat(this.tools.replaceInFile("a.txt", "c", "d", this.alice)).isEqualTo("The text is not in a.txt");
		assertThat(this.tools.replaceInFile("a.txt", "a", "z", this.alice)).contains("more than once");
		assertThat(this.tools.replaceInFile("a.txt", "", "z", this.alice)).isEqualTo("The text to replace is empty");
		assertThat(this.tools.replaceInFile("nope.txt", "a", "z", this.alice)).isEqualTo("nope.txt is not a file");
		assertThat(file).hasContent("a b a");
	}

	@Test
	void runCommandRunsInTheWorkspaceAndGivesTheExitCodeAndTheOutput() throws IOException {
		file("a.txt", "x");

		assertThat(this.tools.runCommand("ls", this.alice)).isEqualTo("exit code 0\na.txt\n");
		assertThat(this.tools.runCommand("echo out; echo err >&2; exit 3", this.alice)).isEqualTo("exit code 3\nout\nerr\n");
		assertThat(this.tools.runCommand("pwd", this.alice)).contains(this.workspace.of("alice").toString());
	}

	@Test
	void aCommandOfBobRunsInBobsWorkspace() throws IOException {
		file("alices.txt", "x");
		ToolContext bob = new ToolContext(Map.of(CodingTools.USER, "bob"));

		assertThat(this.tools.runCommand("ls", bob)).isEqualTo("exit code 0\n");
	}

	@Test
	void aCommandGetsNoSecretOfTheApplicationsEnvironment() {
		String environment = this.tools.runCommand("env", this.alice);

		assertThat(environment).contains("HOME=" + this.workspace.of("alice"), "PATH=", "LANG=C.UTF-8");
		// What the command was given, and what the shell adds, and nothing else.
		assertThat(environment.lines().skip(1).map((line) -> line.split("=", 2)[0]))
			.isSubsetOf("HOME", "PATH", "LANG", "PWD", "SHLVL", "_", "OLDPWD");
	}

	@Test
	void aCommandThatRunsTooLongIsStopped() {
		CodingTools quick = new CodingTools(this.workspace, Duration.ofMillis(300));

		String result = quick.runCommand("echo started; sleep 30", this.alice);

		assertThat(result).startsWith("stopped after 0 seconds").contains("started");
	}

	@Test
	void theOutputOfACommandIsLimited() {
		String result = this.tools.runCommand("yes | head -c 100000", this.alice);

		assertThat(result.length()).isLessThan(CodingTools.MAX_OUTPUT_CHARS + 100);
		assertThat(result).contains("exit code 0").endsWith("... the output goes on");
	}

}
