package dev.nafiz.katar.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import dev.nafiz.katar.config.KatarProperties;

class TicketSignerTest {

    /** Made with: openssl genpkey -algorithm ed25519 -outform DER | base64 (as the server's init-env.sh does). */
    static final String OPENSSL_KEY = "MC4CAQAwBQYDK2VwBCIEIMyDAjMb9GgUPex4SA4avJfryX1huJp8dAurUrJS8s5b";

    static KatarProperties props(String key) {
        Duration d = Duration.ofMinutes(1);
        return new KatarProperties("p", key, "pepper", "pay", d, d, d, 4, d, d);
    }

    @Test
    void aKeyFromOpensslSignsTicketsThatVerify() {
        TicketSigner signer = new TicketSigner(props(OPENSSL_KEY));
        String code = signer.sign("katar1|x|753|2027-03-30|KA-1:DHK-RJS|0");
        assertThat(signer.verify(code)).contains("katar1|x|753|2027-03-30|KA-1:DHK-RJS|0");
    }

    @Test
    void theSameKeyAlwaysGivesTheSamePublicKey() {
        assertThat(new TicketSigner(props(OPENSSL_KEY)).publicKeyBase64())
                .isEqualTo(new TicketSigner(props(OPENSSL_KEY)).publicKeyBase64());
    }

    @Test
    void anotherKeyCannotVerifyIt() {
        String code = new TicketSigner(props(OPENSSL_KEY)).sign("ticket");
        assertThat(new TicketSigner(props("")).verify(code)).isEmpty();
    }
}
