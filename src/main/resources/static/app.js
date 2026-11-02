// katar booking page: route, queue, seats, ticket. Plain JavaScript, no build.
"use strict";

const $ = (id) => document.getElementById(id);
const BN = "০১২৩৪৫৬৭৮৯";
const bn = (n) => String(n).replace(/\d/g, (d) => BN[d]);
const taka = (poisha) => "৳" + (poisha / 100).toLocaleString("en-IN", { minimumFractionDigits: 2 });
const store = {
  get: (k) => { try { return localStorage.getItem(k); } catch { return null; } },
  set: (k, v) => { try { localStorage.setItem(k, v); } catch { /* private mode */ } },
  del: (k) => { try { localStorage.removeItem(k); } catch { /* private mode */ } },
};

const state = { sale: null, stops: [], from: 0, to: 0, token: null, pass: null, passExpires: null,
  coach: null, holds: new Map(), stream: null };

async function api(path, opts = {}) {
  const res = await fetch(path, { ...opts, headers: { "content-type": "application/json", ...(opts.headers || {}) } });
  const body = res.status === 204 ? null : await res.json().catch(() => null);
  if (!res.ok) {
    const err = new Error(body?.error?.message || `request failed (${res.status})`);
    err.code = body?.error?.code;
    throw err;
  }
  return body;
}

function show(id) {
  for (const s of ["step-join", "step-queue", "step-seats", "step-ticket"]) $(s).hidden = s !== id;
}

// ---------------------------------------------------------------- the train

async function start() {
  const sales = await api("/api/sales");
  if (!sales.length) {
    $("app").innerHTML = '<p class="lede">No sale is scheduled right now. The demo opens a new one every hour.</p>';
    return;
  }
  const s = sales[0];
  state.sale = await api(`/api/sales/${s.id}`);
  state.stops = state.sale.stops;
  state.from = 0;
  state.to = state.stops.length - 1;
  $("train-no").textContent = s.trainNumber;
  $("train-name").textContent = s.trainName;
  $("train-date").textContent = new Date(s.serviceDate + "T00:00:00").toLocaleDateString("en-GB",
    { weekday: "long", day: "numeric", month: "long", year: "numeric" });
  $("train").hidden = false;
  drawRoute();

  state.token = store.get(tokenKey());
  if (state.token) {
    try { return follow(await api(`/api/sales/${s.id}/queue/me`, { headers: { "X-Queue-Token": state.token } })); }
    catch { store.del(tokenKey()); state.token = null; }
  }
  const opens = new Date(state.sale.opensAt);
  $("join-lede").textContent = opens > new Date()
    ? `Sale opens at ${opens.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}. Everyone who joins before then is put in a random order when it opens, so there is no need to rush.`
    : "The sale is open. You join at the back of the line and move up as people are let in.";
  show("step-join");
}

const tokenKey = () => `katar-queue-${state.sale.id}`;

function drawRoute() {
  const ol = $("route");
  ol.innerHTML = "";
  state.stops.forEach((st, i) => {
    const li = document.createElement("li");
    if (i >= state.from && i <= state.to) li.classList.add("on");
    if (i === state.from) li.classList.add("from", "end");
    if (i === state.to) li.classList.add("to", "end");
    li.innerHTML = `<button type="button" aria-label="${st.name}"><span class="dot"></span>
      <span class="code">${st.code}</span><span class="name">${st.name}<br>${st.nameBn}</span></button>`;
    li.querySelector("button").onclick = () => pickStop(i);
    ol.append(li);
  });
  const a = state.stops[state.from], b = state.stops[state.to];
  $("route-hint").textContent = `${a.name} → ${b.name}. Tap a station to change where you board or get off.`;
}

let picking = "from";
function pickStop(i) {
  if (picking === "from" && i < state.stops.length - 1) {
    state.from = i;
    if (state.to <= i) state.to = state.stops.length - 1;
    picking = "to";
  } else if (i > state.from) {
    state.to = i;
    picking = "from";
  }
  drawRoute();
  if (!$("step-seats").hidden && state.holds.size === 0) loadCoaches();
}

// ---------------------------------------------------------------- the queue

