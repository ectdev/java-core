# java-core

[![CI](https://github.com/ectdev/java-core/actions/workflows/ci.yml/badge.svg)](https://github.com/ectdev/java-core/actions/workflows/ci.yml)
![Java 21](https://img.shields.io/badge/Java-21-informational)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

Core Java rebuilt from first principles. Each module takes something every Java developer uses, builds it from
scratch, and then tries to break it: with answers worked out by hand, with random inputs compared against an
independent oracle, and with deliberately broken copies of the code to see whether the tests notice.

This is a reference and a workbench, not a library to depend on. Where the JDK already has the thing, the JDK's
version is the one to use in real code. What is here is the reasoning, and the evidence that it is right.

## Modules

| Module | What is built | What it is checked against | Tests | Mutants |
| --- | --- | --- | ---: | ---: |
| [`collections`](collections/README.md) | A dynamic array, a chained hash map, an LRU cache and a binary heap | `ArrayList`, `HashMap`, `LinkedHashMap` and `PriorityQueue`, over random operation sequences | 128 | 24 |
| [`concurrency`](concurrency/README.md) | A bounded blocking buffer, a token-bucket rate limiter and a compute-once memoizer | A fake clock, thread-state waits instead of sleeps, a conservation property under contention, and a race harness proven able to fail | 53 | 24 |
| [`json`](json/README.md) | A strict RFC 8259 parser and writer on sealed types, and RFC 6901 JSON Pointer | The RFCs' own examples and tables, hand-counted error positions, seeded round trips and damaged-text fuzzing | 221 | 63 |
| [`ledger`](ledger/README.md) | Money in integer minor units with exact allocation, a FIFO lot book, and a trade timeline that refuses to go short | Hand-worked cases, a unit-by-unit oracle that shares no arithmetic, and conservation laws where rounding happens | 171 | 48 |

"Mutants" is the number of deliberately broken copies of the code that the tests are required to notice; every one is
caught. Test counts are a snapshot (`./mvnw verify` prints the current ones).

## Build and test

Requirements: JDK 21 or newer. Maven is not needed; the wrapper downloads the pinned version (3.9.11).

```sh
./mvnw verify                 # compile with every lint warning as an error, then run all tests
./mvnw -pl json test          # one module
```

On Windows use `mvnw.cmd`. If `java -version` reports something older than 21, the build stops at once with a
message saying so (a Maven Enforcer rule), instead of failing later on a language feature.

## How the repository is kept honest

* **Warnings are errors.** The compiler runs with `-Xlint:all -Werror`. A raw type or an unchecked cast must be
  fixed, or suppressed in place with the reason next to it.
* **No dependencies.** The only dependency anywhere is JUnit, for tests. Nothing is on the runtime classpath.
* **Randomness is seeded.** Every randomised test uses fixed seeds, so a failure reproduces on any machine.
* **The Java 21 API surface is enforced.** The code is compiled with `--release 21`, and CI builds on JDK 21 and
  on JDK 25, so a pass on 25 also shows nothing newer than 21 leaked in.
* **Third-party GitHub Actions are pinned to a commit**, not a movable tag.
* **The tests are tested.** [`tools/mutate.py`](tools/README.md) breaks the code on purpose and reports any test
  suite that fails to notice.

## Layout

```text
pom.xml                 parent: Java release, lint flags, JUnit, plugin versions
<module>/               one directory per module: src/main, src/test, README.md
tools/mutate.py         the mutation checker; tools/mutants/ holds the broken variants
.github/workflows/      CI
```

## License

[MIT](LICENSE)
