package dev.nafiz.katar.booking;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.EdECPrivateKey;
import java.security.spec.NamedParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import dev.nafiz.katar.config.KatarProperties;

/**
 * Signs tickets with Ed25519. A ticket code is {@code payload.signature}; an
 * inspector's phone holds only the public key, so it can check any ticket
 * with no network, and a lost phone cannot be used to forge one.
 */
@Component
public class TicketSigner {

    private static final Logger log = LoggerFactory.getLogger(TicketSigner.class);
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final PrivateKey privateKey;
    private final PublicKey publicKey;

    public TicketSigner(KatarProperties props) {
        try {
            KeyPair pair = props.ticketPrivateKey() == null || props.ticketPrivateKey().isBlank()
                    ? generate()
                    : load(props.ticketPrivateKey());
            this.privateKey = pair.getPrivate();
            this.publicKey = pair.getPublic();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot set up the ticket key", e);
        }
    }

    public String sign(String payload) {
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(privateKey);
            s.update(body);
            return B64.encodeToString(body) + "." + B64.encodeToString(s.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The payload of a genuine ticket code; empty if forged, altered or malformed. */
    public Optional<String> verify(String code) {
        int dot = code == null ? -1 : code.indexOf('.');
        if (dot <= 0) {
            return Optional.empty();
        }
        try {
            byte[] body = B64D.decode(code.substring(0, dot));
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(publicKey);
            s.update(body);
            return s.verify(B64D.decode(code.substring(dot + 1)))
                    ? Optional.of(new String(body, StandardCharsets.UTF_8))
                    : Optional.empty();
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            return Optional.empty();
        }
    }

    /** The public key (X.509, base64): all an inspector app needs. */
    public String publicKeyBase64() {
        return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }

    private static KeyPair generate() throws GeneralSecurityException {
        log.warn("No KATAR_TICKET_PRIVATE_KEY set: tickets are signed with a key made at startup and "
                + "will not verify after a restart");
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private static KeyPair load(String base64) throws GeneralSecurityException {
        KeyFactory kf = KeyFactory.getInstance("Ed25519");
        EdECPrivateKey priv = (EdECPrivateKey) kf.generatePrivate(
                new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64.trim())));
        // The JDK has no call that derives an EdDSA public key from a private
        // one, but its generator turns 32 random bytes into the private key:
        // fed the same bytes, it reproduces the matching public key.
        byte[] seed = priv.getBytes().orElseThrow();
        KeyPairGenerator gen = KeyPairGenerator.getInstance("Ed25519");
        gen.initialize(NamedParameterSpec.ED25519, new FixedSeed(seed));
        KeyPair pair = gen.generateKeyPair();
        return new KeyPair(pair.getPublic(), priv);
    }

    /** A SecureRandom that returns one fixed seed: how the JDK derives an Ed25519 public key. */
    private static final class FixedSeed extends java.security.SecureRandom {
        private final byte[] seed;

        FixedSeed(byte[] seed) {
            this.seed = seed.clone();
        }

        @Override
        public void nextBytes(byte[] bytes) {
            System.arraycopy(seed, 0, bytes, 0, Math.min(seed.length, bytes.length));
        }
    }
}
