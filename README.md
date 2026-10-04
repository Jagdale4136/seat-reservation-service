# Seat Reservation Service

A backend API for reserving seats at a show, built to stay correct under heavy concurrency. If 500 users try to reserve seat A12 at the same instant, exactly one of them gets it and the other 499 receive a clean 409 Conflict, never a 500.

- Live API: https://seat-reservation-service-fqlg.onrender.com (Render free tier, see "Live deployment notes" below)
- Design, measurements and trade-offs: see WRITEUP.md in this repository

Stack: Java 21, Spring Boot 4, Spring Data JPA (Hibernate), PostgreSQL 18, Flyway, Spring Security, Micrometer and Prometheus, Docker, Testcontainers.

---

## Contents

1. How it works in short
2. Features
3. Quick start with Docker
4. Running the app without Docker
5. API reference
6. Burst (load) test
7. Automated tests
8. Observability
9. Configuration
10. Project structure
11. Deployment on Render
12. Live deployment notes
13. Troubleshooting
14. Known limitations

---

## 1. How it works in short

A reservation never follows the pattern "read the seat, see it is free, then save". Two requests could both see the seat as free. Instead, the decision is made inside a database transaction using row locks (SELECT ... FOR UPDATE):

- Seats are locked, in seat-number order, so overlapping multi-seat requests cannot deadlock. Whoever locks a seat first wins. Everyone else waits, then sees the seat is taken and gets 409.
- A per-user counter row is locked, so the per-user seat limit cannot be exceeded by parallel requests.
- An idempotency record is locked, so a retried request (same Idempotency-Key) returns the original reservation instead of creating a second one.
- A lock-free fast-fail check rejects requests for seats that are already taken before they queue for locks. This keeps losing requests cheap during a hot-seat storm. The locked checks remain the authoritative decision.
- Holds expire. A reservation is a 5-minute hold. A scheduled sweeper releases expired holds and gives the seat and the user's quota back. Confirming an expired hold is always rejected.

The full reasoning, including lock ordering, error semantics and measured results, is in WRITEUP.md.

---

## 2. Features

- Create shows with named seats, a price (in paise) and a per-user seat limit
- Reserve one or several seats as a time-limited hold, then confirm or cancel
- Per-user, per-show seat limit (held and confirmed seats both count)
- Idempotency-Key support for safe retries
- Automatic release of expired holds
- Consistent error responses: conflicts are 409, bad input is 400, and so on
- Health and readiness probes, Prometheus metrics, correlation IDs in structured logs
- Concurrency tests against real PostgreSQL and a ready-made burst script

---

## 3. Quick start with Docker

Requires Docker with Compose. From the project folder run:

    docker compose up --build

This starts PostgreSQL 18 and the application. Database migrations run automatically. The first start takes about a minute. Check that the app is ready:

    curl http://localhost:8080/actuator/health/readiness

The expected answer is {"status":"UP"}. The API is then available at http://localhost:8080.

To stop everything press Ctrl+C. To also wipe the database, run:

    docker compose down -v

---

## 4. Running the app without Docker

This is useful for development. You need Java 21 and Maven installed (the Maven wrapper is not included, so use mvn). First start only the database:

    docker compose up -d postgres

Then run the application:

    mvn spring-boot:run

The defaults point at localhost port 5432, database seat_reservation, with user and password postgres. See the Configuration section to change them.

---

## 5. API reference

### Authentication

Every endpoint except the health and metrics endpoints needs a bearer token in this header:

    Authorization: Bearer <user-id>

The token string is the user ID. A token that starts with admin- (for example admin-demo) receives the admin role, which is required to create shows. This is a deliberately simple scheme. See "Known limitations".

### Endpoints

| Method | Path | Who | Description |
|---|---|---|---|
| POST | /shows | admin | Create a show together with its seats |
| GET | /shows/{showId} | any user | Show details, per-seat status and seat counts |
| POST | /reservations/{showId}/reserve | any user | Hold one or more seats. Requires an Idempotency-Key header |
| POST | /reservations/{reservationId}/confirm | owner | Confirm a held reservation |
| POST | /reservations/{reservationId}/cancel | owner | Cancel a held or confirmed reservation |
| GET | /actuator/health/liveness | public | Liveness probe |
| GET | /actuator/health/readiness | public | Readiness probe (includes the database) |
| GET | /actuator/prometheus | public | Prometheus metrics |

