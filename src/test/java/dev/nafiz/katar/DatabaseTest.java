package dev.nafiz.katar;

import java.io.IOException;
import java.io.UncheckedIOException;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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

    @Autowired
    protected JdbcClient db;

    /** Every test starts from the reference data alone. */
    @BeforeEach
    void clean() {
        db.sql("TRUNCATE ticket, booking, hold, queue_entry, sale, seat, journey RESTART IDENTITY CASCADE").update();
    }
}
