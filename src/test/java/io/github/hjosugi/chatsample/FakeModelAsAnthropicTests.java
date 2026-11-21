package io.github.hjosugi.chatsample;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;

/**
 * The application with a model named {@code fake} of anthropic, and the fake embedding
 * of the guidelines.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.model.chat=anthropic", "spring.ai.anthropic.chat.model=fake",
				"fake-model.port=18101", "spring.ai.model.embedding=fake",
				"agent.workspace=target/fake-workspace/anthropic" })
class FakeModelAsAnthropicTests extends FakeModelTests {

}