$("join-form").onsubmit = async (e) => {
  e.preventDefault();
  $("join-err").textContent = "";
  try {
    const r = await api(`/api/sales/${state.sale.id}/queue`, { method: "POST",
      body: JSON.stringify({ nationalId: $("nid").value }) });
    state.token = r.token;
    store.set(tokenKey(), r.token);
    follow(r.place);
  } catch (err) {
    $("join-err").textContent = err.message;
  }
};

function follow(place) {
  show("step-queue");
  render(place);
  if (place.admitted) return admit();
  state.stream?.close();
  const src = new EventSource(`/api/sales/${state.sale.id}/queue/stream?token=${encodeURIComponent(state.token)}`);
  state.stream = src;
  src.addEventListener("place", (ev) => {
    const p = JSON.parse(ev.data);
    render(p);
    if (p.admitted) { src.close(); admit(); }
  });
  // the stream ends after a while or on a network blip: fall back to polling
  src.onerror = () => {
    src.close();
    setTimeout(async () => follow(await api(`/api/sales/${state.sale.id}/queue/me`,
      { headers: { "X-Queue-Token": state.token } })), 2000);
  };
}

function render(p) {
  if (p.ticket == null) {
    $("ahead-bn").textContent = "—";
    $("ahead-en").textContent = "";
    $("ahead-cap").textContent = "waiting for the sale to open · বিক্রি শুরুর অপেক্ষা";
    $("queue-note").textContent = "When it opens, everyone who is already here is shuffled into a random order.";
    return;
  }
  $("ahead-bn").textContent = bn(p.ahead);
  $("ahead-en").textContent = p.ahead.toLocaleString();
  $("ahead-cap").textContent = "people ahead of you · আপনার সামনে";
  $("queue-note").textContent = `Your number is ${p.ticket.toLocaleString()}. Keep this page open; it moves on its own.`;
}

async function admit() {
  try {
    const pass = await api(`/api/sales/${state.sale.id}/pass`, { method: "POST", headers: { "X-Queue-Token": state.token } });
    state.pass = pass.token;
    state.passExpires = new Date(pass.expiresAt);
    show("step-seats");
    tickPass();
    loadCoaches();
  } catch (err) {
    store.del(tokenKey());
    $("queue-note").textContent = err.message + " Reload the page to join again.";
  }
}

function tickPass() {
  const left = Math.max(0, state.passExpires - new Date());
  const m = Math.floor(left / 60000), s = Math.floor(left / 1000) % 60;
  $("pass-left").textContent = `You're in. ${m}:${String(s).padStart(2, "0")} left to book.`;
  if (left > 0 && !$("step-seats").hidden) setTimeout(tickPass, 1000);
}

// ---------------------------------------------------------------- seats

const legQuery = () => `from=${state.stops[state.from].code}&to=${state.stops[state.to].code}`;
const CLASS = { SHOVON_CHAIR: "Shovon chair", SNIGDHA: "Snigdha (AC chair)", AC_SEAT: "AC seat" };

async function loadCoaches() {
  const coaches = await api(`/api/sales/${state.sale.id}/availability?${legQuery()}`);
  const box = $("coaches");
  box.innerHTML = "";
  if (!state.coach || !coaches.some((c) => c.code === state.coach)) {
    state.coach = (coaches.find((c) => c.free > 0) || coaches[0]).code;
  }
  for (const c of coaches) {
    const b = document.createElement("button");
    b.type = "button";
    b.className = "coach-tab";
    b.setAttribute("role", "tab");
    b.setAttribute("aria-selected", String(c.code === state.coach));
    b.innerHTML = `<b>${c.code}</b><span>${CLASS[c.travelClass] || c.travelClass} · ${c.free} free</span>`;
    b.onclick = () => { state.coach = c.code; loadCoaches(); };
    box.append(b);
  }
  loadSeats();
}

