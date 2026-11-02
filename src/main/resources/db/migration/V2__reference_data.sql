-- A sample timetable for one intercity train, Dhaka to Rajshahi, with real
-- station names. Times and fares are illustrative, not Bangladesh Railway's.

INSERT INTO station (code, name, name_bn) VALUES
    ('DHK', 'Dhaka Kamalapur',      'ঢাকা কমলাপুর'),
    ('APT', 'Biman Bandar',         'বিমানবন্দর'),
    ('JDP', 'Joydebpur',            'জয়দেবপুর'),
    ('TGL', 'Tangail',              'টাঙ্গাইল'),
    ('BBE', 'Bangabandhu Setu East','বঙ্গবন্ধু সেতু পূর্ব'),
    ('ULP', 'Ullapara',             'উল্লাপাড়া'),
    ('CMH', 'Chatmohar',            'চাটমোহর'),
    ('ISD', 'Ishwardi',             'ঈশ্বরদী'),
    ('ADP', 'Abdulpur',             'আব্দুলপুর'),
    ('RJS', 'Rajshahi',             'রাজশাহী');

INSERT INTO train (number, name) VALUES (753, 'Silk City Express');

INSERT INTO route_stop (train_id, seq, station_id, depart_min)
SELECT t.id, s.seq, st.id, s.depart_min
FROM train t,
     (VALUES (0, 'DHK', 0), (1, 'APT', 28), (2, 'JDP', 60), (3, 'TGL', 135), (4, 'BBE', 175),
             (5, 'ULP', 215), (6, 'CMH', 260), (7, 'ISD', 300), (8, 'ADP', 335), (9, 'RJS', 385))
         AS s(seq, code, depart_min)
JOIN station st ON st.code = s.code
WHERE t.number = 753;

INSERT INTO coach (train_id, code, class, seat_count, position)
SELECT t.id, c.code, c.class, c.seats, c.pos
FROM train t,
     (VALUES ('KA', 'SHOVON_CHAIR', 75, 1), ('KHA', 'SHOVON_CHAIR', 75, 2), ('GA', 'SHOVON_CHAIR', 75, 3),
             ('GHA', 'SNIGDHA', 55, 4), ('UMA', 'SNIGDHA', 55, 5), ('CHA', 'AC_SEAT', 50, 6))
         AS c(code, class, seats, pos)
WHERE t.number = 753;

INSERT INTO fare (train_id, class, per_leg_poisha)
SELECT t.id, f.class, f.poisha
FROM train t,
     (VALUES ('SHOVON_CHAIR', 4500), ('SNIGDHA', 8600), ('AC_SEAT', 10400)) AS f(class, poisha)
WHERE t.number = 753;
