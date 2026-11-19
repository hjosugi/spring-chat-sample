# spring-chat-sample

> **This branch reproduces a problem of Spring AI:** a stopped reply can go on at the provider. See [Reproduce it](#reproduce-it-a-stopped-reply-goes-on-at-the-provider).

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

## Reproduce it: a stopped reply goes on at the provider

When a reply is stopped, spring-chat cancels the stream of Spring AI's chat model. The cancel should close the stream to the provider, which then stops writing the reply. Where it does not, the model writes the whole reply and it is billed.

### With a test

```bash
./mvnw test -Dtest=AnthropicStopReproTests
```

The test uses Spring AI's `AnthropicChatModel` alone, without Spring Boot, `ChatClient` or spring-chat, against `SlowModel`, a stand-in for the API that streams 200 words in 10 seconds. It cancels after the first words and expects the connection to close within 5 seconds. With Spring AI 2.0.2 it fails:

```
[the connection closed after the cancel; errors dropped while cancelling: [java.lang.IllegalStateException: Unbalanced enter/exit]]
Expecting
to be completed within 5S.
```

The other test of the class shows that `SlowModel` does see a client that closes the connection.

It depends on timing. Run alone it failed 4 times of 4; in `./mvnw verify` it failed 3 times of 4. The time it passed, the cancel fell between two reads: the close went through, and the reading thread ended with `IOException: closed`.

### In the browser, without a key

```bash
ANTHROPIC_MODEL=fake ./mvnw spring-boot:run
```

The application talks to a `SlowModel` of its own on port 18089, whose replies are 300 words, one every 100 ms. Sign in, ask anything, and press the stop button after a few words. The browser stops at once, but the log shows:

```
21:00:01.016 INFO  SlowModel : Streaming a reply of 300 words, one every 100 ms, as ANTHROPIC
21:00:02.351 ERROR Operators : Operator called default onErrorDropped
java.lang.IllegalStateException: Unbalanced enter/exit
21:00:31.211 INFO  SlowModel : Streamed all 300 words: the client kept the connection open to the end
```

It is the same over SSE and WebSocket, and when the page is closed during a reply.

With `CHAT_PROVIDER=openai OPENAI_MODEL=fake` and with `CHAT_PROVIDER=gemini GEMINI_MODEL=fake` no error is logged, but `Streamed all 300 words` comes 30 seconds later all the same.

### With a key, over HTTP/2

OkHttp, which all three clients use, can log every HTTP/2 frame:

```bash
CHAT_PROVIDER=gemini GEMINI_API_KEY=... ./mvnw spring-boot:run \
    -Dspring-boot.run.arguments=--logging.level.okhttp3.internal.http2=DEBUG
```

Ask for a long story and stop it after a few words. Each `<< 0x00000003 ... DATA` line is a piece of the reply arriving.

- `>> 0x00000003     4 RST_STREAM` right after the stop, and no more `DATA`: the stream is closed.
- `DATA` lines that go on to one with `END_STREAM`: the reply was written to its end.

Measured on 2026-11-19 with the chat models alone, cancelled after their first three chunks:

| | After the cancel |
| --- | --- |
| Anthropic, `claude-haiku-5-5` | `RST_STREAM` sent 0 ms later, no `DATA` after it |
| OpenAI, `gpt-6.1-terra` | `RST_STREAM` sent 2 ms later, no `DATA` after it |
| Gemini, `gemini-3.5-flash-lite` | no `RST_STREAM`; 155 more `DATA` frames, 71804 bytes, to `END_STREAM` 16 seconds later |

The same three against a `SlowModel` over HTTP/1.1, 150 words at 50 ms: all three received all 150 words. Only Anthropic dropped an error, `Unbalanced enter/exit`.

Whether a provider stops writing, and billing, when its stream is reset cannot be seen from the client.

### What goes wrong

Anthropic over HTTP/1.1:

1. The cancel reaches `AnthropicChatModel$PendingResponse.cancel()`, on the thread that cancels: a Tomcat or Netty thread, or one of Reactor's.
2. It closes the response, `SpringAiAnthropicHttpClient$3.close()`, while a `boundedElastic` thread is blocked reading that response.
3. OkHttp's `Http1ExchangeCodec$ChunkedSource.close()` reads the rest of the body to discard it. Its read enters okio's `AsyncTimeout`, which the reading thread has entered already, and throws `IllegalStateException: Unbalanced enter/exit`.
4. The close is abandoned and Reactor drops the error. The connection stays open, and the reading thread reads the reply to its end.

Closing a response that another thread reads is not safe in OkHttp over HTTP/1.1; `Call.cancel()` is the way to abort a call from another thread. Over HTTP/2 the same close resets the stream, so the problem does not show against `api.anthropic.com`.

OpenAI over HTTP/1.1: the connection stays open in the same way, but nothing is logged. Spring AI's `main` closes the response from the cancelling thread there too (`sink.onDispose(response::close)` in `OpenAiChatModel`); where the error goes was not looked into.

Gemini, over both: nothing closes the stream. Spring AI's `main` wraps the SDK's `ResponseStream` in `Flux.fromIterable` in `GoogleGenAiChatModel`, and a cancel only stops the iteration.

Found with Spring AI 2.0.2, anthropic-java-core 2.72.0, openai-java-core 4.80.0, google-genai 1.65.0 and OkHttp 4.12.0. Spring AI 2.1.0-RC2 has the same `PendingResponse`.

Spring AI 2.0.0 and 2.0.1 close the connection: `AnthropicStopReproTests` passes with `-Dspring-ai.version=2.0.1` (4 times of 4). Since 2.0.2 a cancelled stream closes its response at once, also while it is still opening ([spring-ai#7163](https://github.com/spring-projects/spring-ai/pull/7163)). What is left is the case above: over HTTP/1.1, a cancel that arrives during a read.

The Anthropic SDK solved the same case in its own OkHttp client in 2.64.0 (`ResponseBodyInputStream`: a close that races a read cancels the call, any other close closes the body). Spring AI's `SpringAiAnthropicHttpClient` and `SpringAiOpenAiHttpClient` are ports of the SDKs' clients and close the body directly, on `main` too (4cce4caa, 2026-11-15).

Related reports, as of 2026-11-19:

| | |
| --- | --- |
| Gemini does not close the stream | [spring-ai#7245](https://github.com/spring-projects/spring-ai/issues/7245) and [#7250](https://github.com/spring-projects/spring-ai/pull/7250), open; [java-genai#1372](https://github.com/googleapis/java-genai/issues/1372) |
| OpenAI did not close the stream | [spring-ai#6654](https://github.com/spring-projects/spring-ai/issues/6654), solved by [#6656](https://github.com/spring-projects/spring-ai/pull/6656); [#7187](https://github.com/spring-projects/spring-ai/pull/7187), open, changes how its streams are opened and closed |
| The same `Unbalanced enter/exit` in the OpenAI SDK | [openai-java#1316](https://github.com/openai/openai-java/pull/1316), closed without a merge |
| OkHttp on closing from another thread | [okhttp#4110](https://github.com/square/okhttp/issues/4110): cancel the call instead |

None of them is about Anthropic or OpenAI over HTTP/1.1 in Spring AI.

## License

[Apache License 2.0](LICENSE)
