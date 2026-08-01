# Kotlin & Spring Concepts — Refresher

A personal cheatsheet for resuming this project. Covers the Kotlin syntax you'll
write for the API and the Spring concepts (beans, DI, transactions, locking)
that aren't visible from just reading the code.

---

## Part 1 — Kotlin syntax

### `val` vs `var`
```kotlin
val walletId = 42L   // read-only (Java final). Default choice.
var balance = 0L     // reassignable. Use only when you must mutate.
```
Prefer `val`; switch to `var` only when the compiler forces you. Types are
inferred but can be explicit: `val walletId: Long = 42`.

### Null safety
Types are non-null by default; `?` makes them nullable. Kills most NPEs.
```kotlin
val name: String  = "Main Wallet"  // never null
val note: String? = null           // null allowed

note?.length          // safe call → null if note is null
note?.length ?: 0     // elvis → fallback if null
note!!.length         // force-unwrap → throws if null. Avoid.
```
You'll use this on repo lookups: `repo.findById(id) ?: throw NotFound()`.

### Data classes = your entities
Auto-generates `equals`/`hashCode`/`toString`/`copy`. Constructor params with
`val` **are** the fields.
```kotlin
@Table("wallets")
data class Wallet(
    @Id val id: Long? = null,      // null before insert; DB assigns identity
    val userId: Long,
    val name: String,
    val currency: String = "USD",  // default value
    val availableBalance: Long = 0,
    val version: Long = 0,
)
```
Entities are immutable `val`s → "change" them with `copy`:
```kotlin
val credited = wallet.copy(availableBalance = wallet.availableBalance + amount)
```

### Functions
```kotlin
fun add(a: Long, b: Long): Long { return a + b }
fun add(a: Long, b: Long) = a + b            // single-expression, type inferred
```
Named + default args: `topup(walletId = 42, amount = 100)`.

### `if` / `when` are expressions (return values)
```kotlin
val fee = if (amount > 1000) 10 else 0

val type = when (op) {
    "topup"    -> "TOPUP"
    "transfer" -> "TRANSFER_OUT"
    else       -> throw IllegalArgumentException("unknown op")
}
```
`when` over an enum/sealed type is exhaustive — no `else` needed if all cases
are covered.

### Money note
Balances are `BIGINT` (integer minor units / cents) → use `Long`. Never
`Double` for money; `BigDecimal` only if you later need fractional units.

---

## Part 2 — Spring: beans, DI, and startup (the invisible part)

This is the stuff you can't see by reading the code — Spring does it at startup.

### What a "bean" is
A **bean** is just an object that **Spring creates and manages for you**,
instead of you calling `new`/the constructor yourself. At startup Spring builds
one instance of each of your annotated classes and keeps them in a registry
called the **application context**. Each managed instance = one bean.

Classes become beans via stereotype annotations:
- `@RestController` — HTTP layer
- `@Service` — business logic
- `@Repository` — data access (interfaces; see below)

### Dependency Injection (DI) / Inversion of Control (IoC)
**Problem:** if a class builds its own dependencies, it's hard-wired to one
implementation and impossible to test with a fake.
```kotlin
class WalletService {
    private val repo = WalletRepositoryImpl()  // BAD: creates its own tool
}
```
**Fix:** the class *declares what it needs* in its constructor and lets Spring
*hand it in* (this is "injection"; the control is "inverted" from the class to
Spring):
```kotlin
@Service
class WalletService(
    private val walletRepository: WalletRepository,  // "give me one of these"
)
```
`private val` in the constructor = an injected, stored dependency. No
`@Autowired` needed — this is **constructor injection**, the recommended form.

### What Spring does at startup (the mental model)
```
1. Scans for @RestController / @Service / @Repository classes.
2. For repository interfaces, GENERATES an implementation at runtime (a proxy)
   and registers it as a bean.
3. Builds each bean. When a constructor needs another bean, Spring finds the
   already-built one and passes it in (the injection).
   e.g. WalletService needs WalletRepository → Spring hands in the repo bean.
       WalletController needs WalletService → Spring hands in the service bean.
4. Everything lives in the application context, wired together, ready to serve.
```
You never call `WalletService(...)` yourself. In **tests**, you can: pass a fake
repo → `WalletService(fakeRepo)`. That testability is the whole payoff of DI.

