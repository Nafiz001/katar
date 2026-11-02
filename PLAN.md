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
- [ ] M6. UI: queue, then seat map (coach diagram), then ticket, served from static.
- [ ] M7. CI (GitHub Actions: `./mvnw verify`), Dockerfile (multi-stage, arm64 + amd64), image via portfolio-deploy's reusable image.yml.
- [ ] M8. Deploy on the A1 (130.210.37.18) in portfolio-deploy's `vernissage/` stack with its own postgres, Caddy site katar.130-210-37-18.sslip.io. Seed a demo sale that reopens daily.
- [ ] M9. k6 rush test, README with architecture, decisions and numbers. Then the portfolio, profile and CV (ask the user first).

## Demo mode (KATAR_DEMO=true)

- Every hour the sale for train 753, ten days ahead, starts afresh. It opens at :01, so visitors can see the pre-opening wait. It admits 5 people a second, with a room of 150.
- Bots keep about 160 people waiting, so a visitor waits roughly 30 seconds. Bots buy one or two seats on random legs, and stop buying at 55% of seat-legs sold.
- Checked locally on 2026-11-02: a visitor was admitted after 34 s, held an AC seat, paid, and the ticket verified.
- Local run: the  cluster on port 5492 (waiting for server to start....2026-11-02 16:28:33.567 +06 [12572] FATAL:  lock file "postmaster.pid" already exists
2026-11-02 16:28:33.567 +06 [12572] HINT:  Is another postmaster (PID 19560) running in data directory "E:/Backend_Projects/katar/.dev/pg"?
 stopped waiting), then 
  .   ____          _            __ _ _
 /\ / ___'_ __ _ _(_)_ __  __ _ \ \ \ ( ( )\___ | '_ | '_| | '_ \/ _` | \ \ \  \/  ___)| |_)| | | | | || (_| |  ) ) ) )
  '  |____| .__|_| |_|_| |_\__, | / / / /
 =========|_|==============|___/=/_/_/_/

 :: Spring Boot ::                (v4.1.2)

2026-11-02T16:28:35.605+06:00  INFO 18364 --- [katar] [           main] dev.nafiz.katar.KatarApplication         : Starting KatarApplication v0.0.1-SNAPSHOT using Java 21.0.5 with PID 18364 (E:\Backend_Projects\katar	arget\katar-0.0.1-SNAPSHOT.jar started by nafiz in E:\Backend_Projects\katar)
2026-11-02T16:28:35.613+06:00  INFO 18364 --- [katar] [           main] dev.nafiz.katar.KatarApplication         : No active profile set, falling back to 1 default profile: "default"
2026-11-02T16:28:39.132+06:00  INFO 18364 --- [katar] [           main] o.s.boot.tomcat.TomcatWebServer          : Tomcat initialized with port 8093 (http)
2026-11-02T16:28:39.156+06:00  INFO 18364 --- [katar] [           main] o.apache.catalina.core.StandardService   : Starting service [Tomcat]
2026-11-02T16:28:39.156+06:00  INFO 18364 --- [katar] [           main] o.apache.catalina.core.StandardEngine    : Starting Servlet engine: [Apache Tomcat/11.0.27]
2026-11-02T16:28:39.208+06:00  INFO 18364 --- [katar] [           main] b.w.c.s.WebApplicationContextInitializer : Root WebApplicationContext: initialization completed in 3456 ms
2026-11-02T16:28:40.070+06:00  INFO 18364 --- [katar] [           main] com.zaxxer.hikari.HikariDataSource       : HikariPool-1 - Starting...
2026-11-02T16:28:40.410+06:00  INFO 18364 --- [katar] [           main] com.zaxxer.hikari.pool.HikariPool        : HikariPool-1 - Added connection org.postgresql.jdbc.PgConnection@684372d0
2026-11-02T16:28:40.413+06:00  INFO 18364 --- [katar] [           main] com.zaxxer.hikari.HikariDataSource       : HikariPool-1 - Start completed.
2026-11-02T16:28:40.448+06:00  INFO 18364 --- [katar] [           main] org.flywaydb.core.FlywayExecutor         : Database: jdbc:postgresql://localhost:5492/katar (PostgreSQL 18.1)
2026-11-02T16:28:40.585+06:00  INFO 18364 --- [katar] [           main] o.f.core.internal.command.DbValidate     : Successfully validated 2 migrations (execution time 00:00.066s)
2026-11-02T16:28:40.606+06:00  INFO 18364 --- [katar] [           main] o.f.core.internal.command.DbMigrate      : Current version of schema "public": 2
2026-11-02T16:28:40.612+06:00  INFO 18364 --- [katar] [           main] o.f.core.internal.command.DbMigrate      : Schema "public" is up to date. No migration necessary.
2026-11-02T16:28:40.938+06:00  WARN 18364 --- [katar] [           main] dev.nafiz.katar.booking.TicketSigner     : No KATAR_TICKET_PRIVATE_KEY set: tickets are signed with a key made at startup and will not verify after a restart
2026-11-02T16:28:42.582+06:00  INFO 18364 --- [katar] [           main] o.s.b.a.e.web.EndpointLinksResolver      : Exposing 3 endpoints beneath base path '/actuator'
2026-11-02T16:28:42.874+06:00  INFO 18364 --- [katar] [           main] o.s.boot.tomcat.TomcatWebServer          : Tomcat started on port 8093 (http) with context path '/'
2026-11-02T16:28:42.908+06:00  INFO 18364 --- [katar] [           main] dev.nafiz.katar.KatarApplication         : Started KatarApplication in 8.262 seconds (process running for 9.255).

## Notes for whoever resumes

- The user is asleep and asked for the work to continue when the usage limit resets. Don't push to GitHub or deploy (M7/M8) without checking memory and portfolio-deploy notes first. Create the GitHub repo `Nafiz001/katar` as public, like the other showcase repos.
- Low-memory PC (~7.8 GB): build with `./mvnw -q`, and don't run several JVMs at once.
