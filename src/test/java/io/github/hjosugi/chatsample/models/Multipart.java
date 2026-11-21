package io.github.hjosugi.chatsample.models;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.hjosugi.chatsample.support.StandIn;

/**
 * The parts of a {@code multipart/form-data} request that a stand-in received, by name,
 * in the order they came.
 */
final class Multipart {

	private static final Pattern NAME = Pattern.compile("name=\"([^\"]*)\"");

	private static final Pattern FILENAME = Pattern.compile("filename=\"([^\"]*)\"");

	/**
	 * The values of each part. A file part has its file name as its value, so a test
	 * sees what the client called the file.
	 */
	final Map<String, List<String>> parts = new LinkedHashMap<>();

	private Multipart(StandIn.Request request) {
		String contentType = request.header("Content-Type");
		if (contentType == null || !contentType.startsWith("multipart/form-data")) {
			throw new IllegalArgumentException("Not a multipart request: " + contentType);
		}
		String boundary = "--" + contentType.substring(contentType.indexOf("boundary=") + "boundary=".length())
			.replace("\"", "");
		for (String part : request.text().split(Pattern.quote(boundary))) {
			int blankLine = part.indexOf("\r\n\r\n");
			if (blankLine < 0) {
				continue;
			}
			String headers = part.substring(0, blankLine);
			String value = part.substring(blankLine + 4).replaceFirst("\r\n$", "");
			Matcher name = NAME.matcher(headers);
			if (!name.find()) {
				continue;
			}
			Matcher filename = FILENAME.matcher(headers);
			this.parts.computeIfAbsent(name.group(1), (key) -> new ArrayList<>())
				.add(filename.find() ? filename.group(1) : value);
		}
	}

	static Multipart of(StandIn.Request request) {
		return new Multipart(request);
	}

	/**
	 * The only value of a part, or {@code null}.
	 */
	String get(String name) {
		List<String> values = this.parts.get(name);
		return (values != null) ? values.get(0) : null;
	}

}