Windows Command Prompt tip: single quotes do not work for JSON in cmd. Save the JSON in a file (for example body.json) and send it with -d @body.json, or use PowerShell, Postman or the burst script.

### Create a show

    curl -X POST http://localhost:8080/shows \
      -H "Authorization: Bearer admin-demo" \
      -H "Content-Type: application/json" \
      -d '{"name":"Demo show","seats":["A1","A2","A3"],"price_paise":25000,"per_user_limit":4}'

| Field | Type | Notes |
|---|---|---|
| name | string | required |
| seats | list of strings | required, at least one, no duplicates, each at most 50 characters |
| price_paise | number | required, greater than zero |
| per_user_limit | number | optional, defaults to 4 |

Response 201:

    {
      "id": "7ad59e18-1b90-4faf-b58a-833156badcba",
      "name": "Demo show",
      "pricePaise": 25000,
      "perUserLimit": 4,
      "totalSeats": 3,
      "availableSeats": 3,
      "heldSeats": 0,
      "confirmedSeats": 0,
      "seats": [
        {"seat": "A1", "status": "AVAILABLE"},
        {"seat": "A2", "status": "AVAILABLE"},
        {"seat": "A3", "status": "AVAILABLE"}
      ]
    }

### Get a show

    curl http://localhost:8080/shows/<showId> -H "Authorization: Bearer user-1"

This returns the same shape as above, with the current seat statuses (AVAILABLE, HELD or CONFIRMED).

### Reserve seats

    curl -X POST http://localhost:8080/reservations/<showId>/reserve \
      -H "Authorization: Bearer user-1" \
      -H "Idempotency-Key: demo-key-1" \
      -H "Content-Type: application/json" \
      -d '{"seats":["A1"]}'

- The Idempotency-Key header is required. Use a unique string for each logical request (a UUID works well).
- Sending the same key with the same seats again returns the original reservation. Sending the same key with different seats returns 409.
- All requested seats are reserved together or not at all.

Response 201:

    {
      "reservationId": "0b63a988-5d07-49d1-8867-34eb77ad6973",
      "showId": "7ad59e18-1b90-4faf-b58a-833156badcba",
      "userId": "user-1",
      "seats": ["A1"],
      "amountPaise": 25000,
      "status": "HELD"
    }

The hold lasts 5 minutes. After that it is released automatically.

### Confirm a reservation

    curl -X POST http://localhost:8080/reservations/<reservationId>/confirm \
      -H "Authorization: Bearer user-1"

Response 200 is the reservation with status CONFIRMED. Only the owner can confirm, only a HELD reservation can be confirmed, and an expired hold is rejected with 409.

### Cancel a reservation

    curl -X POST http://localhost:8080/reservations/<reservationId>/cancel \
      -H "Authorization: Bearer user-1"

Response 200:

    {
      "reservationId": "0b63a988-5d07-49d1-8867-34eb77ad6973",
      "showId": "7ad59e18-1b90-4faf-b58a-833156badcba",
      "userId": "user-1",
      "seats": ["A1"],
      "amountPaise": 25000,
      "status": "CANCELLED"
    }

The seats become available again and the user's quota is returned.

### Status codes

| Code | Meaning |
|---|---|
| 200 | Fetch, confirm or cancel succeeded |
| 201 | Show created, or seats held (also returned when an idempotent retry replays the original reservation) |
| 400 | Invalid input: missing Idempotency-Key, malformed JSON or UUID, empty or duplicate seats |
| 401 | Missing bearer token |
| 403 | Not allowed: not the reservation's owner, or not an admin |
| 404 | Show, reservation or seat not found |
| 409 | Seat already taken; per-user limit exceeded; idempotency key reused with a different request; reservation in the wrong state (for example confirming twice, or confirming after the hold expired); lock conflict (safe to retry) |
| 503 | Database unavailable or connection pool exhausted (safe to retry) |
| 500 | Unexpected error (a bug, logged with a stack trace) |

Error responses share one shape:

    {
      "timestamp": "2026-10-04T12:04:21.836Z",
      "status": 409,
      "error": "Conflict",
      "message": "One or more requested seats are already reserved",
      "path": "/reservations/7ad59e18-1b90-4faf-b58a-833156badcba/reserve"
    }

---

## 6. Burst (load) test

The script scripts/burst_test.py fires many requests at the same instant, then fetches the final state and checks the result. It creates its own show unless you pass --show-id. It needs the requests library:

    pip install requests

