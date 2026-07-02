# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

Kotlin + Spring Boot wallet/ledger backend (learning project). The full
requirements and system design live in `README.md` (originally authored in
`docs/wallet-service-design.docx`) — read it before implementing any domain
logic. It encodes deliberate, already-decided architectural choices (double-entry
ledger, pessimistic locking, DB-constraint-based idempotency, Spring Data JDBC
over JPA) rather than open design space, so don't re-derive or second-guess
them without reason.

**Status:** early scaffold — only `WalletServiceApplication.kt` exists under
`src/main/kotlin/com/example/wallet_service/`; no domain code (entities,
repositories, controllers) has been written yet.

## Commands

- Build: `./gradlew build` (Windows: `gradlew.bat build`)
- Run: `./gradlew bootRun`
- Run all tests: `./gradlew test`
- Run a single test class: `./gradlew test --tests "com.example.wallet_service.WalletServiceApplicationTests"`
- Run a single test method: `./gradlew test --tests "com.example.wallet_service.WalletServiceApplicationTests.contextLoads"`
- Clean build output: `./gradlew clean`

Toolchain: JDK 21 (Amazon Corretto), Gradle Kotlin DSL (`build.gradle.kts`),
Kotlin 2.3.21, Spring Boot 4.1.0. Tests run on JUnit 5 (`useJUnitPlatform()`).

## Architecture

- **Persistence is Spring Data JDBC, deliberately not JPA/Hibernate** — see
  README.md's "Tech Stack" section for the rationale (predictable, explicit
  SQL under pessimistic locking vs. ORM flush/cache surprises). Repository
  locking must use hand-written `@Query(... FOR UPDATE)`, not a JPA `@Lock`
  annotation.
- **Core data model is a double-entry ledger.** `wallets.available_balance` is
  a cached running total that must only ever be mutated inside the same DB
  transaction as an insert into `ledger_entries`. Never update a balance
  without a corresponding ledger row.
- **Concurrency control is pessimistic row locking** (`SELECT ... FOR UPDATE`)
  taken on every wallet involved in a balance mutation, before validation.
  Wallet-to-wallet transfers lock both wallets in ascending UUID order
  (regardless of transfer direction) to prevent deadlocks. The exact lock
  scope per operation (top-up, wallet transfer, bank transfer, bank callback)
  is tabulated in README.md's "Concurrency" section.
- **Idempotency relies on DB unique constraints** as the real safety net
  (`UNIQUE(wallet_id, idempotency_key)`), not just an application-level
  pre-check — the race-safe path is catching
  `DataIntegrityViolationException` and returning the original response.
- Current build dependencies: `spring-boot-starter-webmvc`,
  `spring-boot-starter-data-jdbc`, `spring-boot-h2console` + H2 runtime (local
  dev/test only — the target database is PostgreSQL 16 per the design doc),
  `jackson-module-kotlin`.
- Full API contracts, the `wallets` / `ledger_entries` / `bank_transfer_jobs`
  schema, error codes, and the phased build plan (Phase 3, steps 1–10) are in
  README.md — consult it rather than re-deriving the design from scratch.
