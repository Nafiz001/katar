package dev.nafiz.katar.queue;

import org.springframework.stereotype.Component;

import dev.nafiz.katar.config.KatarException;
import dev.nafiz.katar.config.KatarProperties;
import dev.nafiz.katar.config.Signer;

/**
 * What a browser keeps after joining a queue: a signed note of which sale and
 * which person. With it the page can ask for its place, stream updates and
 * claim its pass without sending the national ID again.
 */
@Component
public class QueueTokens {

    private final Signer signer;

    public QueueTokens(KatarProperties props) {
        // its own key derivation, so a queue token can never be passed off as an admission pass
        this.signer = new Signer("queue|" + props.passSecret());
    }

    public String issue(long saleId, String person) {
        return signer.sign(saleId + "|" + person);
    }

    /** The person a token belongs to, if it is genuine and for this sale. */
    public String person(long saleId, String token) {
        String[] parts = signer.open(token).map(p -> p.split("\\|")).filter(p -> p.length == 2)
                .orElseThrow(() -> KatarException.forbidden("bad_queue_token", "that queue token is not valid"));
        if (Long.parseLong(parts[0]) != saleId) {
            throw KatarException.forbidden("bad_queue_token", "that queue token is for a different sale");
        }
        return parts[1];
    }
}
