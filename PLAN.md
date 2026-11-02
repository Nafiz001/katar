# katar: build plan and progress

> কাতার (katar) = "a queue, a row". A train-ticketing engine built for the Eid
> ticket rush in Bangladesh: hundreds of thousands of people open the sale at
> the same second. Resume project for 2027 BD job hunting: **Java 21 + Spring
> Boot 3**, the stack Therap, Optimizely and WellDev use.
>
> **For a resuming session:** read this file and continue from the first
> unchecked milestone. Commit after each milestone, with commit messages that
> have a subject and body only and no Claude attribution (the user's CLAUDE.md).

## The two hard problems (the point of the project)

1. **A fair virtual waiting room, in PostgreSQL alone.**
   - Joining takes `nextval('queue_ticket')`: O(1), no row locks, strictly ordered.
   - A scheduler advances an *admission watermark* (`admitted_upto`) by N tickets a second. Your place in line = your ticket number minus the watermark, so nothing is ever reshuffled.
   - Admitted users get a short-lived HMAC-signed *admission pass*. Every seat endpoint requires it.
   - Each national ID or phone can hold one queue ticket per sale. Rejoining returns the same ticket, so refreshing the page can't skip ahead.
   - Inactive admitted users expire, so their capacity frees up.
   - Early joiners (before opening) have no ticket; at opening they are shuffled at random, so camping on the page gains nothing.
   - A turn not claimed within the claim window (2 minutes) lapses, so no-shows can't block the room.

2. **Seats sold by the segment, without double-selling.**
   - A route is a list of stations, so a journey is a contiguous set of legs: a bitmask.
   - Each seat row stores `taken` as a bitmask (bigint, so at most 63 legs).
   - A hold is ONE statement: `UPDATE seat SET taken = taken | :mask WHERE id = :id AND (taken & :mask) = 0`. If no row is updated, someone else got an overlapping leg. There's no read-then-write race, and no table lock.
   - A hold has a TTL (default 10 minutes). Expiring releases it with `taken & ~mask`. A sweeper runs `@Scheduled` and uses `SKIP LOCKED`.
   - Buying converts the hold to a ticket. It's idempotent via an `Idempotency-Key` header. Payment is simulated by a mock gateway that calls a signed webhook.
   - The ticket code is signed with Ed25519: an inspector's phone holds only the public key, verifies offline, and a lost phone cannot forge tickets.
   - A payment arriving after its seats expired cancels the booking and reports REFUND, so nobody pays without a seat.
   - Limit: 4 seats per national ID per train-day (BD railway rule).

## Supporting pieces

- **Live updates:** SSE streams of queue position and the seat map for a journey.
- **Observability:** Micrometer, `/actuator/prometheus`, wired to the existing Grafana on the A1 server.
- **Abuse:** a per-IP token bucket on join and hold.
- **Load test:** a k6 script that simulates the rush (N joins in one second, then holds). Results go in the README.
- **UI:** a static page served by Spring: queue screen, then seat map, then ticket. Can ask Gemini for design ideas; build it with the frontend-design skill's rules.

## Stack and layout

- Java 21 (virtual threads on), Spring Boot 4.1.2, Maven wrapper.
- Spring Web, Spring JDBC (`JdbcClient`, not JPA: the core is hand-written SQL), Flyway, Validation, Actuator, Micrometer Prometheus.
- PostgreSQL 18 in prod. Tests use embedded Postgres (io.zonky.test embedded-postgres): there's no Docker on the dev PC, and the same setup runs in CI.
- Package `dev.nafiz.katar`: `queue/`, `inventory/`, `booking/`, `payment/`, `ticket/`, `web/`, `config/`.
- Dev DB: project-local cluster `.dev/pg` on port 5492 (PG 18 binaries in `C:\Program Files\PostgreSQL\18\bin`; never touch the system cluster on 5432).

## Milestones

- [x] M1. Scaffold: Spring Initializr project, Flyway schema (stations, trains, routes, coaches, seats, sales, queue, holds, tickets), seed data for a real route (Dhaka to Rajshahi, Silk City Express: stations and legs), health endpoint, embedded-pg test harness.
- [x] M2. Inventory core: segment masks, hold, release, expire sweeper. Concurrency tests: 200 threads on overlapping legs means exactly one winner; disjoint legs means all win.
- [x] M3. Waiting room: join, position, watermark scheduler, admission pass (HMAC), one ticket per NID. Tests for order, fairness and rejoining.
- [x] M4. Booking: hold API (needs a pass), buy with idempotency, mock payment and signed webhook, ticket issue with an offline-verifiable code, the 4-seats-per-NID rule. Tests.
- [x] M5. SSE live streams, Prometheus metrics, rate limiting.
- [x] M6. UI: queue, then seat map (coach diagram), then ticket, served from static.
- [x] M7. CI (GitHub Actions: `./mvnw verify`), Dockerfile (multi-stage, arm64 + amd64), image via portfolio-deploy's reusable image.yml.
- [x] M8. Deploy on the A1 (130.210.37.18) in portfolio-deploy's `vernissage/` stack with its own postgres, Caddy site katar.130-210-37-18.sslip.io. Seed a demo sale that reopens daily.
- [x] M9. k6 rush test, README with architecture, decisions and numbers. Then the portfolio, profile and CV (ask the user first).

## Demo mode (KATAR_DEMO=true)

- Every hour the sale for train 753, ten days ahead, starts afresh. It opens at :01, so visitors can see the pre-opening wait. It admits 5 people a second, with a room of 150.
- Bots keep about 160 people waiting, so a visitor waits roughly 30 seconds. Bots buy one or two seats on random legs, and stop buying at 55% of seat-legs sold.
- Checked locally on 2026-11-02: a visitor was admitted after 34 s, held an AC seat, paid, and the ticket verified.
- Local run: start the `.dev/pg` cluster on port 5492 (`pg_ctl -D .dev/pg -o "-p 5492" start`), then `java -jar target/katar-0.0.1-SNAPSHOT.jar --server.port=8093 --katar.demo=true`.

## State (2026-11-02)

- **Done:** published at github.com/Nafiz001/katar (public) and live at https://katar.130-210-37-18.sslip.io. CI is green: tests, then the amd64 and arm64 images.
- **Server test:** the server rush test and the oversell check are recorded in loadtest/RESULTS.md and the README.
- **Still to do, with the user's OK:** add katar to the portfolio site, the GitHub profile and the CV.

## Notes for whoever resumes

- The user is asleep and asked for the work to continue when the usage limit resets. Don't push to GitHub or deploy (M7/M8) without checking memory and portfolio-deploy notes first. Create the GitHub repo `Nafiz001/katar` as public, like the other showcase repos.
- Low-memory PC (~7.8 GB): build with `./mvnw -q`, and don't run several JVMs at once.