| Scenario | What it does | What it expects |
|---|---|---|
| hot-seat | N different users race for one seat | exactly one 201, the rest 409, no 5xx |
| user-limit | one user races for N different seats | exactly per_user_limit times 201, the rest 409 |
| idempotency | one user sends the same Idempotency-Key N times | all 201, one distinct reservation, one seat held |

Examples. First, 500 users race for one seat and then the winner confirms:

    python scripts/burst_test.py --scenario hot-seat --requests 500 --confirm-winner

The other two scenarios:

    python scripts/burst_test.py --scenario user-limit --requests 20
    python scripts/burst_test.py --scenario idempotency --requests 100

Against a deployed instance, with fewer simultaneous client threads:

    python scripts/burst_test.py --url https://seat-reservation-service-fqlg.onrender.com --scenario hot-seat --requests 50 --workers 20 --confirm-winner

Options: --url, --show-id, --scenario, --requests, --workers (maximum simultaneous client threads, default 200), --seat, --limit (per-user limit for a created show), --user-prefix and --confirm-winner.

The script prints status counts and latency percentiles, then runs checks such as "exactly one winner", "every loser got 409", "no 5xx or network errors", "seat counts reconcile" and, with --confirm-winner, "the seat ends up CONFIRMED". It ends with RESULT: PASS or RESULT: FAIL and exits with code 1 on failure. Use a fresh show for every run: a seat that is already held cannot be won again.

---

## 7. Automated tests

Run all tests with:

    mvn test

Docker must be running. The integration tests start a real PostgreSQL 18 container with Testcontainers, and one container is shared by all test classes.

| Test class | What it covers |
|---|---|
| ReservationServiceTest | Unit tests (Mockito): reserve, multi-seat, conflicts, limits, idempotency, confirm, cancel |
| ReservationExpiryTest | Unit tests for releasing expired holds |
| ReservationConcurrencyTest | Real database: 50-user hot-seat race, 30 concurrent requests with one idempotency key, per-user-limit race |
| HoldExpiryIntegrationTest | Real database: an expired hold frees the seat and the user's quota |
| Controller and repository tests | HTTP layer and data access |

---

## 8. Observability

- Probes: /actuator/health/liveness and /actuator/health/readiness (readiness also checks the database).
- Metrics: /actuator/prometheus exposes standard HTTP request metrics (with status codes) and JVM metrics, Hikari connection-pool metrics (names starting with hikaricp_connections), and two business metrics: reservation_operations_total (labels operation and outcome; operations are reserve, confirm and cancel; the outcome is success or the exception name, for example SeatAlreadyReservedException) and reservation_holds_expired_total.
- Logs: each request is logged with a correlation ID that appears in every log line for that request (at the default INFO level; the Render deployment lowers the level to WARN to save CPU on the small instance). Set LOGGING_STRUCTURED_FORMAT_CONSOLE to logstash for JSON logs. The Docker Compose file already does this.

A quick look at the pool and outcome counters on Linux or macOS:

    curl -s http://localhost:8080/actuator/prometheus | grep -E "hikaricp_connections_(max|pending|timeout_total)|reservation_operations_total"

On Windows Command Prompt use findstr instead of grep:

    curl -s http://localhost:8080/actuator/prometheus | findstr "hikaricp_connections_max hikaricp_connections_pending reservation_operations_total"

---

## 9. Configuration

| Variable | Default | Purpose |
|---|---|---|
| PORT | 8080 | HTTP port |
| DATABASE_URL | jdbc:postgresql://localhost:5432/seat_reservation | JDBC URL. Must start with jdbc:postgresql:// |
| DATABASE_USERNAME | postgres | Database user |
| DATABASE_PASSWORD | postgres | Database password |
| DB_POOL_SIZE | 20 | Connection pool size (fixed). Docker Compose uses 20, the Render deployment uses 10 |
| LOGGING_STRUCTURED_FORMAT_CONSOLE | unset | Set to logstash for JSON logs |
| reservation.hold.sweep-interval-ms | 5000 | How often expired holds are released (milliseconds) |

The 5-minute hold duration is a constant in ReservationService. Database migrations (in src/main/resources/db/migration) run automatically at startup, and Hibernate validates the schema against them.

---

