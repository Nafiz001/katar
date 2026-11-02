package dev.nafiz.katar;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

/**
 * Tests against a real PostgreSQL 18, started once for the whole run. The
 * concurrency guarantees under test live in SQL, so a fake database would
 * prove nothing. Scheduled jobs are off; tests drive them by hand.
 */
@SpringBootTest(properties = "katar.scheduling=false")
@Import(DatabaseTest.TestClock.class)
public abstract class DatabaseTest {

    private static final EmbeddedPostgres PG;

    static {
        try {
            PG = EmbeddedPostgres.builder().start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                PG.close();
            } catch (IOException ignored) {
                // the process is ending anyway
            }
        }));
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> PG.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "40");
    }

    /** 07:59 on the morning a sale opens, Bangladesh time. */
    public static final Instant T0 = Instant.parse("2027-03-20T01:59:00Z");

    @TestConfiguration
    public static class TestClock {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(T0);
        }
    }

    @Autowired
    protected JdbcClient db;
    @Autowired
    protected MutableClock clock;
    @Autowired
    private dev.nafiz.katar.queue.Sales salesCache;
    @Autowired
    private dev.nafiz.katar.inventory.Journeys journeysCache;
    @Autowired
    private dev.nafiz.katar.queue.WaitingRoom roomCache;

    /** Every test starts from the reference data alone, at T0. */
    @BeforeEach
    void clean() {
        clock.set(T0);
        salesCache.clearCache();
        journeysCache.clearCache();
        roomCache.clearCache();
        db.sql("TRUNCATE ticket, booking, hold, queue_entry, sale, seat, journey RESTART IDENTITY CASCADE").update();
        // each sale made its own ticket sequence, which a truncate leaves behind
        db.sql("""
                DO $$ DECLARE s text; BEGIN
                  FOR s IN SELECT sequencename FROM pg_sequences WHERE sequencename LIKE 'sale\\_%\\_ticket' LOOP
                    EXECUTE format('DROP SEQUENCE %I', s);
                  END LOOP;
                END $$
                """).update();
    }
}
