package com.jw.ignition.license.crypto;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public final class RsaPublicKeys {
    private static final String PUBLIC_KEY_BEGIN = "-----BEGIN PUBLIC KEY-----";
    private static final String PUBLIC_KEY_END = "-----END PUBLIC KEY-----";

    // Need to update this integer key if PublicKey changed/updated
    private static final int PUBLIC_KEY_VERSION = 1;

    private static final String EETARP_RSA_PUBLIC_KEY = """
            -----BEGIN PUBLIC KEY-----
            MIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAtjtJvCw5Ev1o33e/ATIT
            ahKc4MIXQM3EsdGqK29/YCrfGRY7wOOrFWYiCapU5wSevDqoKrLDU8IrRGSOExb5
            75rActcAJD1tUUIMo8Mv7SxIdYRrMAAu3bSlrE1ehzUaemRBSaj3NL84LgetrMXN
            xw0Ll2P4HrAAUnMs5ewdiZ/ijPqWoinwuxC64y2wcUuG/ognHCGWCrTeypW8uWpV
            baEGxW0cYiTCJiTUqw+dUgyoCYm7JXsOv/7YDS+DJqyqIsLO8/EiLSCD/2JJyC2y
            fEBObeSL7YyvksGLxjEAy1jyZO3CmqoOapS0V6M8xcVGwDb0+KAUGr5jHZ0CCe9G
            SWuIRjNQeUz0Y7QOIRSUe3zKnxNDXFuQdTj2Zja42R+C5/HZ1u6EAhhe1o2rOjur
            7qGsFB3q/71EqnXhhSGmwP4tzvDRBBeJC6Iv70lFklSrnHylhv/TNUt+M3QGSHVO
            Pjyx2Xr2ffMHxJnNZ5U8aVQmLmA626bsecRMOtAV8Afyx4zkC55TuqP5RYYhvOZC
            3R7AWO2trB1hI5vpGRZ48JL85F7YZ5zKGB29QiqYxd56++xhwS7AwsBMSpq2pXto
            Lyza6j7h8aXVdpMIqnu3q7RFxFTAXLKWGJ9fHdU36YhYCP9GolSpG8pq0w3lZpMH
            +hA5nVwxApi5U5qZUe3k1ZUCAwEAAQ==
            -----END PUBLIC KEY-----
            """;

    private RsaPublicKeys() {
    }

    public static PublicKey getPublicKey() {
        String base64 = EETARP_RSA_PUBLIC_KEY
            .replace(PUBLIC_KEY_BEGIN, "")
            .replace(PUBLIC_KEY_END, "")
            .replaceAll("\\s+", "");
        return fromBase64(base64);
    }

    public static PublicKey fromBase64(String base64) {
        if (base64 == null || base64.isBlank()) {
            throw new IllegalArgumentException("RSA public key content is required.");
        }
        try {
            byte[] encoded = Base64.getDecoder().decode(base64);
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(encoded));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid RSA public key.", e);
        }
    }

    public static String getPublicKeyVersion() {
        return String.valueOf(PUBLIC_KEY_VERSION);
    }
}