## 10. Project structure

    src/main/java/com/kiran/seatreservation/
      controller/    REST endpoints (shows, reservations)
      service/       ReservationService (locking logic), ShowService, HoldExpiryJob (sweeper)
      repository/    Spring Data repositories, including the FOR UPDATE queries
      entity/        JPA entities (Show, Seat, Reservation, ReservationSeat, IdempotencyKey, UserShowLimit)
      dto/           Request and response objects
      exception/     Domain exceptions and the global exception handler (HTTP status mapping)
      security/      Bearer-token filter and security configuration
      logging/       Correlation-ID and request-logging filters
      metrics/       Business metrics
      config/        Scheduling configuration
    src/main/resources/
      application.properties
      db/migration/  V1 initial schema, V2 hold support, V3 expiry index
    src/test/java/   Unit and integration tests (Testcontainers)
    scripts/         burst_test.py
    Dockerfile, docker-compose.yml

---

## 11. Deployment on Render

The live instance was deployed from this repository as a Docker web service, with a Render PostgreSQL database in the same region.

1. Create a PostgreSQL database (free plan) and note its internal hostname, database name, username and password.
2. Create a Web Service from this repository: runtime Docker, the same region as the database, root directory left empty.
3. Set the environment variables listed in the table below.
4. Set the health check path to /actuator/health/liveness.

Environment variables used for the live deployment:

| Key | Value |
|---|---|
| DATABASE_URL | jdbc:postgresql://<internal-hostname>:5432/<database-name> |
| DATABASE_USERNAME | from the database page |
| DATABASE_PASSWORD | from the database page |
| DB_POOL_SIZE | 10 |
| LOGGING_STRUCTURED_FORMAT_CONSOLE | logstash |
| JAVA_TOOL_OPTIONS | -XX:MaxRAMPercentage=60 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 |
| SERVER_TOMCAT_THREADS_MAX | 20 |
| LOGGING_LEVEL_COM_KIRAN_SEATRESERVATION | WARN |
| LOGGING_LEVEL_COM_KIRAN_SEATRESERVATION_EXCEPTION | ERROR |

DATABASE_URL must use the jdbc:postgresql:// form. Render's own connection strings start with postgres://, which Spring does not accept. PORT is provided by Render. The JVM, thread and logging settings exist only to make the very small free instance cope better with bursts.

---

## 12. Live deployment notes

The live instance runs on Render's free tier: about 0.1 CPU and 512 MB of memory, with a free PostgreSQL database.

- It sleeps after about 15 minutes without traffic. The first request after a pause can take a minute or more while the service starts.
- Correctness held in every live run: no seat was ever double-booked. Under a heavy simultaneous burst the instance is slow and may drop some connections, which a client sees as a network error rather than a wrong answer. The numbers are in WRITEUP.md, section 8. For full-speed behaviour, run the service locally with Docker as described above.
- Free Render databases are time-limited. This database is scheduled to expire on 3 November 2026. After that date the live API stops working, and the project can still be run locally with Docker.

---

## 13. Troubleshooting

| Symptom | Likely cause and fix |
|---|---|
| The app exits right after docker compose up, with an error such as "column ... already exists" | An old database volume does not match the current migrations. Run docker compose down -v, then docker compose up --build |
| The readiness endpoint returns DOWN | The app cannot reach the database. Check DATABASE_URL, the username and the password |
| mvn test fails while starting a container | Docker is not running |
| Port 5432 or 8080 is already in use | Stop the other process (for example a local PostgreSQL) or change the port mapping in docker-compose.yml |
| 401 on every call | The Authorization: Bearer <user-id> header is missing |
| 403 on POST /shows | The token must start with admin- |
| 409 on a seat you expect to be free | It may be held by another reservation. Holds last 5 minutes. Check with GET /shows/{showId} |
| The burst script reports FAIL on a re-run | Use a fresh show: a seat that is already held cannot be won again |

---

## 14. Known limitations

- Authentication is a placeholder. The token is the user ID and any caller can claim admin with an admin- prefix. A real system would use signed tokens (for example JWTs) and proper roles.
- Throughput is bounded by the database connection pool, and the single PostgreSQL primary is the scaling limit.
- Expired holds are recorded as CANCELLED, not as a separate EXPIRED status, and the 5-minute hold duration is not configurable.
- There is no rate limiting, and GET /shows/{id} returns every seat without pagination.
- The live instance is very small, so large simultaneous bursts can drop connections there.

More detail on these trade-offs, and on what I would do next, is in WRITEUP.md.
