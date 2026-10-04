# Write-up: Seat Reservation at Scale

## 1. The problem and the guarantees

The hard part of this assignment is not the endpoints. It is staying correct when hundreds of requests compete for the same seat at the same instant. The service is built around these guarantees:

1. A seat is held or confirmed by at most one reservation at a time.
2. A user never holds more than per_user_limit seats in a show (held and confirmed seats both count).
3. The same Idempotency-Key never creates a second reservation.
4. An expired hold gives back both the seat and the user's quota.
5. Expected conflicts are reported as 4xx. A 500 always means a real bug.

## 2. Concurrency design

The naive flow, "read the seat, see it is available, save a reservation", is wrong because two requests can both read AVAILABLE before either one writes. This service never decides on a stale read. The decision is made inside a database transaction, under row locks (SELECT ... FOR UPDATE, with PostgreSQL's default READ COMMITTED isolation).

A reserve request runs as one transaction:

1. Validate the input (non-empty, no duplicate seats, Idempotency-Key present) and load the show.
2. Insert an idempotency row if it is absent, then lock it (see section 3).
3. Fast-fail read (no lock): if any requested seat is already taken, return 409 immediately.
4. Lock the user's limit row for this show.
5. Lock the requested seat rows, ordered by seat number.
6. Under the locks, check the per-user limit and that every seat is AVAILABLE. These checks are the authoritative decision.
7. Create the reservation as HELD, mark the seats HELD, increase the user's active-seat counter, link the idempotency row and commit.

Whoever locks a seat first wins. Every other request waits for that lock, then reads the committed state and gets 409.

Deadlock avoidance. Locks are always taken in the same order: user limit before seats, and seats in seat-number order. Two multi-seat requests with overlapping seats therefore queue up instead of deadlocking. Cancel and hold expiry take the reservation lock first, then the user-limit row, then the seats, which matches the reserve order for the rows they share. If Postgres still aborts a transaction as a deadlock victim, or a lock times out, the API returns 409 and the request can be retried safely.

Why the fast-fail exists. In a hot-seat storm, nearly every request loses. Without step 3, each loser would take the user-limit lock, queue on the seat lock while holding a database connection, and only then be rejected. Measured locally, that exhausted the connection pool and produced 503 responses. With step 3, losers are rejected after a few cheap statements. The fast read is only an optimisation: a seat that looks free can still be taken before the locks are acquired, so step 6 re-checks under the lock. The fast read is a projection (seat status only), not entities. Loading Seat entities there would put stale copies into Hibernate's session, and the later FOR UPDATE query would return those stale copies instead of the freshly locked rows, which would allow a double booking.

## 3. Idempotency

Each request carries an Idempotency-Key. A row keyed by (show, user, key) is inserted if it does not exist, then locked, so concurrent requests with the same key are serialised.

- First request: creates the reservation and links it to the key.
- Retry with the same key and the same seats: returns the original reservation (201) without touching any seat.
- Same key with a different seat list: 409. The request hash is stored and compared.

The idempotency check comes first, before the seat checks. A retry of a successful request must replay its result even though its seat is, correctly, already held.

A request that fails with 409 rolls back completely, including its idempotency row, so retrying it re-runs the decision. That is intentional: if the seat was released in between, the retry can succeed.

## 4. Holds and expiry

- A reserve creates a HELD reservation that expires after 5 minutes.
- Confirm checks the expiry time exactly, so a seat can never be confirmed after its hold lapsed, regardless of whether the sweeper has run yet.
- A scheduled sweeper (every 5 seconds) finds expired holds, using an index on expires_at, and releases each one in its own transaction. It re-checks the reservation under its row lock, frees the seats, returns the user's quota and marks the reservation CANCELLED. Because everything is re-checked under the lock, a race with a confirm or a cancel is safe: whichever transaction locks second sees the new state and does nothing. It is also safe to run on several instances.
- Expired holds are recorded as CANCELLED rather than a separate EXPIRED status, to avoid a further schema change. A dedicated status would be a small follow-up.

## 5. Consistency

- All state changes for one operation (reservation, seats, user counter, idempotency link) commit in one transaction, so readers never see a half-applied reservation.
- The per-user counter (active_seat_count) is changed only while its row is locked, and in the same transaction as the reservation it counts, so it cannot drift from the reservations.
- The schema backs the invariants up independently of application code: a unique constraint on (show, seat_number), a unique constraint on (show, user, idempotency_key), check constraints on the status values, and active_seat_count must be at least 0.
- Hibernate runs in validate mode and Flyway owns the schema, so code and database cannot silently disagree.

## 6. Error semantics

| Situation | Response |
|---|---|
| Seat taken, user limit exceeded, idempotency key reused with a different request, wrong reservation state (for example confirm twice or after expiry) | 409 |
| Lock timeout or deadlock victim (transaction rolled back, safe to retry) | 409 |
| Missing Idempotency-Key, bad JSON, malformed UUID, duplicate or empty seats | 400 |
| Missing token | 401 |
| Not the reservation owner, or not an admin | 403 |
| Unknown show, reservation or seat | 404 |
| Database unavailable or connection pool exhausted | 503 with a Retry-After header |
| Anything unexpected | 500 (logged with the stack trace) |

## 7. Observability

- The liveness and readiness probes are at /actuator/health/liveness and /actuator/health/readiness (readiness includes the database).
- /actuator/prometheus exposes HTTP request metrics, JVM metrics, Hikari pool metrics and two business metrics: reservation_operations_total (labels operation and outcome) and reservation_holds_expired_total.
- A correlation ID is attached to every request and appears in every log line (at the default INFO level; the live deployment lowers the log level to WARN to save CPU). JSON logs are enabled by setting LOGGING_STRUCTURED_FORMAT_CONSOLE to logstash.

## 8. Testing and results

Automated tests (mvn test, needs Docker): Mockito unit tests for the service, plus integration tests on a real PostgreSQL container for a 50-user hot-seat race, 30 concurrent requests with the same idempotency key, a per-user-limit race, and hold expiry (the freed seat and quota can be reserved again).

Burst script (scripts/burst_test.py), local Docker Compose, 20-connection pool, Windows with Docker Desktop. These local runs were taken before the hold-expiry sweeper was added.

| Scenario | Result |
|---|---|
| Hot seat, 500 requests (final design, two runs) | 1 x 201, 499 x 409, 0 x 5xx. 39 and 46 req/s; p50 about 3.6 to 4.4 s, p95 about 6.6 to 7.8 s |
| Hot seat, 500 requests (before the fast-fail change) | 1 x 201, 486 x 409, 13 x 503 at 22 req/s |
| One user, 20 different seats, limit 4 | exactly 4 x 201, 16 x 409 |
| One user, same idempotency key, 100 requests | 100 x 201, one distinct reservation, one seat held |

After each hot-seat run the script also checks that exactly one seat was newly held, that the seat counts reconcile and, with --confirm-winner, that the winner's confirm returns 200 and the seat ends CONFIRMED.

Where the time goes. Connection-pool metrics after the 500-request runs: average connection hold about 0.43 s, average wait for a connection about 1.64 s, zero pool timeouts. Throughput is roughly pool size divided by hold time (20 / 0.43 s, about 46 req/s), which matches what the script measured. I did not establish why each request holds a connection for about 0.43 s on this machine. The Docker Desktop setup and the client sharing the same CPU are likely contributors, but that is a guess.

Live deployment (Render free tier: about 0.1 CPU, 512 MB, DB_POOL_SIZE 10, Tomcat threads capped at 20, log level WARN):

| Run | Result |
|---|---|
| Hot seat, 20 requests, 5 client threads | clean: 1 x 201, 19 x 409 |
| Hot seat, 50 requests, 20 client threads | intermittent: some runs clean (1 x 201, 49 x 409), others had 1 to 8 client-side connection resets out of 50 |
| Hot seat, 100 requests, 100 client threads (before tuning the instance) | all 100 connections failed; no reservation was created |

In every live run, including the ones with connection errors, at most one reservation was created, no seat was double-booked and the seat counts reconciled. The errors were connections dropped by the very small instance, not incorrect answers. I did not investigate them further than checking that they were not application errors, and I cannot say for certain whether they come from CPU throttling, memory pressure or the platform's proxy. The local Docker run is the better measure of the design's performance.

## 9. Limitations and what I would do next

- Authentication is a placeholder. The bearer token is the user ID, and any caller can claim admin with an admin- prefix. A real system would use signed tokens (for example JWTs) and proper roles.
- Throughput is bounded by the connection pool. A losing request still holds a connection for a few statements. The next step would be a read-only loser path: reject on a plain read before opening a write transaction, and only then fall back to the locking path. I did not build it because it adds real complexity.
- There is a single database primary. Correctness relies on PostgreSQL row locks, so scaling the API horizontally is fine, but the single database is the bottleneck. Sharding by show would be the way to scale it.
- Expired holds are CANCELLED, not a distinct EXPIRED status, and the 5-minute hold duration is a constant in code rather than configuration.
- The live instance is very small, so large simultaneous bursts can drop connections there. Its database is scheduled to expire on 3 November 2026.
- There is no rate limiting, and GET /shows/{id} returns all seats without pagination.

## 10. AI usage

I used Claude (Anthropic) as an assistant during this project.


- Design and code review: reviewing the reservation service's locking and idempotency design, and spotting problems: expired holds that were never released, a possible lock-order deadlock between reserve and cancel, exceptions that returned 500 instead of 409, and a missing confirm endpoint.
- Tests: fixing unit tests (strict Mockito stubbing) and the concurrency tests (thread-pool sizing, sharing one Testcontainers PostgreSQL across test classes).
- Tooling and operations: the burst script, the metrics, correlation-ID and error-mapping code, the hold-expiry sweeper, Dockerisation and the Render deployment.
- What I did myself: *[for example: the original schema and reservation service, the design decisions, running every test and burst, and deploying]*.
- How I checked the AI's suggestions: I ran all tests and ran the burst script locally and against the deployed service. Performance claims here come from measurements (pool metrics and burst output), not from the assistant's estimates. Where the cause of a result is unknown, such as the live connection resets, I say so above instead of asserting one.
