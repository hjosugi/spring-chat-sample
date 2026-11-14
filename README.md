# spring-chat-sample

A shop assistant that runs [spring-chat](https://github.com/hjosugi/spring-chat) as an application would: behind a Spring Security login, answering with Claude through Spring AI, with tools that read the signed-in user's orders.

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

Open <http://localhost:8080> and sign in as `alice` or `bob`; the password is the name. Without `ANTHROPIC_API_KEY` the application stops at the start and says so. `ANTHROPIC_MODEL` sets the model, `claude-opus-5-5` by default.

Ask "What did I order?" or "Where is A-1001?". Alice asking about Bob's order B-2001 finds nothing: the tools take the user from the login, never from the model. The links above the chat switch between SSE, WebSocket and spring-chat's built-in page at `/chat`.

## What is where

| | |
| --- | --- |
| The chat in a page of the application | `templates/home.html` |
| Tools for the user who asked | `ChatConfiguration`: a `ChatPromptCustomizer` adds `OrderTools` and puts the signed-in user in the tool context |
| The login in front of everything, the chat included | `SecurityConfiguration`; spring-chat sends the CSRF token by itself |
| Title, intro, system prompt and model | `application.properties` |
| A stopped reply that ends at Anthropic too | `CancelOnCloseInterceptor`, added by `ChatConfiguration` |

## A stopped reply ends at Anthropic too

Spring AI 2.0.2 does not close the connection of a stream that is cancelled (`Unbalanced enter/exit` in the log). Stopping a reply, or leaving the page while it streams, would end it in the browser only: the model would write the whole reply, and it would be billed. The branch `repro/anthropic-stop` reproduces it.

`CancelOnCloseInterceptor` closes that connection. `ChatConfiguration` adds it to Spring AI's OkHttp client through an `AnthropicHttpClientBuilderCustomizer`, a hook Spring AI provides. To use spring-chat with Anthropic in an application of your own, copy the class and the bean until Spring AI closes the connection itself.

## Tests

`./mvnw verify` runs the application against a stand-in for the Anthropic API, so it needs no key. It signs in through the login form, takes the CSRF token from the page and asks over SSE and WebSocket, while the stand-in asks for the tools as Claude would. It also stops a long reply, and leaves another, and checks that the stand-in sees the connection close.
