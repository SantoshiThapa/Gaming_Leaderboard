#!/usr/bin/env python3
"""Fires thousands of game results at the leaderboard API and reports throughput and latency.

Examples:
  python simulate.py --url http://localhost:8080
  python simulate.py --url https://leaderboard-lb.onrender.com --players 3000 --requests 30000 --workers 40
"""
import argparse
import random
import statistics
import threading
import time
from concurrent.futures import ThreadPoolExecutor

import requests

MODES = ["blitz", "bullet", "rapid"]
ADJ = ["Neon", "Turbo", "Pixel", "Cosmic", "Shadow", "Blaze", "Vapor", "Glitch", "Nova", "Rogue", "Hyper", "Lunar"]
NOUN = ["Fox", "Wolf", "Knight", "Ghost", "Falcon", "Viper", "Rook", "Pawn", "Bishop", "Comet", "Raven", "Tiger"]

local = threading.local()


def session():
    if not hasattr(local, "s"):
        local.s = requests.Session()
    return local.s


def make_players(n):
    names = set()
    while len(names) < n:
        names.add(f"Sim{random.choice(ADJ)}{random.choice(NOUN)}{random.randint(1, 99999)}")
    return list(names)


def post(url, payload, latencies, errors):
    t0 = time.perf_counter()
    try:
        r = session().post(url, json=payload, timeout=30)
        if r.status_code >= 400:
            errors.append(r.status_code)
    except requests.RequestException as e:
        errors.append(type(e).__name__)
    latencies.append((time.perf_counter() - t0) * 1000)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default="http://localhost:8080", help="load balancer base URL")
    ap.add_argument("--players", type=int, default=2000)
    ap.add_argument("--requests", type=int, default=20000, help="score updates after the seeding pass")
    ap.add_argument("--workers", type=int, default=32)
    ap.add_argument("--batch", type=int, default=25, help="items per /api/scores/batch call")
    ap.add_argument("--single", action="store_true", help="use POST /api/scores instead of batches")
    args = ap.parse_args()
    base = args.url.rstrip("/")

    players = make_players(args.players)
    seeds = [{"username": p, "mode": random.choice(MODES), "rating": random.randint(800, 2600), "source": "sim"}
             for p in players]
    updates = [{"username": random.choice(players), "mode": random.choice(MODES),
                "delta": int(random.gauss(0, 25)) or 1, "source": "sim"} for _ in range(args.requests)]
    items = seeds + updates

    if args.single:
        jobs = [(f"{base}/api/scores", it) for it in items]
    else:
        jobs = [(f"{base}/api/scores/batch", items[i:i + args.batch]) for i in range(0, len(items), args.batch)]

    latencies, errors = [], []
    print(f"Sending {len(items)} results in {len(jobs)} requests with {args.workers} workers to {base} ...")
    start = time.perf_counter()
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = [pool.submit(post, u, p, latencies, errors) for u, p in jobs]
        done = 0
        for f in futures:
            f.result()
            done += 1
            if done % max(1, len(jobs) // 10) == 0:
                print(f"  {done}/{len(jobs)} requests done")
    elapsed = time.perf_counter() - start

    latencies.sort()
    p = lambda q: latencies[min(len(latencies) - 1, int(len(latencies) * q))]
    print("\nResults")
    print(f"  wall time        {elapsed:.1f}s")
    print(f"  results/second   {len(items) / elapsed:.0f}")
    print(f"  requests/second  {len(jobs) / elapsed:.0f}")
    print(f"  latency ms       mean {statistics.mean(latencies):.0f}  p50 {p(.5):.0f}  p95 {p(.95):.0f}  max {latencies[-1]:.0f}")
    print(f"  errors           {len(errors)}" + (f"  (e.g. {errors[0]})" if errors else ""))


if __name__ == "__main__":
    main()
