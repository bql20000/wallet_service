# 💳 Wallet Service

Requirements & System Design Document

*Phase 1 & 2 — Learning Project*

---

# Section 1 — Requirements

All requirements have been finalised through a requirements clarification
session. Decisions are deliberate and documented below.

## 1.1 Tech Stack

| Component | Choice | Notes |
| --- | --- | --- |
| Language | Kotlin 2.x | Primary backend language |
| Framework | Spring Boot 3.x | REST API + JPA |
| Database | PostgreSQL 16 | Primary data store |
| Build Tool | Gradle (Kotlin DSL) | `build.gradle.kts` |
| JDK | Amazon Corretto 21 | LTS, AWS-aligned |
| IDE | IntelliJ IDEA | Windows |

## 1.2 Wallet

- **Multi-wallet:** One user can have multiple wallets
- **Currency:** USD only at launch — schema designed to support multi-currency later
- **Balance floor:** Minimum 0, never negative. Enforced at both application and DB level

## 1.3 Top-up

- **Source:** External API — request carries `source_type` and `source_reference_id`
- **Idempotency:** Idempotent by `source_reference_id` — same reference credited only once
- **Limits:** No maximum amount per transaction or per day

## 1.4 Balance

- **Consistency:** Real-time — balance reflects all completed transactions immediately
- **What is returned:** Available balance only (pending bank transfer amounts are excluded)

## 1.5 Wallet-to-Wallet Transfer

- **Execution:** Instant and synchronous
- **Self-transfer:** Allowed — same owner, different wallets
- **Insufficient funds:** Hard fail — return error immediately
- **Fees:** None
- **Idempotency:** Idempotent by client-provided `idempotency_key`

## 1.6 Wallet-to-Bank Transfer

- **Bank integration:** Mock only — no real bank API
- **Response:** Returns `PENDING` immediately
- **Balance lock:** Amount deducted from available balance immediately on initiation
- **Pending usage:** Locked amount cannot be used by any subsequent action
- **Concurrent transfers:** Multiple in-flight bank transfers allowed simultaneously
- **On bank confirm:** Transfer completes — amount permanently gone
- **On bank reject:** Amount fully reversed back to available balance
- **Idempotency:** Idempotent by client-provided `idempotency_key`

## 1.7 Mock Bank Callback

- **Mechanism:** `POST /mock/bank/callback` endpoint simulates bank response
- **Idempotency:** Job status check inside a locked transaction — cannot process twice
- **Concurrency:** Row-level lock on `bank_transfer_jobs` prevents double-processing

## 1.8 General

- **Transaction history:** Not required at this stage (but ledger captures it implicitly)
- **Fraud / limits:** Not required
- **Event emission:** Not required

---

# Section 2 — System Design

## 2.1 API Design

Base URL: `/api/v1`

| Method | Endpoint | Description | Response |
| --- | --- | --- | --- |
| POST | `/wallets` | Create a new wallet | 201 |
| GET | `/wallets/{id}/balance` | Get current available balance | 200 |
| POST | `/wallets/{id}/topup` | Top up wallet balance | 200 |
| POST | `/transfers/wallet` | Wallet-to-wallet transfer | 200 |
| POST | `/transfers/bank` | Wallet-to-bank transfer | 202 PENDING |
| POST | `/mock/bank/callback` | Simulate bank response | 200 |

### Standard Error Response

```json
{
  "error_code": "INSUFFICIENT_FUNDS",
  "message": "Available balance is insufficient for this transfer",
  "timestamp": "2026-06-28T10:00:00Z"
}
```

### Key Error Codes

| Error Code | Trigger |
| --- | --- |
| `INSUFFICIENT_FUNDS` | Transfer amount exceeds available balance |
| `WALLET_NOT_FOUND` | Wallet ID does not exist |
| `DUPLICATE_REQUEST` | Idempotency key already processed — original response returned |
| `INVALID_AMOUNT` | Amount is zero or negative |
| `JOB_NOT_FOUND` | Bank transfer job ID not found in callback |
| `INVALID_JOB_STATUS` | Callback received for non-`PENDING` job |

## 2.2 Endpoint Contracts

### POST /wallets — Create Wallet

```jsonc
// Request
{  "user_id": "usr_123",  "currency": "USD"  }

// Response 201
{  "wallet_id": "wal_abc",  "user_id": "usr_123",
   "currency": "USD",  "available_balance": 0,
   "created_at": "2026-06-28T10:00:00Z"  }
```

### GET /wallets/{wallet_id}/balance — Get Balance

```jsonc
// Response 200
{  "wallet_id": "wal_abc",  "currency": "USD",
   "available_balance": 150.00  }
```

### POST /wallets/{wallet_id}/topup — Top Up

