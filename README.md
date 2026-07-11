# How to build and run

This is a **library module** (`formwork-channel-sms`), not a standalone
service — there is no `spring-boot-maven-plugin` in the POM, so there is no
executable jar and no `mvn spring-boot:run` target. It's meant to be built,
tested, and consumed by a parent application (see the commented-out
`formwork-base-tenant` dependency).

### Prerequisites

- JDK 21
- Maven 3.9+ (use `mvn` — no wrapper is committed in this repo)
- A reachable PostgreSQL instance for tests that exercise the JPA/Flyway
  path (connection settings in `src/test/resources/application-test.yml`).
  [Note the exact way you provided one — local install, Docker container,
  whatever you actually used, so it's reproducible.]

### Build

    mvn clean install

### Run tests

    mvn test

This runs the full suite, including the `*WireMockTest` classes, which spin
up an embedded WireMock server per test rather than hitting real provider
endpoints.

### Run tests with coverage

    mvn clean test jacoco:report

Report is written to `target/site/jacoco/index.html`. [Add a note here once
you've wired up proper `<executions>` for the JaCoCo plugin — as configured
in the original POM, coverage isn't bound to any phase automatically.]

# what's done, what's cut

### DONE: Cost recording was never triggered

**Problem:** `SmsCostService` (cost calculation, persistence, per-provider
rates) was fully built but never called anywhere. `SmsChannelService.sendSms()`
sent the message and returned — no cost was ever recorded, for any provider,
ever.

**Fix:** `SmsChannelService` now calls `SmsCostService.recordCost(tenantId,
recipient, result)` after a successful send. Cost-recording failures are
caught and logged rather than thrown, so a DB error can't turn an SMS that
was actually delivered into a reported failure.

**Proof:** see `SmsChannelServiceTest#sendSms_recordsCostOnSuccess` — fails
on original code, passes after the fix (commit `6a1e0c56971455c45066e599bb67fd2891824f76`).

### A Honest integration test

**What I found:** the existing `*WireMockTest` files (e.g.
`BudgetSmsGatewayWireMockTest`) are misnamed — they mock every step of
`WebClient`'s fluent API (`webClient.post()`, `.uri()`, `.body()`, etc.) via
Mockito and reflection-inject the mock into the gateway. No real HTTP request
is ever made; nothing leaves the JVM. They can pass even if the actual wire
format — URL, headers, body encoding — is completely broken, because the
mock only checks that methods were *called*, not what bytes would actually
be sent. See `REVIEW.md` finding #N for the full writeup.

**What I did instead:** added `AwsSnsSmsGatewayWireMockTest`, which starts a
real `WireMockServer` on a local port and sends an actual HTTP request to it
through the gateway — no mocked `WebClient`. It asserts on what the stub
server actually received: the exact query parameters (including that spaces
are percent-encoded as `%20`, not `+` — the SigV4 bug from finding #1), the
`Authorization` and `x-amz-date` headers, and the request path. This is the
first test in the suite that would actually catch a wire-format regression
in this gateway.

**Scope:** implemented for `AwsSnsSmsGateway` only, per the assignment's
"one honest integration test" — not retrofitted across all five providers.
That would be a good next step (see "What I'd do with another week" below),
but doing it for all providers in this time-box would come at the cost of
Part 1/2 depth, which is weighted higher.

**Enabling this test required one small production change:** added an
`endpointOverride` field to `AwsSnsProperties` (defaults to `null`, so
production behavior — building the real `sns.<region>.amazonaws.com` URL —
is unchanged). This is the only way to point the gateway at a local stub
server instead of AWS without hitting the network in tests.

### DONE: Double URL-encoding broke every real AWS SNS request

Caught by the honest integration test (`AwsSnsSmsGatewayWireMockTest`) — the
existing mocked `*WireMockTest` suite never inspects real bytes, so it missed
this. `webClient.uri(endpoint + "/?" + queryString)` re-encoded an already
percent-encoded query string, turning `%20` into `%2520`, which would break
the SigV4 signature and fail every real SMS with `403 SignatureDoesNotMatch`.
Fixed by wrapping in `URI.create(...)` so WebClient doesn't re-encode it.
Proof: test fails on original code, passes after fix. Commit: `<sha>`.


Commit: `da5e7b8bdd42227342a9850e2d7d4464b7d3405b`


### CUT: Tenant-aware provider selection — scoped, not implemented

**Current state:** `SmsChannelProperties` is a single flat, global
`@ConfigurationProperties` bean (`formwork.sms-channel.*`) — one `provider`
string and one set of nested provider configs (`TwilioProperties`,
`VonageProperties`, `AwsSnsProperties`, etc.), all Spring singletons wired
once at startup. There is no per-tenant config anywhere in this class, and
no keying structure (no `Map<String, SmsChannelProperties>` or similar) to
hang one off of. `SmsMessage.tenantId` is read nowhere in the send path.
This isn't a wiring gap — the tenant dimension doesn't exist in config at
all yet, so this is a small feature addition, not a bug fix.

**Why I cut it:** the assignment is explicit that "one tenant's
configuration must never affect another's." Given the existing gateways
are constructed once as Spring singletons (e.g. `AwsSnsSmsGateway` holds a
single `AwsSnsProperties` instance and a single shared `WebClient` for its
whole lifetime), the fast-but-wrong version — mutating a shared config
object per-request based on `tenantId` — would create a race: two tenants'
sends interleaving could read each other's credentials mid-request. I'd
rather scope this out than ship something that fails exactly the isolation
requirement the task calls out by name.

**Design I'd use:**
- Extend config to `Map<String, SmsChannelProperties> tenantOverrides`
  (keyed by tenant ID) alongside the existing global `SmsChannelProperties`,
  loaded the same way (Spring relaxed binding already supports nested maps
  in `@ConfigurationProperties`).
- A `TenantSmsGatewayResolver` that, given `tenantId`, looks up the
  tenant's override (falling back to the global default `SmsChannelProperties`
  if none exists), and returns the matching `SmsGateway`.
- Each tenant+provider pair gets its **own** gateway instance — e.g.
  `Map<String, SmsGateway>` cache keyed by `tenantId + provider`, built
  lazily on first use, immutable once created — rather than one shared
  `AwsSnsSmsGateway` instance being reconfigured per call. This is what
  actually guarantees isolation: no mutable shared state crosses the
  tenant boundary at all, because there's no shared state to mutate.

**Hardest part:** `AwsSnsSmsGateway`'s constructor takes an
`AwsSnsProperties` and builds its `WebClient` once. Making this
tenant-aware means either constructing one gateway instance per tenant
(more objects, but zero shared mutable state — simpler to reason about)
or making `send()` accept per-call config (touches every gateway
implementation, more invasive). I'd pick the first: more instances, but
correctness by construction beats correctness by discipline.

**Estimate:** ~half a day — config map + resolver + per-tenant gateway
cache + a concurrency test asserting two tenants on the same provider with
different credentials never cross-contaminate under concurrent sends.


### CUT: Retry and failover — scoped, not implemented

**Current state:** `SmsChannelProperties.RetryProperties` exists
(`maxAttempts` default 3, `backoff` default `"5s"`) but is never read
anywhere in the send path. `AwsSnsSmsGateway.send()` fails once and
returns `SmsResult.failure(...)` — no retry, no fallback provider.

**Why I cut it:** the assignment's own line — "retrying the wrong one is
worse than not retrying at all" — is the actual test here, and I'd rather
scope it honestly than get the classification wrong. Looking at
`AwsSnsSmsGateway.send()` specifically, its `catch` block currently
collapses every failure mode into two buckets: `WebClientResponseException`
(any HTTP status) and generic `Exception` (network/timeout/signing
errors) — it doesn't distinguish "SNS rejected this request" (4xx, don't
retry) from "SNS returned 5xx" (retry) from "we never got a response at
all" (ambiguous — the message may have already sent; retrying risks a
double-send and double-billing the tenant, which is worse than the
original failure).

