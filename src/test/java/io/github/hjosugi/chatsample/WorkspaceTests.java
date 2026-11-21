package io.github.hjosugi.chatsample;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Where the tools may go: the workspace of the user who asked, and nowhere else, whatever
 * path the model makes up.
 */
class WorkspaceTests {

	@TempDir
	Path base;

	private Workspace workspace;

	@BeforeEach
	void workspace() {
		this.workspace = new Workspace(this.base.resolve("workspaces"));
	}

	@Test
	void everyUserHasADirectoryOfTheirOwn() {
		assertThat(this.workspace.of("alice")).isDirectory().isNotEqualTo(this.workspace.of("bob"));
		assertThat(this.workspace.of("alice")).isEqualTo(this.workspace.of("alice"));
	}

	@Test
	void aUserNameThatIsNotADirectoryNameHasNoWorkspace() {
		for (String name : new String[] { "..", ".", "a/b", "../x", "", " ", ".hidden", "a b" }) {
			assertThatIllegalArgumentException().as(name).isThrownBy(() -> this.workspace.of(name));
		}
		assertThatIllegalArgumentException().isThrownBy(() -> this.workspace.of(null));
	}

	@Test
	void aRelativePathIsInTheWorkspaceOfTheUser() {
		assertThat(this.workspace.resolve("alice", "src/Main.java")).isEqualTo(this.workspace.of("alice").resolve("src/Main.java"));
		assertThat(this.workspace.resolve("alice", ".")).isEqualTo(this.workspace.of("alice"));
		assertThat(this.workspace.resolve("alice", null)).isEqualTo(this.workspace.of("alice"));
		assertThat(this.workspace.resolve("alice", "  ")).isEqualTo(this.workspace.of("alice"));
		assertThat(this.workspace.resolve("alice", "a/../b.txt")).isEqualTo(this.workspace.of("alice").resolve("b.txt"));
	}

	@Test
	void aPathThatLeavesTheWorkspaceIsRefused() {
		for (String path : new String[] { "..", "../bob/secret.txt", "a/../../bob", "/etc/passwd", "/" }) {
			assertThatIllegalArgumentException().as(path)
				.isThrownBy(() -> this.workspace.resolve("alice", path))
				.withMessageContaining("outside the workspace");
		}
	}

	@Test
	void aLinkToAnotherDirectoryDoesNotLeadOutOfTheWorkspace() throws IOException {
		Path secret = Files.writeString(this.workspace.of("bob").resolve("secret.txt"), "bobs secret");
		Path alice = this.workspace.of("alice");
		Files.createSymbolicLink(alice.resolve("to-secret"), secret);
		Files.createSymbolicLink(alice.resolve("to-bob"), this.workspace.of("bob"));

		assertThatIllegalArgumentException().isThrownBy(() -> this.workspace.resolve("alice", "to-secret"))
			.withMessageContaining("outside the workspace");
		assertThatIllegalArgumentException().isThrownBy(() -> this.workspace.resolve("alice", "to-bob"));
		// A file that would be made through the link as well.
		assertThatIllegalArgumentException().isThrownBy(() -> this.workspace.resolve("alice", "to-bob/new.txt"));
	}

	@Test
	void aLinkThatLeadsNowhereIsRefused() throws IOException {
		Files.createSymbolicLink(this.workspace.of("alice").resolve("dangling"), this.base.resolve("does-not-exist"));

		assertThatIllegalArgumentException().isThrownBy(() -> this.workspace.resolve("alice", "dangling"));
	}

	@Test
	void aLinkInsideTheWorkspaceIsFollowed() throws IOException {
		Path alice = this.workspace.of("alice");
		Files.writeString(alice.resolve("real.txt"), "x");
		Files.createSymbolicLink(alice.resolve("alias.txt"), alice.resolve("real.txt"));

		assertThat(this.workspace.resolve("alice", "alias.txt")).isEqualTo(alice.resolve("alias.txt"));
	}

	@Test
	void aPathIsShownRelativeToTheWorkspace() {
		assertThat(this.workspace.show("alice", this.workspace.resolve("alice", "src/Main.java"))).isEqualTo("src/Main.java");
		assertThat(this.workspace.show("alice", this.workspace.of("alice"))).isEqualTo(".");
	}

}
