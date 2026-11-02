-- katar: the reference data (stations, trains, coaches), the inventory sold
-- for one run of a train, and the queue, holds and bookings of a sale.

CREATE TABLE station (
    id      smallserial PRIMARY KEY,
    code    text NOT NULL UNIQUE,
    name    text NOT NULL,
    name_bn text NOT NULL
);

CREATE TABLE train (
    id     smallserial PRIMARY KEY,
    number integer NOT NULL UNIQUE,
    name   text    NOT NULL
);

-- The stops in order. Leg i runs from stop i to stop i + 1, so a route of n
-- stops has n - 1 legs, and a journey from stop a to stop b covers legs
-- a .. b - 1: a contiguous run of bits.
CREATE TABLE route_stop (
    train_id   smallint NOT NULL REFERENCES train,
    seq        smallint NOT NULL CHECK (seq BETWEEN 0 AND 63),
    station_id smallint NOT NULL REFERENCES station,
    depart_min integer  NOT NULL, -- minutes after the train leaves its origin
    PRIMARY KEY (train_id, seq),
    UNIQUE (train_id, station_id)
);

CREATE TABLE coach (
    id         smallserial PRIMARY KEY,
    train_id   smallint NOT NULL REFERENCES train,
    code       text     NOT NULL,          -- KA, KHA, GA ... as painted on the coach
    class      text     NOT NULL CHECK (class IN ('SHOVON_CHAIR', 'SNIGDHA', 'AC_SEAT')),
    seat_count smallint NOT NULL CHECK (seat_count > 0),
    position   smallint NOT NULL,          -- order along the platform
    UNIQUE (train_id, code)
);

-- Fare per leg, in poisha, by class.
CREATE TABLE fare (
    train_id      smallint NOT NULL REFERENCES train,
    class         text     NOT NULL,
    per_leg_poisha integer NOT NULL CHECK (per_leg_poisha > 0),
    PRIMARY KEY (train_id, class)
);

-- One run of a train on one date: what a sale sells.
CREATE TABLE journey (
    id           bigserial PRIMARY KEY,
    train_id     smallint NOT NULL REFERENCES train,
    service_date date     NOT NULL,
    UNIQUE (train_id, service_date)
);

-- The inventory. `taken` has bit i set while leg i of this seat is held or
-- sold. A hold is a single conditional UPDATE on this row (see SeatInventory):
-- it succeeds only if no requested bit is already set, so two overlapping
-- journeys can never both get the seat, and two disjoint ones both can.
CREATE TABLE seat (
    id         bigserial PRIMARY KEY,
    journey_id bigint   NOT NULL REFERENCES journey,
    coach_id   smallint NOT NULL REFERENCES coach,
    number     smallint NOT NULL,
    taken      bigint   NOT NULL DEFAULT 0,
    UNIQUE (journey_id, coach_id, number)
);
CREATE INDEX seat_by_journey ON seat (journey_id, coach_id);

-- A sale: tickets for one journey go on sale at a moment, and a waiting room
-- lets people in at a controlled rate.
CREATE TABLE sale (
    id            bigserial   PRIMARY KEY,
    journey_id    bigint      NOT NULL UNIQUE REFERENCES journey,
    opens_at      timestamptz NOT NULL,
    closes_at     timestamptz NOT NULL,
    admit_per_sec integer     NOT NULL CHECK (admit_per_sec > 0),
    max_active    integer     NOT NULL CHECK (max_active > 0),
    -- Everyone holding a queue ticket at or below this number may come in.
    -- Only the admitter moves it, and only forwards.
    admitted_upto bigint      NOT NULL DEFAULT 0,
    -- People who join before opening are ordered at random when it opens,
    -- so arriving early gains nothing and nobody camps on the page.
    shuffled      boolean     NOT NULL DEFAULT false,
    ticket_seq    text        NOT NULL  -- name of this sale's own sequence
);

-- A place in the queue. The ticket comes from the sale's own sequence, so
-- your position is simply ticket - admitted_upto: no counting, no reshuffles.
-- Early joiners have no ticket until the sale opens and they are shuffled.
-- One place per person per sale: rejoining returns the same place.
CREATE TABLE queue_entry (
    id           bigserial   PRIMARY KEY,
    sale_id      bigint      NOT NULL REFERENCES sale,
    ticket       bigint,
    person       text        NOT NULL, -- keyed hash of the national ID, never the ID itself
    joined_at    timestamptz NOT NULL,
    state        text        NOT NULL DEFAULT 'WAITING'
                 CHECK (state IN ('WAITING', 'ADMITTED', 'DONE', 'EXPIRED')),
    offered_at   timestamptz, -- when the watermark passed this ticket: the claim window starts
    admitted_at  timestamptz, -- when the pass was claimed
    pass_expires timestamptz,
    UNIQUE (sale_id, ticket),
    UNIQUE (sale_id, person)
);
CREATE INDEX queue_active ON queue_entry (sale_id, pass_expires) WHERE state = 'ADMITTED';
CREATE INDEX queue_offered ON queue_entry (sale_id, offered_at) WHERE state = 'WAITING' AND offered_at IS NOT NULL;

-- A seat held for a journey's legs while the buyer pays.
CREATE TABLE hold (
    id         uuid        PRIMARY KEY,
    sale_id    bigint      NOT NULL REFERENCES sale,
    seat_id    bigint      NOT NULL REFERENCES seat,
    person     text        NOT NULL,
    from_seq   smallint    NOT NULL,
    to_seq     smallint    NOT NULL CHECK (to_seq > from_seq),
    mask       bigint      NOT NULL,
    state      text        NOT NULL DEFAULT 'HELD'
               CHECK (state IN ('HELD', 'BOOKED', 'RELEASED', 'EXPIRED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    booking_id uuid
);
CREATE INDEX hold_expiring ON hold (expires_at) WHERE state = 'HELD';
CREATE INDEX hold_by_person ON hold (sale_id, person) WHERE state IN ('HELD', 'BOOKED');

-- A booking groups a person's held seats into one payment.
CREATE TABLE booking (
    id              uuid        PRIMARY KEY,
    sale_id         bigint      NOT NULL REFERENCES sale,
    person          text        NOT NULL,
    idempotency_key text        NOT NULL UNIQUE,
    request_hash    text        NOT NULL, -- the same key with a different body is refused
    amount_poisha   bigint      NOT NULL,
    state           text        NOT NULL DEFAULT 'AWAITING_PAYMENT'
                    CHECK (state IN ('AWAITING_PAYMENT', 'PAID', 'CANCELLED')),
    payment_ref     text UNIQUE,
    created_at      timestamptz NOT NULL DEFAULT now(),
    paid_at         timestamptz
);

-- An issued ticket. `code` is HMAC-signed, so an inspector's phone can check
-- it offline.
CREATE TABLE ticket (
    booking_id uuid        PRIMARY KEY REFERENCES booking,
    code       text        NOT NULL UNIQUE,
    issued_at  timestamptz NOT NULL DEFAULT now()
);
