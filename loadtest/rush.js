// The Eid rush, simulated: every virtual user is one person who joins the
// queue the moment the test starts, waits to be let in, holds one or two seats
// on a random stretch of the route, books and pays.
//
//   katar running locally with a fast demo sale and no simulated crowd:
//     java -jar target/katar-*.jar --katar.demo=true \
//       --katar.demo-admit=400 --katar.demo-room=3000 --katar.demo-crowd=false
//   then:
//     k6 run -e BASE=http://localhost:8093 -e PEOPLE=800 loadtest/rush.js
//
// Each person sends their own X-Forwarded-For, so the per-address rate limit
// treats them as different people, as it would behind the real proxy.
import http from "k6/http";
import { check, sleep } from "k6";
import { Counter, Trend } from "k6/metrics";

const BASE = __ENV.BASE || "http://localhost:8093";
const PEOPLE = Number(__ENV.PEOPLE || 800);
// Seconds over which people arrive. A rush is thousands clicking within a few
// seconds of opening; 0 sends everyone in the same millisecond, which a
// Windows laptop cannot even accept (its connection backlog is small).
const SPREAD = Number(__ENV.SPREAD || 3);
const STOPS = ["DHK", "APT", "JDP", "TGL", "BBE", "ULP", "CMH", "ISD", "ADP", "RJS"];
const COACHES = ["KA", "KHA", "GA", "GHA", "UMA", "CHA"];

const seatsHeld = new Counter("seats_held");
const coachFull = new Counter("coach_full");
const paid = new Counter("bookings_paid");
const waited = new Trend("time_in_queue_ms", true);

export const options = {
  scenarios: {
    rush: { executor: "per-vu-iterations", vus: PEOPLE, iterations: 1, maxDuration: "5m" },
  },
  thresholds: {
    // goals for the server; a laptop running k6 as well will miss them (see RESULTS.md)
    "http_req_duration{name:join}": ["p(95)<1000"],
    "http_req_duration{name:hold}": ["p(95)<1000"],
    http_req_failed: ["rate<0.01"],
  },
};

// A full coach (409) is an answer, not a failure.
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 299 }, 409));

export function setup() {
  const sales = http.get(`${BASE}/api/sales`).json();
  if (!sales.length) throw new Error("no sale is open: start katar with --katar.demo=true");
  return { sale: sales[0].id };
}

export default function ({ sale }) {
  const vu = __VU;
  const ip = `10.${(vu >> 16) & 255}.${(vu >> 8) & 255}.${vu & 255}`;
  const nid = String(1990000000000 + vu);
  const json = { "content-type": "application/json", "x-forwarded-for": ip };
  sleep(Math.random() * SPREAD);

  const joined = http.post(`${BASE}/api/sales/${sale}/queue`, JSON.stringify({ nationalId: nid }),
    { headers: json, tags: { name: "join" } });
  if (!check(joined, { joined: (r) => r.status === 200 })) return;
  const token = joined.json("token");
  const start = Date.now();

  let place = joined.json("place");
  while (!place.admitted && Date.now() - start < 240000) {
    sleep(0.5);
    place = http.get(`${BASE}/api/sales/${sale}/queue/me`,
      { headers: { "x-queue-token": token, "x-forwarded-for": ip }, tags: { name: "place" } }).json();
  }
  if (!place.admitted) return;
  waited.add(Date.now() - start);

  const pass = http.post(`${BASE}/api/sales/${sale}/pass`, null,
    { headers: { "x-queue-token": token, "x-forwarded-for": ip }, tags: { name: "pass" } }).json("token");
  const auth = { ...json, authorization: `Pass ${pass}` };

  const a = Math.floor(Math.random() * (STOPS.length - 1));
  const b = a + 1 + Math.floor(Math.random() * (STOPS.length - 1 - a));
  const coach = COACHES[Math.floor(Math.random() * COACHES.length)];
  const holds = [];
  for (let i = 1 + Math.floor(Math.random() * 2); i > 0; i--) {
    const r = http.post(`${BASE}/api/sales/${sale}/holds`,
      JSON.stringify({ from: STOPS[a], to: STOPS[b], coach }), { headers: auth, tags: { name: "hold" } });
    if (r.status === 201) {
      holds.push(r.json("id"));
      seatsHeld.add(1);
    } else {
      coachFull.add(1);
      break;
    }
  }
  if (!holds.length) return;

  const booking = http.post(`${BASE}/api/sales/${sale}/bookings`, JSON.stringify({ holds }),
    { headers: { ...auth, "idempotency-key": `rush-${vu}` }, tags: { name: "book" } });
  if (!check(booking, { booked: (r) => r.status === 201 })) return;
  const pay = http.post(`${BASE}/api/bookings/${booking.json("id")}/demo-pay`, null,
    { headers: { "x-forwarded-for": ip }, tags: { name: "pay" } });
  if (check(pay, { paid: (r) => r.json("outcome") === "PAID" })) paid.add(1);
}
