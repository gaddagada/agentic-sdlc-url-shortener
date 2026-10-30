# ADR 0010: Java 25 LTS and Spring Boot 4.1 as the baseline

Status: Accepted (2026-10-29)

## Context

Options were Java 21 + Spring Boot 3.5 (the last 3.x line, whose open-source support window has
ended) or Java 25 + Spring Boot 4.1 (current generation: Spring Framework 7, Jakarta EE 11, Jackson 3,
Hibernate 7, modularized starters).

## Decision

Java 25 (LTS, virtual threads mature) and Spring Boot 4.1.2.

## Consequences

- Current, supported baseline; virtual threads are used for stage execution and the servlet container.
- Boot 4 specifics are visible in the code: `spring-boot-starter-webmvc`, test-slice starters,
  `@MockitoBean`, Jackson 3 (`tools.jackson`), `@AutoConfigureMockMvc` with `@SpringBootTest`.
- Some third-party libraries lag; the project limits itself to libraries with Boot 4 compatible
  releases (springdoc 3.x, Caffeine 3, ArchUnit 1.5, Anthropic Java SDK 3.x).
