#!/usr/bin/env python3
"""
Seat reservation burst test.

Scenarios
  hot-seat     N different users race for ONE seat.
               Expect: exactly one 201, everyone else 409, zero 5xx.
  user-limit   ONE user races for N different seats.
               Expect: exactly per_user_limit x 201, the rest 409, zero 5xx.
  idempotency  ONE user sends the SAME Idempotency-Key N times.
               Expect: all 201 with the same reservationId, exactly one seat held.

Usage
  pip install requests
  python scripts/burst.py --scenario hot-seat --requests 500
  python scripts/burst.py --scenario hot-seat --requests 500 --confirm-winner
  python scripts/burst.py --scenario user-limit --requests 20
  python scripts/burst.py --url https://your-app.onrender.com --scenario idempotency

If --show-id is omitted, a fresh show is created with the admin token.
Use a fresh show for every run: a seat that is already held cannot be won again.
"""

import argparse
import queue
import sys
import threading
import time
import uuid
from collections import Counter

import requests

ADMIN_TOKEN = "admin-burst"
VERIFIER_TOKEN = "burst-verifier"
EXPECTED_STATUSES = {201, 409}

failures = []


# ---------------------------------------------------------------
# HTTP helpers
# ---------------------------------------------------------------

def auth(token):
    return {"Authorization": f"Bearer {token}"}


def create_show(base_url, seat_count, per_user_limit):
    seats = [f"A{i}" for i in range(1, seat_count + 1)]

    response = requests.post(
        f"{base_url}/shows",
        headers=auth(ADMIN_TOKEN),
        json={
            "name": f"burst-{uuid.uuid4().hex[:8]}",
            "seats": seats,
            "price_paise": 25000,
            "per_user_limit": per_user_limit,
        },
        timeout=60,
    )
    response.raise_for_status()

    return response.json()["id"]


def fetch_show(base_url, show_id):
    response = requests.get(
        f"{base_url}/shows/{show_id}",
        headers=auth(VERIFIER_TOKEN),
        timeout=60,
    )
    response.raise_for_status()

    return response.json()


def reserve(session, base_url, show_id, job):
    user, idempotency_key, seats = job
    started = time.perf_counter()

    try:
        response = session.post(
            f"{base_url}/reservations/{show_id}/reserve",
            headers={
                **auth(user),
                "Idempotency-Key": idempotency_key,
            },
            json={"seats": seats},
            timeout=60,
        )

        body = None
        if response.status_code == 201:
            try:
                body = response.json()
            except ValueError:
                pass

        return {
            "user": user,
            "status": response.status_code,
            "body": body,
            "elapsed": time.perf_counter() - started,
        }

    except requests.RequestException as ex:
        return {
            "user": user,
            "status": "ERROR",
            "body": None,
            "elapsed": time.perf_counter() - started,
            "error": str(ex),
        }


# ---------------------------------------------------------------
# Burst runner
# ---------------------------------------------------------------

def run_burst(base_url, show_id, jobs, workers):
    """
    Starts `workers` threads, releases them together through a barrier,
    and lets them drain the job queue as fast as possible.
    """
    workers = max(1, min(workers, len(jobs)))

    pending = queue.Queue()
    for job in jobs:
        pending.put(job)

    barrier = threading.Barrier(workers)
    results = []
    lock = threading.Lock()

    def worker():
        with requests.Session() as session:
            barrier.wait()

            while True:
                try:
                    job = pending.get_nowait()
                except queue.Empty:
                    return

                result = reserve(session, base_url, show_id, job)

                with lock:
                    results.append(result)

    threads = [threading.Thread(target=worker) for _ in range(workers)]

    started = time.perf_counter()

    for thread in threads:
        thread.start()

    for thread in threads:
        thread.join()

    return results, time.perf_counter() - started


# ---------------------------------------------------------------
# Reporting / validation helpers
# ---------------------------------------------------------------

def percentile(sorted_values, pct):
    if not sorted_values:
        return 0.0

    index = int(round(pct / 100 * (len(sorted_values) - 1)))

    return sorted_values[index]


def check(ok, message):
    print(("PASS: " if ok else "FAIL: ") + message)

    if not ok:
        failures.append(message)


def seat_status(show, seat_number):
    for seat in show["seats"]:
        if seat["seat"] == seat_number:
            return seat["status"]

    return None


def held_plus_confirmed(show):
    return show["heldSeats"] + show["confirmedSeats"]


