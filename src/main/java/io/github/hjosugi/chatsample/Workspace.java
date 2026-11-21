package io.github.hjosugi.chatsample;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The directories that the agent works in: one for each user, below the one that
 * {@code agent.workspace} names. The tools take the user from the login and never from
 * the model, and a path that the model makes up cannot leave the user's directory, by
 * {@code ..}, by an absolute path or by a symbolic link.
 * <p>
 * It keeps files apart, and is no sandbox: a command that the user allows runs with the
 * rights of the application.
 */
@Component
class Workspace {

	// What a user name may be for a directory of its own: the login's names are such.
	private static final Pattern DIRECTORY_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

	private final Path base;

	Workspace(@Value("${agent.workspace}") Path base) {
		this.base = base.toAbsolutePath().normalize();
	}

	/**
	 * The directory of a user, made on first use. It is the real path, with no symbolic
	 * link in it.
	 */
	Path of(String user) {
		if (user == null || !DIRECTORY_NAME.matcher(user).matches()) {
			throw new IllegalArgumentException("No workspace for the user " + user);
		}
		try {
			return Files.createDirectories(this.base.resolve(user)).toRealPath();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * A path of the user's workspace, which may not exist yet: a file that is about to be
	 * written. The workspace itself is the path {@code "."} or an empty one.
	 *
	 * @throws IllegalArgumentException if the path leaves the workspace
	 */
	Path resolve(String user, String path) {
		Path root = of(user);
		String relative = (path == null || path.isBlank()) ? "." : path.strip();
		Path candidate = root.resolve(relative).normalize();
		if (!candidate.startsWith(root)) {
			throw new IllegalArgumentException(relative + " is outside the workspace");
		}
		// What exists of the path must lie in the workspace once links are followed: a link
		// to /etc is a path of the workspace that leads out of it.
		Path existing = candidate;
		while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
			existing = existing.getParent();
		}
		try {
			if (existing != null && !existing.toRealPath().startsWith(root)) {
				throw new IllegalArgumentException(relative + " is outside the workspace");
			}
		}
		catch (IOException ex) {
			// A link that leads nowhere.
			throw new IllegalArgumentException(relative + " is not a path of the workspace");
		}
		return candidate;
	}

	/**
	 * A path as the model sees it: relative to the user's workspace.
	 */
	String show(String user, Path path) {
		Path relative = of(user).relativize(path);
		return relative.toString().isEmpty() ? "." : relative.toString();
	}

}
