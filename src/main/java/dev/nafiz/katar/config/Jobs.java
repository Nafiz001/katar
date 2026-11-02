package dev.nafiz.katar.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import dev.nafiz.katar.booking.Holds;
import dev.nafiz.katar.queue.Admitter;

/**
 * The background work. Off when {@code katar.scheduling=false}, which the
 * tests use so they can run each step by hand at a moment they choose.
 */
@Component
@ConditionalOnProperty(name = "katar.scheduling", havingValue = "true", matchIfMissing = true)
public class Jobs {

    private final Admitter admitter;
    private final Holds holds;

    public Jobs(Admitter admitter, Holds holds) {
        this.admitter = admitter;
        this.holds = holds;
    }

    @Scheduled(fixedDelayString = "${katar.admitter-interval}")
    void admit() {
        admitter.tick();
    }

    /** Gives back seats whose holds ran out, in batches until none are due. */
    @Scheduled(fixedDelayString = "${katar.sweeper-interval}")
    void sweep() {
        while (holds.sweep() == 500) {
            // a full batch: there may be more due right now
        }
    }
}
