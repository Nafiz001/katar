# Rush test results

`k6 run -e PEOPLE=800 loadtest/rush.js` against katar with a fast demo sale
(`--katar.demo-admit=400 --katar.demo-room=3000 --katar.demo-crowd=false`),
then `psql -f loadtest/check.sql`.

**The machine matters.** These runs are on a laptop (Intel i5-1155G7, 8 GB,
Windows 11) that runs k6's 800 virtual users, the JVM (384 MB heap) and
PostgreSQL 18 at the same time. Read the latencies as a floor on what a real
server does, not as its numbers.

## Correctness (every run)

| check | result |
|---|---|
| two live holds overlapping on a seat's legs | **0** |
| seats whose bitmask differs from their holds | **0** |
| people holding more than four seats | **0** |

## 2026-11-02, after caching route lookups and a one-statement join

800 people arriving within 3 seconds:

| | |
|---|---|
| requests | 5,266, **0 failed** |
| throughput | 407 requests/s |
| bookings paid | 678 (978 seat-journeys on a 385-seat train) |
| answered "coach full" | 181 |
| join, median / p95 | 0.75 s / 4.4 s |
| hold, median / p95 | 1.07 s / 3.4 s |
| time in the queue, average | 2.9 s |

## The same day, before those changes

| | |
|---|---|
| throughput | 310 requests/s |
| join, median / p95 | 2.06 s / 4.6 s |
| hold, p95 | 4.2 s |
| time in the queue, average | 6.3 s |

## What the first run found

With all 800 connecting in the same millisecond, **576 of 800 joins were
refused at the TCP level**: Tomcat's default accept backlog (100) overflowed
before katar saw a request. `server.tomcat.accept-count` is now 1000. A
Windows laptop still caps the backlog lower than that, which is why the test
spreads arrivals over a few seconds (`SPREAD`, default 3); Linux honours it.
