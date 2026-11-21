package io.github.hjosugi.chatsample.models;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.hjosugi.chatsample.support.StandIn;
import io.github.hjosugi.chatsample.support.StandIn.Reply;
import tools.jackson.databind.JsonNode;

/**
 * Answers {@code POST /v1/systemone}, the decision protocol of TypeSafe that Ollama
 * serves too, as the provider's own tests show it: every yes/no question yes with 0.92,
 * every choice its first option with 0.85, every score its lowest level.
 */
final class SystemOne {

	private SystemOne() {
	}

	/**
	 * The answers, without a request id, as Ollama, Laya and llmman send them.
	 */
	static Reply answers(StandIn.Request request) {
		List<String> answers = new ArrayList<>();
		for (Map.Entry<String, JsonNode> question : request.body().get("questions").properties()) {
			String name = question.getKey();
			JsonNode criteria = question.getValue().get("criteria");
			switch (question.getValue().get("type").asString()) {
				case "noul" -> answers.add("\"%s\": {\"type\": \"noul\", \"noul\": 0.92}".formatted(name));
				case "choice" -> {
					List<String> options = new ArrayList<>(criteria.propertyNames());
					List<String> probabilities = new ArrayList<>();
					for (int i = 0; i < options.size(); i++) {
						double probability = (i == 0) ? 0.85 : 0.15 / (options.size() - 1);
						probabilities.add("\"%s\": %s".formatted(options.get(i), probability));
					}
					answers.add("""
							"%s": {"type": "choice", "choice": "%s", "probabilities": {%s}, "confidence": 0.8}"""
						.formatted(name, options.get(0), String.join(", ", probabilities)));
				}
				case "score" -> {
					List<String> legend = new ArrayList<>();
					List<String> probabilities = new ArrayList<>();
					for (int i = 0; i < criteria.size(); i++) {
						JsonNode level = criteria.get(i);
						legend.add("\"%d\": \"%s\"".formatted(i,
								level.isString() ? level.asString() : level.get("label").asString()));
						probabilities.add("\"%d\": %s".formatted(i, (i == 0) ? 0.7 : 0.3 / (criteria.size() - 1)));
					}
					answers.add("""
							"%s": {"type": "score", "score": 0.4, "legend": {%s}, "probabilities": {%s}, "confidence": 0.75}"""
						.formatted(name, String.join(", ", legend), String.join(", ", probabilities)));
				}
				default -> throw new IllegalArgumentException("No answer for " + question);
			}
		}
		return Reply.json("""
				{"model": "%s", "answers": {%s}, "usage": {"input_tokens": 312, "output_tokens": 48}}"""
			.formatted(request.body().get("model").asString() + "-pinned", String.join(", ", answers)));
	}

	/**
	 * The answers with the request id that TypeSafe Jev sends.
	 */
	static Reply answersWithRequestId(StandIn.Request request) {
		return answers(request).header("x-typesafe-request-id", "ts-req-1");
	}

}
