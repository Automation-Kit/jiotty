# AGENTS Instructions

## Tests

- Use AssertJ (`org.assertj.core.api.Assertions`) for test assertions; avoid Hamcrest matchers in new or updated tests.

# Miscellaneous
- Prefer Guava `checkArgument()`/`checkState()` over `if (...) throw IllegalArgumentException/IllegalStateException`, and use `instanceof` pattern variables
  instead of casts when possible.

## Git hygiene

- Always add new files to git unless they are explicitly marked as non-shareable (e.g., secrets in `.env`).