# ---------------------------------------------------------------
# Main
# ---------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(
        description="Seat reservation concurrency burst test"
    )

    parser.add_argument("--url", default="http://localhost:8080")
    parser.add_argument("--show-id", help="Existing show UUID (default: create one)")
    parser.add_argument(
        "--scenario",
        choices=["hot-seat", "user-limit", "idempotency"],
        default="hot-seat",
    )
    parser.add_argument("--requests", type=int, default=100)
    parser.add_argument(
        "--workers",
        type=int,
        default=200,
        help="Max simultaneous client threads (default 200)",
    )
    parser.add_argument("--seat", default="A1", help="Seat for hot-seat / idempotency")
    parser.add_argument("--limit", type=int, default=4, help="per_user_limit for a created show")
    parser.add_argument("--user-prefix", default="burst-user")
    parser.add_argument(
        "--confirm-winner",
        action="store_true",
        help="hot-seat only: confirm the winning reservation afterwards",
    )

    args = parser.parse_args()
    base_url = args.url.rstrip("/")
    n = args.requests

    print()
    print("=" * 64)
    print(f"SEAT RESERVATION BURST: {args.scenario}")
    print("=" * 64)

    # ---- show setup ------------------------------------------------
    show_id = args.show_id

    if show_id is None:
        seat_count = max(n, 5) if args.scenario == "user-limit" else 5
        show_id = create_show(base_url, seat_count, args.limit)
        print(f"Created show {show_id} ({seat_count} seats, limit {args.limit})")

    before = fetch_show(base_url, show_id)
    per_user_limit = before["perUserLimit"]

    print(f"URL      : {base_url}")
    print(f"Show     : {show_id}")
    print(f"Requests : {n}  (client threads: {min(args.workers, n)})")
    print()

    # ---- build jobs --------------------------------------------------
    run_id = uuid.uuid4().hex[:8]
    prefix = f"{args.user_prefix}-{run_id}"

    if args.scenario == "hot-seat":
        jobs = [
            (f"{prefix}-{i}", str(uuid.uuid4()), [args.seat])
            for i in range(n)
        ]

    elif args.scenario == "user-limit":
        solo_user = f"{prefix}-solo"
        jobs = [
            (solo_user, str(uuid.uuid4()), [f"A{i + 1}"])
            for i in range(n)
        ]

    else:  # idempotency
        solo_user = f"{prefix}-solo"
        shared_key = str(uuid.uuid4())
        jobs = [(solo_user, shared_key, [args.seat]) for _ in range(n)]

    # ---- fire ---------------------------------------------------------
    results, elapsed = run_burst(base_url, show_id, jobs, args.workers)

    statuses = Counter(r["status"] for r in results)
    latencies = sorted(r["elapsed"] for r in results)

    print("RESULTS")
    print("-" * 64)

    for status, count in sorted(statuses.items(), key=lambda item: str(item[0])):
        print(f"  {status}: {count}")

    print()
    print(f"Wall time : {elapsed:.2f}s  ({n / elapsed:.0f} req/s)")
    print(
        f"Latency   : p50={percentile(latencies, 50) * 1000:.0f}ms  "
        f"p95={percentile(latencies, 95) * 1000:.0f}ms  "
        f"max={latencies[-1] * 1000:.0f}ms"
    )

    errors = [r for r in results if r["status"] == "ERROR"]
    for r in errors[:3]:
        print(f"  network error ({r['user']}): {r['error']}")

    # ---- verify -------------------------------------------------------
    after = fetch_show(base_url, show_id)

    created = statuses.get(201, 0)
    conflicts = statuses.get(409, 0)
    unexpected = {
        s: c for s, c in statuses.items() if s not in EXPECTED_STATUSES
    }
    newly_held = held_plus_confirmed(after) - held_plus_confirmed(before)

    print()
    print("VALIDATION")
    print("-" * 64)

    check(
        not unexpected,
        "Only 201/409 responses"
        + (f" (unexpected: {unexpected})" if unexpected else ""),
        )
    check(len(results) == n, f"All {n} requests accounted for")
    check(
        after["availableSeats"] + after["heldSeats"] + after["confirmedSeats"]
        == after["totalSeats"],
        "Seat counts reconcile",
        )

    if args.scenario == "hot-seat":
        check(created == 1, f"Exactly one winner (got {created})")
        check(conflicts == n - 1, f"Every loser got 409 ({conflicts}/{n - 1})")
        check(newly_held == 1, f"Exactly one seat newly held (got {newly_held})")
        check(
            seat_status(after, args.seat) == "HELD",
            f"{args.seat} is HELD (got {seat_status(after, args.seat)})",
            )

        if args.confirm_winner and created == 1:
            winner = next(r for r in results if r["status"] == 201)
            reservation_id = (winner["body"] or {}).get("reservationId")

            confirm = requests.post(
                f"{base_url}/reservations/{reservation_id}/confirm",
                headers=auth(winner["user"]),
                timeout=60,
            )

            check(confirm.status_code == 200, f"Winner confirm returned {confirm.status_code}")

            confirmed = fetch_show(base_url, show_id)
            check(
                seat_status(confirmed, args.seat) == "CONFIRMED",
                f"{args.seat} is CONFIRMED (got {seat_status(confirmed, args.seat)})",
                )

    elif args.scenario == "user-limit":
        expected_success = min(n, per_user_limit)

        check(
            created == expected_success,
            f"Exactly {expected_success} reservations succeeded (got {created})",
            )
        check(
            conflicts == n - expected_success,
            f"Remaining requests got 409 ({conflicts}/{n - expected_success})",
            )
        check(
            newly_held == expected_success,
            f"{expected_success} seats newly held (got {newly_held})",
            )

    else:  # idempotency
        reservation_ids = {
            (r["body"] or {}).get("reservationId")
            for r in results
            if r["status"] == 201
        }

        check(created == n, f"Every replay returned 201 ({created}/{n})")
        check(
            len(reservation_ids) == 1 and None not in reservation_ids,
            f"All replays returned the same reservationId ({len(reservation_ids)} distinct)",
            )
        check(newly_held == 1, f"Exactly one seat newly held (got {newly_held})")

    print()
    print("=" * 64)
    print("RESULT:", "PASS" if not failures else f"FAIL ({len(failures)} checks)")
    print("=" * 64)

    sys.exit(0 if not failures else 1)


if __name__ == "__main__":
    main()