### Repositories — what they are
A **repository** is a design pattern: an object that acts like an in-memory
collection of domain objects, hiding the database. You think in objects
(`repo.save(wallet)`, `repo.findById(42)`), not tables/rows.

You write only an **interface** and never implement it — Spring generates the
implementation at runtime (Proxy pattern) and registers it as a bean.
`CrudRepository` already declares `save`, `findById`, `deleteById`, etc., so
extending it gives you those for free:
```kotlin
interface WalletRepository : CrudRepository<Wallet, Long> {
    @Query("SELECT * FROM wallets WHERE id = :id FOR UPDATE")
    fun findByIdForUpdate(id: Long): Wallet?
}
```

### ORM vs Spring Data JDBC (why this project chose JDBC)
- **ORM (JPA/Hibernate):** maps objects↔tables **plus** magic — change tracking
  ("dirty checking"), caching, lazy loading. Convenient, but the SQL that runs
  and *when* it runs is hidden.
- **Spring Data JDBC (this project):** leaner. Repository pattern + row→data-class
  mapping, but no change-tracking, no cache, no lazy loading. **What you call is
  what runs.** Chosen because `SELECT ... FOR UPDATE` locking needs predictable
  SQL; an ORM's hidden flush timing is dangerous for a ledger.

Repository = the *pattern*; ORM and Spring Data JDBC = two implementations with
different amounts of magic.

---

## Part 3 — Enums

An enum is a type whose values are a **fixed named list you define**. The type
is the enum name; the constants are the only allowed values.
```kotlin
enum class JobStatus { PENDING, COMPLETED, REJECTED }
val status: JobStatus = JobStatus.PENDING  // type = JobStatus, value = PENDING
```
Win over `String`: `JobStatus.PENDIGN` (typo) won't compile; `"PENDIGN"` would
compile and fail at runtime. `when` over an enum is completeness-checked.

**Enums can carry data** — each constant can hold properties:
```kotlin
enum class EntryType(val sign: Int) {
    TOPUP(+1), TRANSFER_IN(+1),
    TRANSFER_OUT(-1), BANK_TRANSFER_OUT(-1),
    BANK_TRANSFER_REVERSAL(+1);

    fun apply(balance: Long, amount: Long) = balance + sign * amount
}
```
`EntryType.TOPUP.sign` = `+1`, `EntryType.TRANSFER_OUT.sign` = `-1`. The entry
type itself knows whether it adds to or subtracts from the balance. Built-ins:
`.name` (`"TOPUP"`), `.ordinal` (position).

---

## Part 4 — Exceptions

How a function says "I can't continue" and jumps control up to a handler. No
checked exceptions in Kotlin — you never declare `throws`, and catch only what
you want.

**Define your own (one per error code):**
```kotlin
class WalletNotFoundException(id: Long) :
    RuntimeException("Wallet $id not found")               // $id = interpolation
class InsufficientFundsException(walletId: Long, needed: Long, have: Long) :
    RuntimeException("Wallet $walletId needs $needed, has $have")
```

**Throw** (rest of the function is abandoned the instant `throw` runs):
```kotlin
val from = repo.findByIdForUpdate(fromId) ?: throw WalletNotFoundException(fromId)
if (from.availableBalance < amount)
    throw InsufficientFundsException(fromId, amount, from.availableBalance)
```

**Stdlib shortcuts** (pre-packaged throws):
```kotlin
require(amount > 0) { "INVALID_AMOUNT" }   // IllegalArgumentException if false — validate INPUTS
checkNotNull(found) { "WALLET_NOT_FOUND" } // IllegalStateException if null — validate STATE
```

**Catch** — `try { risky } catch (e: SomeType) { recovery }`. Your idempotency
race path:
```kotlin
return try {
    doTopup(walletId, amount, referenceId)      // DB enforces UNIQUE(wallet_id, idempotency_key)
} catch (e: DataIntegrityViolationException) {
    findExistingTopupResult(walletId, referenceId)  // duplicate lost the race → return original
}
```
In Spring, exceptions you *don't* catch bubble up to a global `@ExceptionHandler`
that formats them into your `{ error_code, message, timestamp }` JSON — so mostly
you just throw and let Spring format.