async function loadSeats() {
  const seats = await api(`/api/sales/${state.sale.id}/coaches/${state.coach}?${legQuery()}`);
  const map = $("coach-map");
  map.innerHTML = "";
  seats.forEach((seat, i) => {
    if (i % 4 === 2) map.append(Object.assign(document.createElement("span"), { className: "aisle" }));
    const key = `${state.coach}-${seat.number}`;
    const b = document.createElement("button");
    b.type = "button";
    b.className = "seat" + (state.holds.has(key) ? " mine" : seat.free ? "" : " taken");
    b.textContent = seat.number;
    b.setAttribute("aria-label", `Seat ${key}, ${state.holds.has(key) ? "yours" : seat.free ? "free" : "taken"}`);
    b.disabled = !seat.free && !state.holds.has(key);
    b.onclick = () => toggleSeat(seat.number);
    map.append(b);
  });
  drawBasket();
}

async function toggleSeat(number) {
  const key = `${state.coach}-${number}`;
  $("seat-err").textContent = "";
  try {
    if (state.holds.has(key)) {
      await api(`/api/sales/${state.sale.id}/holds/${state.holds.get(key).id}`,
        { method: "DELETE", headers: { Authorization: "Pass " + state.pass } });
      state.holds.delete(key);
    } else {
      const h = await api(`/api/sales/${state.sale.id}/holds`, { method: "POST",
        headers: { Authorization: "Pass " + state.pass },
        body: JSON.stringify({ from: state.stops[state.from].code, to: state.stops[state.to].code, coach: state.coach, number }) });
      state.holds.set(key, h);
    }
  } catch (err) {
    $("seat-err").textContent = err.message;
  }
  loadCoaches();
}

function drawBasket() {
  const box = $("basket");
  box.innerHTML = "";
  if (!state.holds.size) return;
  const seats = [...state.holds.keys()].join(", ");
  const pay = Object.assign(document.createElement("button"), { type: "button", textContent: "Pay with the demo gateway" });
  pay.onclick = () => book(pay);
  box.append(Object.assign(document.createElement("span"), { textContent: `Held: ${seats}` }), pay);
}

async function book(btn) {
  btn.disabled = true;
  $("seat-err").textContent = "";
  try {
    // one key per basket: a double click or a retry after a timeout cannot book twice
    const ids = [...state.holds.values()].map((h) => h.id);
    const key = "web-" + ids.slice().sort().join("").slice(0, 96);
    const booking = await api(`/api/sales/${state.sale.id}/bookings`, { method: "POST",
      headers: { Authorization: "Pass " + state.pass, "Idempotency-Key": key }, body: JSON.stringify({ holds: ids }) });
    const paid = await api(`/api/bookings/${booking.id}/demo-pay`, { method: "POST" });
    if (paid.outcome !== "PAID" && paid.outcome !== "ALREADY_PAID") throw new Error("The seats expired before payment; it will be refunded.");
    ticket(paid.booking, booking.amountPoisha);
  } catch (err) {
    $("seat-err").textContent = err.message;
    btn.disabled = false;
  }
}

// ---------------------------------------------------------------- the ticket

async function ticket(b, amount) {
  store.del(tokenKey());
  show("step-ticket");
  const a = state.stops[state.from], z = state.stops[state.to];
  const seats = [...state.holds.keys()].map((k) => `<span>${k}</span>`).join("");
  $("ticket").innerHTML = `
    <article class="ticket">
      <div class="ticket-main">
        <p class="eyebrow">${$("train-date").textContent} · Train ${$("train-no").textContent}</p>
        <h3>${$("train-name").textContent}</h3>
        <p class="ticket-route">${a.name} → ${z.name}<br><span class="bn">${a.nameBn} → ${z.nameBn}</span></p>
        <div class="ticket-seats">${seats}</div>
        <p class="code">${b.ticketCode}</p>
      </div>
      <div class="ticket-stub"><span class="paid">PAID</span><strong>${taka(amount)}</strong><span class="fine">${b.id.slice(0, 8)}</span></div>
    </article>`;
  const v = await api(`/api/tickets/verify?code=${encodeURIComponent(b.ticketCode)}`);
  $("verify-note").textContent = v.valid
    ? "Signed with Ed25519. An inspector's phone checks it offline with katar's public key; change one character and it fails."
    : "This ticket did not verify.";
}

start().catch((err) => { $("app").innerHTML = `<p class="err">${err.message}</p>`; });
