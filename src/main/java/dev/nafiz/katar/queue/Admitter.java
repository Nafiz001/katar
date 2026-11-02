package dev.nafiz.katar.queue;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.nafiz.katar.config.KatarProperties;
import dev.nafiz.katar.queue.Sales.Sale;

/**
 * Lets people in. Once a second, for every open sale: shuffles the early
 * joiners if the sale has just opened, expires passes whose time is up, and
 * moves the watermark forward by at most {@code admit_per_sec} tickets, and
 * never further than the booking room has space for.
 */
@Service
public class Admitter {

    private static final Logger log = LoggerFactory.getLogger(Admitter.class);

    private final JdbcClient db;
    private final Sales sales;
    private final Clock clock;
    private final KatarProperties props;

    public Admitter(JdbcClient db, Sales sales, Clock clock, KatarProperties props) {
        this.db = db;
        this.sales = sales;
        this.clock = clock;
        this.props = props;
    }

    public void tick() {
        Instant now = clock.instant();
        for (Sale sale : sales.openAt(now)) {
            if (!sale.shuffled()) {
                shuffle(sale.id());
            }
            admit(sale.id(), now);
        }
    }

    /**
     * Gives every early joiner a ticket, in random order, then points the
     * sale's sequence past them so later joiners queue behind. Holds the
     * sale's advisory lock exclusively so no join can interleave.
     */
    @Transactional
    public void shuffle(long saleId) {
        db.sql("SELECT pg_advisory_xact_lock(:key)").param("key", WaitingRoom.lockKey(saleId)).query(Object.class).single();
        Sale sale = sales.get(saleId);
        if (sale.shuffled()) {
            return;
        }
        int early = db.sql("""
                WITH draw AS (
                    SELECT id, row_number() OVER (ORDER BY random()) AS n
                    FROM queue_entry WHERE sale_id = :sale AND ticket IS NULL
                )
                UPDATE queue_entry q SET ticket = draw.n FROM draw WHERE q.id = draw.id
                """)
                .param("sale", saleId)
                .update();
        if (early > 0) {
            db.sql("SELECT setval('" + sale.ticketSeq() + "', :n)").param("n", early).query(Long.class).single();
        }
        db.sql("UPDATE sale SET shuffled = true WHERE id = :sale").param("sale", saleId).update();
        log.info("sale {} opened: {} early joiners shuffled", saleId, early);
    }

    /**
     * Expires spent passes and unclaimed turns, then admits as many as the
     * rate and the free room allow.
     */
    @Transactional
    public long admit(long saleId, Instant now) {
        db.sql("""
                UPDATE queue_entry SET state = 'EXPIRED'
                WHERE sale_id = :sale
                  AND ((state = 'ADMITTED' AND pass_expires <= :now)
                    OR (state = 'WAITING' AND offered_at <= :unclaimed))
                """)
                .param("sale", saleId)
                .param("now", Timestamp.from(now))
                .param("unclaimed", Timestamp.from(now.minus(props.claimWindow())))
                .update();
        // The sale row is locked for this short transaction: only one admitter
        // moves a watermark at a time, even with several app instances.
        Sale sale = db.sql("SELECT id FROM sale WHERE id = :sale FOR UPDATE").param("sale", saleId)
                .query(Long.class).optional().map(sales::get).orElseThrow();
        long active = db.sql("""
                SELECT count(*) FROM queue_entry
                WHERE sale_id = :sale AND state = 'ADMITTED' AND pass_expires > :now
                """)
                .param("sale", saleId)
                .param("now", Timestamp.from(now))
                .query(Long.class)
                .single();
        // Offered but not yet claimed: they may arrive any moment, so they
        // count against the room until their claim window runs out.
        long pending = db.sql("""
                SELECT count(*) FROM queue_entry
                WHERE sale_id = :sale AND state = 'WAITING' AND offered_at IS NOT NULL
                """)
                .param("sale", saleId)
                .query(Long.class)
                .single();
        long issued = issued(sale);
        long room = Math.max(0, sale.maxActive() - active - pending);
        long next = Math.min(sale.admittedUpto() + Math.min(sale.admitPerSec(), room), issued);
        if (next > sale.admittedUpto()) {
            db.sql("UPDATE sale SET admitted_upto = :next WHERE id = :sale").param("next", next).param("sale", saleId)
                    .update();
            db.sql("""
                    UPDATE queue_entry SET offered_at = :now
                    WHERE sale_id = :sale AND ticket > :from AND ticket <= :to
                    """)
                    .param("now", Timestamp.from(now))
                    .param("sale", saleId)
                    .param("from", sale.admittedUpto())
                    .param("to", next)
                    .update();
        }
        return next;
    }

    /** The highest ticket handed out so far. */
    private long issued(Sale sale) {
        return db.sql("SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM " + sale.ticketSeq())
                .query(Long.class)
                .single();
    }
}
