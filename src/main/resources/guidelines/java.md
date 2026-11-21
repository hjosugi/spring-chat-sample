# Java

## Style

Use tabs for indentation and keep lines under 120 characters.
Name a class in the singular and a method after what it does: `findOrder`, not `getOrderOrNull`.
Prefer an immutable record for data that only carries values.

## Null handling

Do not return null from a public method: return an `Optional` for a result that may be missing, or an empty collection.
Mark a parameter that may be null with `@Nullable` from JSpecify, and say why in the Javadoc.

## Exceptions

Throw the most specific unchecked exception that says what went wrong, with a message that a person can act on.
Never catch `Exception` to hide it. Catch what you can handle and let the rest reach the top.