```jsonc
// Request
{  "amount": 100.00,  "source_type": "BANK_TRANSFER",
   "source_reference_id": "ext_ref_001"  }

// Response 200
{  "transaction_id": "txn_xyz",  "wallet_id": "wal_abc",
   "amount": 100.00,  "new_balance": 250.00,  "status": "COMPLETED"  }
```

### POST /transfers/wallet — Wallet-to-Wallet

```jsonc
// Request
{  "idempotency_key": "idem_001",
   "from_wallet_id": "wal_abc",  "to_wallet_id": "wal_def",
   "amount": 50.00  }

// Response 200
{  "transfer_id": "txn_yyy",  "from_wallet_id": "wal_abc",
   "to_wallet_id": "wal_def",  "amount": 50.00,  "status": "COMPLETED"  }
```

### POST /transfers/bank — Wallet-to-Bank

```jsonc
// Request
{  "idempotency_key": "idem_002",  "from_wallet_id": "wal_abc",
   "amount": 75.00,  "bank_account_number": "1234567890",
   "bank_code": "DBS"  }

// Response 202
{  "transfer_id": "txn_zzz",  "from_wallet_id": "wal_abc",
   "amount": 75.00,  "status": "PENDING",
   "created_at": "2026-06-28T10:05:00Z"  }
```

### POST /mock/bank/callback

```jsonc
// Request
{  "bank_transfer_job_id": "job_123",
   "result": "COMPLETED"  }  // or REJECTED

// Response 200
{  "status": "OK"  }
```

## 2.3 Database Schema

> Core principle: **double-entry ledger**. Money never just appears or
> disappears — every movement is recorded as an immutable ledger entry.
> `available_balance` is a cached running total always updated in the same DB
> transaction as the ledger entry.

### Table: wallets

```sql
CREATE TABLE wallets (
  id                UUID PRIMARY KEY,
  user_id           VARCHAR(64) NOT NULL,
  currency          VARCHAR(3) NOT NULL DEFAULT 'USD',
  available_balance NUMERIC(18,2) NOT NULL DEFAULT 0,
  version           BIGINT NOT NULL DEFAULT 0,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT chk_balance_non_negative CHECK (available_balance >= 0)
);
CREATE INDEX idx_wallets_user_id ON wallets(user_id);
```

> `version` column is kept for potential optimistic locking in future. The
> `CHECK` constraint is the last line of defence — negligible performance cost,
> prevents corruption even if application logic has a bug.

### Table: ledger_entries

```sql
CREATE TABLE ledger_entries (
  id               UUID PRIMARY KEY,
  wallet_id        UUID NOT NULL REFERENCES wallets(id),
  entry_type       VARCHAR(30) NOT NULL,
  amount           NUMERIC(18,2) NOT NULL,
  balance_after    NUMERIC(18,2) NOT NULL,
  reference_id     VARCHAR(128),
  idempotency_key  VARCHAR(128),
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_idempotency UNIQUE (wallet_id, idempotency_key)
);
CREATE INDEX idx_ledger_wallet_id ON ledger_entries(wallet_id);
CREATE INDEX idx_ledger_reference_id ON ledger_entries(reference_id);
```

### Entry Types

| entry_type | Triggered by |
| --- | --- |
| `TOPUP` | Successful top-up |
| `TRANSFER_OUT` | Sender side of wallet-to-wallet transfer |
| `TRANSFER_IN` | Receiver side of wallet-to-wallet transfer |
| `BANK_TRANSFER_OUT` | Wallet-to-bank transfer initiated |
| `BANK_TRANSFER_REVERSAL` | Bank rejects transfer — amount returned |

> `reference_id` links the two sides of a wallet-to-wallet transfer
> (`TRANSFER_OUT` + `TRANSFER_IN` share the same `reference_id`).
> `balance_after` stores a snapshot for instant historical balance lookup and
> corruption detection.

### Table: bank_transfer_jobs

```sql
CREATE TABLE bank_transfer_jobs (
  id                  UUID PRIMARY KEY,
  wallet_id           UUID NOT NULL REFERENCES wallets(id),
  ledger_entry_id     UUID NOT NULL REFERENCES ledger_entries(id),
  amount              NUMERIC(18,2) NOT NULL,
  bank_account_number VARCHAR(64) NOT NULL,
  bank_code           VARCHAR(20) NOT NULL,
  status              VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  idempotency_key     VARCHAR(128) NOT NULL,
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_bank_idempotency UNIQUE (wallet_id, idempotency_key)
);
CREATE INDEX idx_bank_jobs_status ON bank_transfer_jobs(status);
```

## 2.4 Concurrency Design — Pessimistic Locking

> Strategy: pessimistic locking (`SELECT ... FOR UPDATE`). Chosen over
> optimistic locking because financial correctness matters more than raw
> throughput, and lock contention per-wallet is negligible in practice.

### General Lock Pattern

