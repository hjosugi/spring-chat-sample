# Testing

## Names

A test's name is a sentence about the behavior: `aliceCannotReadBobsOrder`, not `testRead2`.
One test checks one behavior.

## What to assert

Assert what can be observed: a result, a request that went out, a stored value.
A test that only checks that nothing was thrown verifies nothing.
Use JUnit 5 and AssertJ, and do not mock what a plain object can stand in for.

## Speed

A unit test finishes in milliseconds. Do not sleep to wait for something: wait for the condition with Awaitility.
Start the whole application in a test only when the application is the point.