---

## Part 5 — `@Transactional` & the locking flow (core of this project)

### 1. What a transaction is
A group of SQL statements treated as **all-or-nothing**: all commit together, or
all roll back. Non-negotiable for the ledger — a balance update and its ledger
row must land together, or you get "money from nowhere."

### 2. What `@Transactional` does
On a service method: Spring opens a transaction on entry, **commits on normal
return, rolls back on a thrown exception**. You never write BEGIN/COMMIT/ROLLBACK.
```kotlin
@Transactional
fun topup(...): Wallet {
    // everything here runs in ONE transaction
    // if any throw happens → automatic rollback, nothing persists
}
```
**Gotcha:** `@Transactional` works via a proxy (same mechanism as repositories).
It only activates when called from *outside* the class. A method self-calling
another `@Transactional` method on `this` **skips the proxy → annotation
ignored.** Must be called from a different bean (controller → service). No
self-invocation.

### 3. What `FOR UPDATE` (the lock) does
A transaction alone does NOT stop concurrent transactions from clobbering each
other. Two top-ups both read balance 100, both write → one update lost (**race
condition**).

`SELECT ... FOR UPDATE` takes a **row lock**. The second transaction to request
it on the same row **blocks until the first commits**, then reads the fresh
value. This is **pessimistic locking** — lock upfront, assume conflict. Chosen
over optimistic because for money correctness > throughput, and wallet
contention is low.
```kotlin
@Query("SELECT * FROM wallets WHERE id = :id FOR UPDATE")
fun findByIdForUpdate(id: Long): Wallet?
```

### 4. The universal recipe
Every balance-mutating op, inside one `@Transactional` method:
```
lock (FOR UPDATE) → validate → update balance → insert ledger entry → commit
```
**Order matters: lock BEFORE you validate** — otherwise you'd check a number
another transaction could change before you write.
```kotlin
@Transactional
fun topup(walletId: Long, amount: Long, referenceId: String): Wallet {
    require(amount > 0) { "INVALID_AMOUNT" }
    val wallet = walletRepository.findByIdForUpdate(walletId)   // 1. LOCK
        ?: throw WalletNotFoundException(walletId)              // 2. validate
    val newBalance = wallet.availableBalance + amount          // 3. update
    walletRepository.save(wallet.copy(availableBalance = newBalance))
    ledgerRepository.save(LedgerEntry(                         // 4. ledger row
        walletId = walletId, entryType = EntryType.TOPUP,
        amount = amount, balanceAfter = newBalance,
        idempotencyKey = referenceId,
    ))
    return wallet.copy(availableBalance = newBalance)
}   // commit: balance update + ledger insert land together, lock releases
```

### 5. Tricky operations
**Wallet-to-wallet — two locks + deadlock trap.** Locking two rows risks
deadlock: transfer X (42→99) locks 42 waits 99; transfer Y (99→42) locks 99
waits 42 → neither proceeds. **Fix: always lock in ascending `id` order**,
regardless of direction. Both lock 42 then 99; no cycle.
```kotlin
val (firstId, secondId) = if (fromId < toId) fromId to toId else toId to fromId
val first  = walletRepository.findByIdForUpdate(firstId)  ?: throw WalletNotFoundException(firstId)
val second = walletRepository.findByIdForUpdate(secondId) ?: throw WalletNotFoundException(secondId)
// then map first/second back to from/to, validate, write two balances +
// two ledger rows (TRANSFER_OUT + TRANSFER_IN) sharing one reference_id
```

**Bank callback — lock the job row, not a balance.** Lock the
`bank_transfer_jobs` row `FOR UPDATE`, check its status *inside the lock*, act
only if still `PENDING`. A duplicate callback blocks, then sees `COMPLETED` and
does nothing. On REJECTED, also lock the source wallet to reverse funds. On
COMPLETED, no extra lock (money already deducted at initiation).

### Mental model to keep
`@Transactional` = all-or-nothing grouping. `FOR UPDATE` = serialize concurrent
access to a row. **Lock before you validate. Lock multiple rows in a fixed
order** to avoid deadlock. That one recipe covers every write path.
