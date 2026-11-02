package dev.nafiz.katar.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import dev.nafiz.katar.queue.Admitter;

/**
 * The background work. Off when {@code katar.scheduling=false}, which the
 * tests use so they can run each step by hand at a moment they choose.
 */
@Component
@ConditionalOnProperty(name = "katar.scheduling", havingValue = "true", matchIfMissing = true)
public class Jobs {

    private final Admitter admitter;

    public Jobs(Admitter admitter) {
        this.admitter = admitter;
    }

    @Scheduled(fixedDelayString = "${katar.admitter-interval}")
    void admit() {
        admitter.tick();
    }
}
