# Rush test results

`k6 run -e PEOPLE=800 loadtest/rush.js` against katar with a fast demo sale
(`--katar.demo-admit=400 --katar.demo-room=3000 --katar.demo-crowd=false`),
then `psql -f loadtest/check.sql`.

**The machine matters.** These runs are on a laptop (Intel i5-1155G7, 8 GB,
Windows 11) that runs k6's 800 virtual users, the JVM (384 MB heap) and
PostgreSQL 18 at the same time. Read the latencies as a floor on what a real
server does, not as its numbers.

## 2026-11-02, the live server

Oracle Ampere A1 (2 cores, 12 GB), with k6 running in a container on the same
box, against the live demo sale: its real settings (5 admitted a second,
behind the simulated crowd), so most of each person's time is the queue,
working as designed.

`docker run --network vernissage_default -e BASE=http://katar:8080 -e PEOPLE=300 grafana/k6 run - < loadtest/rush.js`

| | |
|---|---|
| requests | 39,849, **0 failed** (most are people polling their place) |
| throughput | 397 requests/s |
| hold, median / p95 | **5.7 ms / 59 ms** |
| join, median / p95 | 465 ms / 1.05 s |
| bookings paid in the run | 195; "coach full" 109 |
| time in the queue, average | 68 s (the sale admits 5 a second) |
| check.sql over everything the server has sold (667 bookings) | 0 overlaps, 0 out of step, 0 over the limit |

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
