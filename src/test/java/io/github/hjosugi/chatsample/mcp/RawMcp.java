package io.github.hjosugi.chatsample.mcp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

/**
 * JSON-RPC over plain HTTP, for what an MCP client of Spring AI would not send: a
 * request from a browser page, say.
 */
final class RawMcp {

	static final String INITIALIZE = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
			+ "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"raw\",\"version\":\"1\"}}}";

	private static final HttpClient http = HttpClient.newHttpClient();

	private RawMcp() {
	}

	/**
	 * Posts a JSON-RPC message, as the Streamable HTTP transports expect it.
	 */
	static HttpResponse<String> post(String url, String body, Map<String, String> headers) {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
			.header("Content-Type", "application/json")
			.header("Accept", "application/json, text/event-stream")
			.POST(HttpRequest.BodyPublishers.ofString(body));
		headers.forEach(request::header);
		try {
			return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

}
