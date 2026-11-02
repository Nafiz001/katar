-- After a rush: did anything get sold twice? Every number here must be 0.

-- Two live holds on one seat whose legs overlap.
SELECT count(*) AS overlapping_holds
FROM hold a JOIN hold b ON a.seat_id = b.seat_id AND a.id < b.id
WHERE a.state IN ('HELD', 'BOOKED') AND b.state IN ('HELD', 'BOOKED')
  AND (a.mask & b.mask) <> 0;

-- A seat whose recorded legs differ from the legs its live holds claim.
SELECT count(*) AS seats_out_of_step
FROM seat s
LEFT JOIN (SELECT seat_id, bit_or(mask) AS legs FROM hold WHERE state IN ('HELD', 'BOOKED') GROUP BY seat_id) h
       ON h.seat_id = s.id
WHERE s.taken <> coalesce(h.legs, 0);

-- Anyone holding more than four seats.
SELECT count(*) AS over_the_limit
FROM (SELECT person FROM hold WHERE state IN ('HELD', 'BOOKED') GROUP BY sale_id, person HAVING count(*) > 4) t;

-- What was sold.
SELECT (SELECT count(*) FROM booking WHERE state = 'PAID') AS paid_bookings,
       (SELECT count(*) FROM hold WHERE state = 'BOOKED') AS seats_sold;
