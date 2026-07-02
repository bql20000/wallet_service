# Wallet Service

Demo project to learn Kotlin & Spring Boot: a wallet/ledger backend
supporting top-ups, wallet-to-wallet transfers, and wallet-to-bank transfers
(mocked). Full requirements and system design live in
`docs/wallet-service-design.docx` — this file summarizes it so day-to-day
work doesn't require opening the doc.

**Current status:** early scaffold only (default Spring Initializr output — a
single `WalletServiceApplication.kt` and no domain code yet). Treat everything
below as the target design to build toward, not existing behavior.

## Tech Stack

| Component | Choice |
|---|---|
| Language | Kotlin 2.x |
| Framework | Spring Boot 3.x — REST API + Spring Data JDBC |
| Database | PostgreSQL 16 (H2 acceptable for local/dev in the scaffold) |
| Build tool | Gradle (Kotlin DSL) |
| JDK | Amazon Corretto 21 (LTS) |

Spring Data JDBC was chosen over JPA/Hibernate deliberately: for a ledger
service, explicit and predictable SQL under pessimistic locking matters more
than ORM convenience. JPA's dirty-checking, lazy loading, and persistence
context introduce non-obvious flush/query timing — a real risk when
correctness of `SELECT ... FOR UPDATE` locking is critical. JDBC repository
methods map directly to the SQL actually executed, with no hidden
autoflush/caching behavior.

## Core Requirements

- **Wallets:** one user can have multiple wallets. USD only at launch (schema
  should allow multi-currency later). Balance floor is 0 — never negative,
  enforced at both application and DB level.
- **Top-up:** external API call carries `source_type` + `source_reference_id`.
  Idempotent by `source_reference_id` (same reference credited once). No
  per-transaction or per-day limits.
- **Balance:** always real-time/consistent with completed transactions. Only
  the *available* balance is returned (pending bank transfers excluded).
- **Wallet-to-wallet transfer:** instant, synchronous. Self-transfer between
  a user's own wallets is allowed. Insufficient funds hard-fails immediately.
  No fees. Idempotent by client-provided `idempotency_key`.
- **Wallet-to-bank transfer:** bank integration is mocked (no real bank API).
  Returns `PENDING` immediately; amount is deducted from available balance at
  initiation (locked, unusable until resolved). Multiple in-flight bank
  transfers are allowed concurrently. Bank confirm → amount permanently gone.
  Bank reject → amount fully reversed. Idempotent by `idempotency_key`.
- **Mock bank callback:** `POST /mock/bank/callback` simulates the bank's
  response. Must not double-process — job status is checked inside a locked
  transaction with a row-level lock on `bank_transfer_jobs`.
- Out of scope for this phase: transaction history endpoint (ledger captures
  it implicitly), fraud/limits checks, event emission.

## API (base URL `/api/v1`)

| Method | Endpoint | Description | Response |
|---|---|---|---|
| POST | `/wallets` | Create a new wallet | 201 |
| GET | `/wallets/{id}/balance` | Get current available balance | 200 |
| POST | `/wallets/{id}/topup` | Top up wallet balance | 200 |
| POST | `/transfers/wallet` | Wallet-to-wallet transfer | 200 |
| POST | `/transfers/bank` | Wallet-to-bank transfer | 202 PENDING |
| POST | `/mock/bank/callback` | Simulate bank response | 200 |

Error responses: `{ "error_code", "message", "timestamp" }`. Key codes:
`INSUFFICIENT_FUNDS`, `WALLET_NOT_FOUND`, `DUPLICATE_REQUEST`,
`INVALID_AMOUNT`, `JOB_NOT_FOUND`, `INVALID_JOB_STATUS`.

## Database Schema

Core principle: **double-entry ledger** — money never just appears or
disappears; every movement is an immutable `ledger_entries` row.
`available_balance` on `wallets` is a cached running total, always updated in
the same DB transaction as the ledger entry.

- **`wallets`** — `id`, `user_id`, `currency`, `available_balance`
  (`CHECK >= 0`), `version` (reserved for future optimistic locking),
  timestamps.
- **`ledger_entries`** — `id`, `wallet_id`, `entry_type`, `amount`,
  `balance_after` (snapshot for fast history/corruption checks),
  `reference_id` (links the two sides of a wallet-to-wallet transfer —
  `TRANSFER_OUT` + `TRANSFER_IN` share one `reference_id`),
  `idempotency_key`, with `UNIQUE (wallet_id, idempotency_key)`.
  Entry types: `TOPUP`, `TRANSFER_OUT`, `TRANSFER_IN`,
  `BANK_TRANSFER_OUT`, `BANK_TRANSFER_REVERSAL`.
- **`bank_transfer_jobs`** — tracks mock bank transfer state (`PENDING` →
  `COMPLETED`/`REJECTED`), `UNIQUE (wallet_id, idempotency_key)`.

## Concurrency: Pessimistic Locking

Chosen over optimistic locking — financial correctness matters more than raw
throughput, and per-wallet contention is expected to be negligible.

Every balance-mutating operation follows: `SELECT ... FOR UPDATE` → validate
→ `UPDATE wallets` → `INSERT ledger_entries`, all in one transaction. With
Spring Data JDBC, this is a repository method with a hand-written
`@Query("... FOR UPDATE")` (no JPA `@Lock` annotation) inside a
`@Transactional` service method.

**Deadlock prevention:** wallet-to-wallet transfers lock both wallets in
ascending UUID order regardless of transfer direction, so two concurrent
transfers between the same pair of wallets always acquire locks in the same
order.

| Operation | Wallets locked |
|---|---|
| Top-up | Target wallet |
| Wallet-to-wallet transfer | Both (sorted UUID order) |
| Wallet-to-bank transfer | Source wallet |
| Bank callback (COMPLETED) | None — balance already deducted |
| Bank callback (REJECTED) | Source wallet + job row |

## Idempotency

The **DB unique constraint is the real safety net**, not the application-level
check — the app checks first (fast path), but concurrent duplicate requests
are ultimately resolved by catching `DataIntegrityViolationException` on the
`(wallet_id, idempotency_key)` unique constraint and returning the original
response.

| Operation | Idempotency key |
|---|---|
| Top-up | `source_reference_id` per wallet |
| Wallet-to-wallet / wallet-to-bank | client `idempotency_key` per wallet |
| Bank callback | job status check inside the row lock |

## Architecture

```
HTTP Request
  → Spring Boot Controller
  → Service Layer (business logic, locking, idempotency)
  → Repository Layer (Spring Data JDBC + `SELECT ... FOR UPDATE` queries)
  → PostgreSQL (wallets + ledger_entries + bank_transfer_jobs)
```

Later phases add OpenTelemetry tracing, Prometheus metrics, and Grafana
dashboards — not required for the current build-out.

## Build Plan (Phase 3)

1. Project scaffolding
2. Entities + repositories (`Wallet`, `LedgerEntry`, `BankTransferJob`)
3. Create wallet + get balance — end-to-end skeleton
4. Top-up — introduces the idempotency pattern
5. Wallet-to-wallet transfer — dual locking + dual ledger entries
6. Wallet-to-bank transfer + mock callback — async job pattern
7. Tests — unit + concurrency integration tests
8. Docker Compose — app + Postgres locally
9. Tracing (OpenTelemetry)
10. Metrics (Prometheus + Grafana)