**Design I'd use:**
- Classify failures at the `SmsResult`/exception boundary into
  `RETRYABLE` (`WebClientResponseException` with 5xx status, connect
  timeout, read timeout before any response) vs. `TERMINAL` (4xx —
  `CONFIG_ERROR` for missing credentials, validation errors, auth
  failures — these will fail identically on retry).
- Retry only `RETRYABLE` failures, up to `retry.maxAttempts`, with
  exponential backoff parsed from `retry.backoff` (e.g. via Spring's
  `Duration` binding instead of a raw string) plus jitter applied
  **before** the cap is enforced — a common bug is capping first then
  adding jitter, which lets the cap be exceeded and defeats the point of
  having one.
- After exhausting retries on the primary provider, fail over to one
  configured secondary `SmsGateway` — a single attempt, not a second full
  retry loop, so total delivery attempts stay bounded and predictable.
- Because SNS's `send()` timeout case is genuinely ambiguous (request may
  have reached AWS and been billed even though the client never saw the
  response), I'd treat bare timeouts as **non-retryable** by default
  unless I can confirm from AWS docs that SNS `Publish` is safe to retry
  on timeout — safer to under-retry than to double-send.

**Hardest part:** the timeout ambiguity above. Without confirming SNS's
actual behavior on timeout (idempotency token support, if any), any
retry-on-timeout decision is a guess, and guessing wrong here means
double-charging a tenant for one SMS — the exact "worse than not retrying"
outcome the assignment warns about.

**Estimate:** ~a full day to do properly across all five providers with
correct per-provider classification; ~2–3 hours for a single-provider
(AWS SNS) implementation with explicit failure classification and
capped-then-jittered backoff, which I'd want validated before extending
to Twilio/Vonage/BudgetSMS/MessageBird.


# What I'd do with another week

- Full GSM 03.38 extension-table-aware segment counting (or pull in a
  small, well-tested library constant instead of hand-rolling it).
- `[Retry/failover]`
- `[Tenant-aware provider selection]`