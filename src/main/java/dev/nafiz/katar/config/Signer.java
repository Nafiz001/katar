package dev.nafiz.katar.config;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 with one key: signs a payload into {@code payload.signature}
 * and checks such tokens. Used for admission passes, ticket codes and the
 * payment webhook, each with its own key.
 */
public final class Signer {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final SecretKeySpec key;

    public Signer(String secret) {
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    public byte[] mac(byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(data);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    public String hex(String data) {
        return HexFormat.of().formatHex(mac(data.getBytes(StandardCharsets.UTF_8)));
    }

    /** {@code base64url(payload) + "." + base64url(mac)}. */
    public String sign(String payload) {
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        return B64.encodeToString(body) + "." + B64.encodeToString(mac(body));
    }

    /** The payload of a token this signer made, or empty if it was altered or is malformed. */
    public Optional<String> open(String token) {
        if (token == null) {
            return Optional.empty();
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            return Optional.empty();
        }
        try {
            byte[] body = B64D.decode(token.substring(0, dot));
            byte[] sig = B64D.decode(token.substring(dot + 1));
            // constant time, so the comparison leaks nothing about the right signature
            if (!MessageDigest.isEqual(sig, mac(body))) {
                return Optional.empty();
            }
            return Optional.of(new String(body, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
