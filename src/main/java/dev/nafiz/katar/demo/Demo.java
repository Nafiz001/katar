package dev.nafiz.katar.demo;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import dev.nafiz.katar.booking.Bookings;
import dev.nafiz.katar.booking.Holds;
import dev.nafiz.katar.booking.Holds.Hold;
import dev.nafiz.katar.booking.Payments;
import dev.nafiz.katar.config.KatarException;
import dev.nafiz.katar.inventory.Journeys;
import dev.nafiz.katar.queue.Sales;
import dev.nafiz.katar.queue.Sales.Sale;
import dev.nafiz.katar.queue.WaitingRoom;
import dev.nafiz.katar.queue.WaitingRoom.Admitted;

/**
 * The public demo: an hourly sale with a crowd in front of it.
 *
 * <p>At the top of every hour the demo sale for the Silk City Express, ten
 * days ahead (how far ahead Bangladesh Railway sells), starts afresh. It opens
 * a minute later, so visitors who arrive first see the pre-opening wait and
 * the shuffle. Simulated buyers then keep a few hundred people in the queue,
 * and once let in they hold seats on random stretches of the route and mostly
 * pay, so a visitor sees a real place in line and a seat map filling up.
 *
 * <p>Bots use the same services as people, minus HTTP. Their person keys are
 * not national-ID hashes and cannot collide with one.
 */
@Component
@ConditionalOnProperty(name = "katar.demo", havingValue = "true")
public class Demo {

    private static final Logger log = LoggerFactory.getLogger(Demo.class);
    private static final ZoneId DHAKA = ZoneId.of("Asia/Dhaka");
    private static final int TRAIN = 753;
    private static final int WAITING_TARGET = 160;
    /** Bots stop buying once this share of the train's seat-legs is gone, so visitors always find seats. */
    private static final double BOT_SHARE = 0.55;
    private static final String[] STOPS = { "DHK", "APT", "JDP", "TGL", "BBE", "ULP", "CMH", "ISD", "ADP", "RJS" };
    private static final String[] COACHES = { "KA", "KHA", "GA", "GHA", "UMA", "CHA" };

    private final JdbcClient db;
    private final Journeys journeys;
    private final Sales sales;
    private final WaitingRoom waitingRoom;
    private final Holds holds;
    private final Bookings bookings;
    private final Payments payments;
    private final Clock clock;
    /** Admissions per second and room size of the demo sale; a load test raises both. */
    private final int admitPerSec;
    private final int room;
    /** The simulated crowd; a load test turns it off and brings its own. */
    private final boolean crowd;

    public Demo(JdbcClient db, Journeys journeys, Sales sales, WaitingRoom room, Holds holds, Bookings bookings,
            Payments payments, Clock clock, @Value("${katar.demo-admit:5}") int admitPerSec,
            @Value("${katar.demo-room:150}") int roomSize, @Value("${katar.demo-crowd:true}") boolean crowd) {
        this.admitPerSec = admitPerSec;
        this.room = roomSize;
        this.crowd = crowd;
        this.db = db;
        this.journeys = journeys;
        this.sales = sales;
        this.waitingRoom = room;
        this.holds = holds;
        this.bookings = bookings;
        this.payments = payments;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        ensureCurrentSale();
    }

    /** At the top of every hour: a fresh sale. Checked every minute, so a restart catches up. */
    @Scheduled(cron = "0 * * * * *")
    void hourly() {
        ensureCurrentSale();
    }

    /**
     * Not one transaction, and it need not be: every step can be repeated,
     * so a crash halfway is finished by the next minute's check.
     */
    void ensureCurrentSale() {
        Instant now = clock.instant();
        Instant hour = now.truncatedTo(ChronoUnit.HOURS);
        LocalDate date = LocalDate.ofInstant(now, DHAKA).plusDays(10);
        long journey = journeys.ensure(TRAIN, date);
        Sale current = db.sql("SELECT id FROM sale WHERE journey_id = :j").param("j", journey)
                .query(Long.class).optional().map(sales::get).orElse(null);
        if (current != null && !current.opensAt().isBefore(hour)) {
            return; // this hour's sale already exists
        }
        if (current != null) {
            wipe(current);
        }
        // Yesterday's demo journey may still hold an old sale; it is left as history.
        Sale sale = sales.create(journey, hour.plusSeconds(60), hour.plus(1, ChronoUnit.HOURS), admitPerSec, room);
        log.info("demo: sale {} for train {} on {} opens at {}", sale.id(), TRAIN, date, sale.opensAt());
    }

