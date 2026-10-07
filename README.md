# java-core

[![CI](https://github.com/ectdev/java-core/actions/workflows/ci.yml/badge.svg)](https://github.com/ectdev/java-core/actions/workflows/ci.yml)
![Java 21](https://img.shields.io/badge/Java-21-informational)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

Core Java rebuilt from first principles: each module takes something every Java developer uses, builds it from
scratch, and then tries to break it.

## Build and test

Requirements: JDK 21 or newer. Maven is not needed; the wrapper downloads the pinned version (3.9.11).

```sh
./mvnw verify
```

## License

[MIT](LICENSE)
