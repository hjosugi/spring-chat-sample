# spring-chat-sample

A shop assistant that runs [spring-chat](https://github.com/hjosugi/spring-chat) as an application would: behind a Spring Security login, answering with Claude, or with a model of OpenAI or Google, through Spring AI, with tools that read the signed-in user's orders.

## Run it

spring-chat is not on Maven Central yet, so install it first:

```bash
git clone https://github.com/hjosugi/spring-chat
(cd spring-chat && ./mvnw install -DskipTests)
```

Then, in this repository:

```bash
ANTHROPIC_API_KEY=... ./mvnw spring-boot:run
```

Open <http://localhost:8080> and sign in as `alice` or `bob`; the password is the name. Without the key the application stops at the start and says so.

Claude answers unless these environment variables say otherwise:

| | |
| --- | --- |
| `CHAT_PROVIDER` | Who answers: `anthropic` (the default), `openai` or `gemini` |
| `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `GEMINI_API_KEY` | The key of the one that answers |
| `ANTHROPIC_MODEL`, `OPENAI_MODEL`, `GEMINI_MODEL` | Its model: `claude-haiku-5-5`, `gpt-6.1-terra` or `gemini-3.5-flash-lite` by default |

A model named `fake` needs no key. The application then starts a stand-in for the provider's API, and Spring AI talks to it as it would to the provider. Every reply is 300 words, one every 100 ms:

```bash
CHAT_PROVIDER=openai OPENAI_MODEL=fake ./mvnw spring-boot:run
```

Ask "What did I order?" or "Where is A-1001?". Alice asking about Bob's order B-2001 finds nothing: the tools take the user from the login, never from the model. The links above the chat switch between SSE, WebSocket and spring-chat's built-in page at `/chat`.

## What is where

| | |
| --- | --- |
| The chat in a page of the application | `templates/home.html` |
| Tools for the user who asked | `ChatConfiguration`: a `ChatPromptCustomizer` adds `OrderTools` and puts the signed-in user in the tool context |
| The login in front of everything, the chat included | `SecurityConfiguration`; spring-chat sends the CSRF token by itself |
| Title, intro, system prompt, provider and models | `application.properties` |
| A model named `fake` | The `repro` package: `FakeModelEnvironmentPostProcessor` points Spring AI at a `SlowModel` |

## Stopping a reply does not always end it at the provider

Stopping a reply, or leaving the page while it streams, cancels the stream of Spring AI. Whether that also closes the stream to the provider depends on the provider and on the connection. With Spring AI 2.0.2:

| | Over HTTP/2, as the three APIs answer | Over HTTP/1.1, as a model named `fake` answers, or a proxy that speaks it |
| --- | --- | --- |
| Anthropic | closed at once | stays open, with `Unbalanced enter/exit` in the log |
| OpenAI | closed at once | stays open |
| Gemini | stays open | stays open |

A reply whose stream stays open is written to its end, and billed. That is why replies stop at 8192 tokens. `AnthropicStopReproTests` reproduces the case of Anthropic over HTTP/1.1.

## Tests

`./mvnw verify` runs the application against a stand-in for the Anthropic API, so it needs no key. It signs in through the login form, takes the CSRF token from the page and asks over SSE and WebSocket, while the stand-in asks for the tools as Claude would. It also stops a long reply, and leaves another, and checks that the stand-in sees the connection close. With Spring AI 2.0.2 those two tests fail, and so does `AnthropicStopReproTests`: the stand-in speaks HTTP/1.1, where the connection stays open.

## License

[Apache License 2.0](LICENSE)
