package dev.nafiz.katar.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * All of katar's time comes from this clock and is passed into SQL as a
 * parameter, never read with now() inside a query, so tests can move time
 * forward instead of sleeping.
 */
@Configuration
public class TimeConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
