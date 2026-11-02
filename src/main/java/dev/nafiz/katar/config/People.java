package dev.nafiz.katar.config;

import org.springframework.stereotype.Component;

/**
 * Turns a national ID number into the key katar stores. The database only
 * ever sees a keyed hash, so a leaked table does not leak ID numbers, and
 * without the pepper the hashes cannot be brute-forced from the small space
 * of valid IDs.
 */
@Component
public class People {

    private final Signer signer;

    public People(KatarProperties props) {
        this.signer = new Signer(props.personPepper());
    }

    public String key(String nationalId) {
        String digits = nationalId == null ? "" : nationalId.replaceAll("[^0-9]", "");
        // Bangladesh NIDs are 10, 13 or 17 digits; birth registration numbers are 17
        if (!(digits.length() == 10 || digits.length() == 13 || digits.length() == 17)) {
            throw new IllegalArgumentException("a national ID has 10, 13 or 17 digits");
        }
        return signer.hex(digits);
    }
}