Every operation that mutates `available_balance` follows this exact pattern:

```sql
BEGIN TRANSACTION
  SELECT * FROM wallets WHERE id = ? FOR UPDATE   -- lock the row
  -- validate business rules (balance check etc.)
  UPDATE wallets SET available_balance = ... WHERE id = ?
  INSERT INTO ledger_entries (...)
COMMIT
```

### Deadlock Prevention — Sorted Lock Order

Wallet-to-wallet transfers lock two rows. To prevent deadlocks, wallets are
always locked in ascending UUID order, regardless of transfer direction:

```kotlin
val (firstId, secondId) = if (fromWalletId < toWalletId) {
    fromWalletId to toWalletId
} else {
    toWalletId to fromWalletId
}
val firstWallet  = walletRepository.findByIdForUpdate(firstId)
val secondWallet = walletRepository.findByIdForUpdate(secondId)
```

> This ensures two concurrent transfers in opposite directions between the same
> wallets always acquire locks in the same order — eliminating circular waits.

### Operations Requiring a Lock

| Operation | Wallets Locked | Notes |
| --- | --- | --- |
| Top-up | Target wallet | Single lock |
| Wallet-to-wallet transfer | Both wallets (sorted order) | Deadlock-safe dual lock |
| Wallet-to-bank transfer | Source wallet | Single lock — deducts immediately |
| Bank callback (COMPLETED) | None | Balance already deducted |
| Bank callback (REJECTED) | Source wallet + job row | Reversal requires lock |

## 2.5 Idempotency Design

> Core principle: the database unique constraint is the real safety net, not
> application-level checks. Application checks first but the DB constraint
> handles races.

| Operation | Idempotency Key | Duplicate Behaviour |
| --- | --- | --- |
| Top-up | `source_reference_id` per wallet | Return original response |
| Wallet-to-wallet | `idempotency_key` per wallet | Return original response |
| Wallet-to-bank | `idempotency_key` per wallet | Return original response |
| Bank callback | Job status check (inside lock) | Return OK, do nothing |

### Race Condition Handling

```kotlin
// Two identical requests arrive simultaneously
try {
    // perform transfer + insert ledger entries
} catch (e: DataIntegrityViolationException) {
    // unique constraint on (wallet_id, idempotency_key) violated
    // fetch and return the original response
}
```

## 2.6 Mock Bank Callback — Full Flow

### COMPLETED callback

```sql
BEGIN TRANSACTION
  SELECT * FROM bank_transfer_jobs WHERE id = ? FOR UPDATE
  IF status != PENDING: return error (already processed)
  UPDATE job.status = COMPLETED
  -- balance was already deducted at initiation, nothing more to do
COMMIT
```

### REJECTED callback

```sql
BEGIN TRANSACTION
  SELECT * FROM bank_transfer_jobs WHERE id = ? FOR UPDATE
  IF status != PENDING: return error (already processed)
  SELECT * FROM wallets WHERE id = ? FOR UPDATE   -- lock wallet
  UPDATE wallet.available_balance += amount       -- reverse
  INSERT INTO ledger_entries (BANK_TRANSFER_REVERSAL, reference_id = job.ledger_entry_id)
  UPDATE job.status = REJECTED
COMMIT
```

> Two concurrent callbacks for the same job: `FOR UPDATE` on the job row means
> only one can read `PENDING`. The second waits, then sees
> `REJECTED`/`COMPLETED` and returns early. No double-reversal possible.

## 2.7 Architecture Overview

```text
HTTP Request
    ↓
Spring Boot Controller
    ↓
Service Layer  (business logic, locking, idempotency)
    ↓
Repository Layer  (JPA + @Lock(PESSIMISTIC_WRITE))
    ↓
PostgreSQL  (wallets + ledger_entries + bank_transfer_jobs)

Later phases:
    ↓
OpenTelemetry  (tracing per request)
    ↓
Prometheus  (metrics scraping)
    ↓
Grafana  (dashboards + alerts)
```

## 2.8 Phase 3 — Development Plan

| Step | What |
| --- | --- |
| 1 | Project scaffolding — Spring Initializr, dependencies, folder structure |
| 2 | Entities + repositories — Wallet, LedgerEntry, BankTransferJob (JPA) |
| 3 | Create wallet + get balance — skeleton running end-to-end |
| 4 | Top-up — introduces idempotency pattern |
| 5 | Wallet-to-wallet transfer — locking + dual ledger entries |
| 6 | Wallet-to-bank transfer + mock callback — async job pattern |
| 7 | Tests — unit tests + integration tests for concurrency |
| 8 | Docker Compose — app + Postgres running locally |
| 9 | Tracing — OpenTelemetry, trace every transaction end-to-end |
| 10 | Metrics — Prometheus scraping + Grafana dashboards |

---

*End of Document — Phase 1 & 2*
