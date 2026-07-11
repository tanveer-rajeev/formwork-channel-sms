### Finding 1: AWS SigV4 signature mismatch due to wrong URL-encoding style

**Severity:** Critical — every AWS SNS send containing a space (i.e. almost
every real SMS message) fails in production with a 403 SignatureDoesNotMatch
error. No message actually gets delivered.

**Location:** `AwsSnsSmsGateway.java:120` (the `encode()` method), used at
`AwsSnsSmsGateway.java:57` (where the query string is built).

**What's wrong:** `encode()` uses `URLEncoder.encode(value, StandardCharsets.UTF_8)`,
which implements `application/x-www-form-urlencoded` encoding (e.g. space → `+`,
`~` → `%7E`). AWS SigV4 requires strict RFC 3986 percent-encoding instead
(space → `%20`, `~` left unescaped, `*` percent-encoded). The same encoded
query string is used both to compute the signature and to build the actual
outgoing request. AWS independently re-encodes the query string it receives
using RFC 3986 rules and recomputes the signature to compare. Because the two
encodings disagree on several characters, the signatures don't match and AWS
rejects the request. This is invisible in the existing tests because the test
message ("Test") contains none of the differing characters.

**Fix:** Replace `URLEncoder.encode()` with a dedicated RFC 3986 percent-encoder:
percent-encode every byte except unreserved characters (`A-Z a-z 0-9 - _ . ~`),
always encode space as `%20` (never `+`), and use uppercase hex digits.

---

## Finding 2: SMS segment count hardcoded to 1 — cost silently under-recorded for multi-segment messages

**Severity:** High — money lost. Any message that spans more than one SMS
segment is billed by AWS/the carrier at the real segment count, but the
system records it as 1. Over volume this creates a permanent, growing gap
between actual provider spend and what the system reports/charges tenants
for, invisible until someone manually reconciles against the AWS bill.

**Location:** `AwsSnsSmsGateway.java:100` — `return SmsResult.success(messageId, "AWS_SNS", 1);`

**What's wrong:** The segment count is a hardcoded literal `1`, independent
of the actual message body. SMS has a fixed per-segment character limit —
160 chars (GSM-7 encoding, used for plain Latin text) or 70 chars (UCS-2,
forced by any character outside the GSM-7 set, e.g. emoji or non-Latin
script) per single segment, dropping to 153/67 respectively once a message
needs to be split across multiple segments (to leave room for
concatenation headers). Any message longer than that limit is silently
split into multiple segments by AWS and billed per segment, but this
method always reports back `1`, regardless of `message.body()` length or
encoding. Since the assignment's cost pipeline presumably bills/attributes
cost per segment, every multi-segment message is under-counted.

**Fix:** Extract a pure `calculateSegmentCount(String body)` method:
detect whether the body is GSM-7-eligible (every character present in the
GSM-7 default alphabet) or requires UCS-2 (any character outside it), then
divide the body length by the correct single/concatenated limit for that
encoding. Call this from `send()` instead of hardcoding `1`.

### Known limitation: GSM-7 character detection

`calculateSegmentCount()` uses a simplified GSM-7 default-alphabet character
set to decide GSM-7 vs UCS-2. It does **not** account for the GSM extension
table (e.g. `{ } [ ] \ ^ ~ | €`), where each character actually consumes 2
character-slots, not 1. This means segment counts for messages containing
those specific characters may be undercounted by one segment in edge cases.
Given the time-box, I chose to fix the larger bug (hardcoded `1`) rather than
implement the full GSM 03.38 table byte-for-byte.With another week I'd
either implement the full extension-table-aware version or pull in a
small, well-tested library constant instead of hand-rolling this.

---

### Finding 3 — BudgetSMS credentials and message content sent as GET query parameters

**Severity:** High — data leak (PII: recipient phone number and message body; plus long-lived provider credentials)

**Location:** `BudgetSmsGateway.java`, `send()` method

```
webClient.get()
    .uri(BUDGETSMS_API_URL
            + "?username={user}&password={pass}&from={from}&to={to}&msg={msg}",
            config.getUsername(),
            config.getPassword(),
            config.getOriginator(),
            message.to(),
            message.body())
```

**Same issue for these classes as well ** `AwsSnsSmsGateway.java`, `send()`:
```
webClient.get().uri(endpoint + "/?" + queryString)...
```
**What's wrong:** 