    /** Clears a sale and everything bought in it, and frees its seats. */
    private void wipe(Sale sale) {
        db.sql("DELETE FROM ticket WHERE booking_id IN (SELECT id FROM booking WHERE sale_id = :s)").param("s", sale.id()).update();
        db.sql("DELETE FROM hold WHERE sale_id = :s").param("s", sale.id()).update();
        db.sql("DELETE FROM booking WHERE sale_id = :s").param("s", sale.id()).update();
        db.sql("DELETE FROM queue_entry WHERE sale_id = :s").param("s", sale.id()).update();
        db.sql("DELETE FROM sale WHERE id = :s").param("s", sale.id()).update();
        db.sql("DROP SEQUENCE IF EXISTS " + sale.ticketSeq()).update();
        db.sql("UPDATE seat SET taken = 0 WHERE journey_id = :j").param("j", sale.journeyId()).update();
    }

    /** Once a second: top up the crowd, then let admitted bots shop. */
    @Scheduled(fixedDelay = 1000, initialDelay = 5000)
    void crowd() {
        if (!crowd) {
            return;
        }
        Instant now = clock.instant();
        for (Sale sale : sales.openAt(now)) {
            long waiting = db.sql("""
                    SELECT count(*) FROM queue_entry
                    WHERE sale_id = :s AND state = 'WAITING' AND offered_at IS NULL
                    """).param("s", sale.id()).query(Long.class).single();
            ThreadLocalRandom r = ThreadLocalRandom.current();
            int joins = waiting < WAITING_TARGET ? r.nextInt(8, 16) : r.nextInt(0, 3);
            for (int i = 0; i < joins; i++) {
                waitingRoom.join(sale.id(), "bot-" + UUID.randomUUID());
            }
            double sold = db.sql("""
                    SELECT coalesce(sum(bit_count(taken::bit(64))), 0)::float / (count(*) * :legs)
                    FROM seat WHERE journey_id = :j
                    """).param("legs", STOPS.length - 1).param("j", sale.journeyId()).query(Double.class).single();
            boolean buy = sold < BOT_SHARE;
            List<String> turn = db.sql("""
                    SELECT person FROM queue_entry
                    WHERE sale_id = :s AND state = 'WAITING' AND offered_at IS NOT NULL AND person LIKE 'bot-%'
                    LIMIT 20
                    """).param("s", sale.id()).query(String.class).list();
            for (String bot : turn) {
                shop(sale, bot, r, buy);
            }
        }
    }

    /** One bot's visit: claim the pass, hold one to four seats, and usually pay. */
    private void shop(Sale sale, String bot, ThreadLocalRandom r, boolean buy) {
        try {
            Admitted who = waitingRoom.verify(sale.id(), waitingRoom.claimPass(sale.id(), bot).token());
            if (!buy) {
                waitingRoom.finish(sale.id(), bot); // looked, found it busy, left
                return;
            }
            int a = r.nextInt(0, STOPS.length - 1);
            int b = r.nextInt(a + 1, STOPS.length);
            String coach = COACHES[r.nextInt(COACHES.length)];
            List<UUID> held = new java.util.ArrayList<>();
            for (int i = r.nextInt(1, 3); i > 0; i--) {
                try {
                    Hold h = holds.hold(who, STOPS[a], STOPS[b], coach, null);
                    held.add(h.id());
                } catch (KatarException full) {
                    break;
                }
            }
            if (held.isEmpty() || r.nextInt(10) < 2) {
                for (UUID h : held) {
                    holds.release(who, h);
                }
                waitingRoom.finish(sale.id(), bot); // gave up
                return;
            }
            payments.payDemo(bookings.create(who, "bot-" + UUID.randomUUID(), held).id());
        } catch (KatarException e) {
            waitingRoom.finish(sale.id(), bot);
        }
    }
}
