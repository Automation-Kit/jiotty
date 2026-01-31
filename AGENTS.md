# AGENTS Instructions

- Use AssertJ (`org.assertj.core.api.Assertions`) for test assertions; avoid Hamcrest matchers in new or updated tests.
- Prefer Guava `checkArgument()`/`checkState()` over `if (...) throw IllegalArgumentException/IllegalStateException`, and use `instanceof` pattern variables
  instead of casts when possible.