The BudgetSMS username, password, sender ID, recipient phone number, and full message body are all 
interpolated into the URL query string of a GET request rather than sent in a request body. Query strings get captured 
in places request bodies don't: access logs on any reverse proxy, load balancer, API gateway, or CDN between this service
and BudgetSMS; Reactor Netty/WebClient's own DEBUG/TRACE HTTP logging, which logs full request URIs; APM/tracing tools 
that record request URLs as span attributes; and browser or proxy history if the endpoint is ever exercised manually for
debugging. None of these logging layers are designed to redact query parameters the way they might redact a JSON body 
field, so the recipient's phone number and message content (PII) and the BudgetSMS account credentials can end up
persisted in plaintext in infrastructure this team doesn't control the retention policy for — independent of whether 
the BudgetSMS call itself succeeds or fails.

**Fix:** 

Switch to a POST request with the parameters in a form body (`application/x-www-form-urlencoded` or whatever 
BudgetSMS's API accepts), so credentials and message content never appear in a URI. If BudgetSMS's API genuinely only 
supports GET, then at minimum: (1) add an explicit logging/exchange filter on this `WebClient` that redacts `password` 
and `msg` before any URI is logged at any level, and (2) never enable DEBUG/TRACE logging for this client in production.
Also rotate the BudgetSMS credentials once the fix ships, since they may already be sitting in historical logs.

## Finding 4 — PII (phone numbers) written to application logs

**Severity:**
Critical — data leak. This is a compliance/privacy exposure, not a code smell.

**Location:**
`TwilioSmsGateway.java:54`
`log.info("Twilio SMS sent: sid={}, to={}", sid, message.to());`
`BudgetSmsGateway.java:XX` — `log.info("BudgetSMS sent: messageId={}, to={}", messageId, message.to());`

**What's wrong:**

Every successful send logs the full recipient phone number at INFO level.
Application logs are typically shipped to a centralized logging platform (CloudWatch, Datadog, ELK, etc.) with broader 
access, longer retention, and weaker access controls than the SMS service's own database.
Since this platform is multi-tenant, this means one tenant's customer phone numbers are sitting in a shared logging 
system readable by anyone with log access — engineers debugging an unrelated tenant, support staff, whoever has the Datadog role.
There is no redaction, hashing, or masking applied anywhere in this path.
In a jurisdiction with GDPR/CCPA-style obligations, this is a reportable exposure, not a hypothetical.

**How you'd fix it:**

Remove `to` from the log line entirely, or replace it with a non-reversible identifier — last 4 digits, or a salted hash 
— sufficient for debugging without reconstructing the full number.
`sid` alone is already enough to look the message up in Twilio's dashboard if deeper investigation is needed.
Apply the same audit to the other four gateways; if one gateway leaks PII, it is likely a copy-paste pattern across all five.

---

## Finding 5 — Blocking HTTP call has no timeout, can exhaust the shared thread pool

**Severity:**
High — production failure with multi-tenant blast radius; indirectly costs money through failed OTPs, failed notifications,
and SLA breaches.

**Location:**
`TwilioSmsGateway:41-46`, `AwsSnsSmsGateway:100-105`, `BudgetSmsGateway:25-35`, `MessageBirdSmsGateway:39-44`,
`VonageSmsGateway:41-46`
The `webClient.post() ... .bodyToMono(Map.class).block()` chain.

**What's wrong:**

`.block()` is called with no `.timeout(Duration)` anywhere in the chain.
If Twilio's API is slow or hangs — which happens during provider-side incidents — the calling thread blocks indefinitely.
`send()` is presumably invoked from a bounded worker/thread pool shared across all tenants.
One stuck Twilio call ties up one thread forever; enough stuck calls and the pool is exhausted.
This means SMS sending stalls for every tenant on the platform, not just the one whose message is slow.
A single upstream provider hiccup becomes a platform-wide outage.
There is also a secondary risk: if this method is ever invoked from a Reactor/WebFlux event-loop thread rather than a 
dedicated blocking pool, `.block()` will throw `IllegalStateException` outright — worth confirming which context calls this.

**How you'd fix it:**

Add an explicit `.timeout(Duration.ofSeconds(N))` before `.block()`, with `N` chosen against Twilio's documented p99 
latency plus margin.
Handle the resulting `TimeoutException` as a distinct failure mode so it can be retried or failed over per the Part 3 
retry logic, rather than left to hang.
Longer term, this gateway should probably be non-blocking end to end rather than mixing WebClient with `.block()`, 
but a timeout is the minimum fix that prevents the pool-exhaustion failure mode.

---

## Finding 6 — Response-parsing failure after a successful send is reported as a send failure

**Severity:**
High — money and data-integrity risk. This can cause silent billing loss and duplicate sends.

**Location:**
`TwilioSmsGateway.java:47-58`   
The `num_segments` parsing block and the `return SmsResult.success(...)` statement, both inside the same `try` as the HTTP call.

**What's wrong:**

The HTTP call to Twilio and the parsing of its response are wrapped in the same `try` block.

Once Twilio returns a response, the message has already been accepted and will be billed, regardless of what happens 
next in this method.

The code then parses `num_segments` with `Integer.parseInt(String.valueOf(response.get("num_segments")))`.

If that field is missing in an unexpected shape, empty, non-numeric, or changes format in a future Twilio API version, 
`Integer.parseInt` throws a `NumberFormatException`.

That exception is caught by the generic `catch (Exception e)` block below, which returns `SmsResult.failure(...)`.

The caller now believes the send failed, even though Twilio already accepted and will charge for the message.

Two downstream consequences follow from this:

The cost-recording pipeline never charges this send against the tenant, because it was told the send failed — so real 
provider spend goes untracked, and the tenant's SMS costs silently drift out of sync with the actual Twilio bill.

If Part 3's retry/failover logic treats this as a genuine failure, it will retry the send. There is no idempotency key 
on the Twilio request, so the retry creates a second real message to the same recipient, billed twice for a single 
logical send.

**How you'd fix it:**

Separate "the HTTP call succeeded" from "I could parse the optional segment count."

Move the `num_segments` parsing into its own try/catch, defaulting `segments` to `1` and logging a warning if parsing 
fails, rather than letting a parsing exception fall through to the outer failure path.

Once the HTTP call itself has returned a 2xx response, the method should always return `SmsResult.success(...)` — a 
malformed or unexpected field in the response body should never be able to flip a successful send into a reported failure.

---

### Finding 7

**Severity:** High — this doesn't itself lose money or leak data, so it isn't counted toward the two required 
production-incident findings; its harm is that it lets other defects (e.g. the SigV4/segment-counting bugs) ship and 
regress silently, undetected.

**Location:** `AwsSnsSmsGatewayTest.java` (entire file); `AwsSnsSmsGatewayExtraTest.java`, especially 
`send_NoAwsCredentials_ReturnsConfigError()`.

**What's wrong:**

Applying the assignment's own test — "if I broke the code this test covers, would this test fail?" — almost everything 
in both files fails that test. All methods except one only call `supports()` or `getProviderName()`, trivial pass-through
checks that never touch signing, encoding, the HTTP call, or segment counting; you could delete the entire SigV4 
implementation and every one of those tests would still pass.

The one test that does call `send()`, `send_NoAwsCredentials_ReturnsConfigError`, gates its assertion behind 
`if (accessKey == null)` where `accessKey` is read from the real OS environment variable `AWS_ACCESS_KEY_ID` — but 
`send()` itself checks `config.getAccessKey()`, not the environment, and `setUp()` never calls `config.setAccessKey(...)`.
So the test is checking the wrong source of truth: it currently passes only because both the env var (in CI) and 
the unset config field happen to both be null. If a developer sets `AWS_ACCESS_KEY_ID` in their shell for an unrelated 
reason, the `if` body silently stops executing and the test reports green having asserted nothing — the test's pass/fail 
is coupled to ambient shell state, not to the code under test.

**Fix:**
Fix the guard to be deterministic: explicitly call `config.setAccessKey(null)` / leave it unset in the test itself, 
rather than branching on `System.getenv(...)`, so the test's behavior no longer depends on who runs it or where.

Add a real test against a WireMock stub server. Note `AwsSnsGateway` already supports this — `config.getBaseUri()` 
overrides the derived AWS host if set — so no production code change is needed for testability; the WireMock test just 
needs to set `baseUri` to the stub's URL and assert on the actual request received (query string parameters, 
`Authorization` header format, `x-amz-date` header, HTTP method). This is also the test that would directly prove 
Findings 1 and 2 once those are fixed.


---

## Finding 6 — Successful result returned even when `MessageId` extraction fails

**Severity:** Medium — silent partial failure. No immediate money/data loss,
but downstream delivery-status reconciliation and idempotency handling lose
their key.

**Location:** `AwsSnsSmsGateway.java`, `send()`:
```
String messageId = extractXmlElement(responseBody, "MessageId");
...
return SmsResult.success(messageId, "AWS_SNS", ...);
```

**What's wrong:** `extractXmlElement()` returns `null` if the `<MessageId>`
tag isn't found (malformed response, unexpected AWS response shape, partial
response body). The code doesn't check for this — it returns
`SmsResult.success(null, ...)` regardless. The caller believes the send
succeeded but has no `MessageId` to reconcile delivery status, deduplicate
retries, or investigate later.

**Fix:** Treat a `null` extracted `messageId` after an HTTP 2xx response as a
distinct failure mode (e.g. `"PARSE_ERROR"`) rather than silently returning
success, or at minimum log a warning so the gap is visible in monitoring.