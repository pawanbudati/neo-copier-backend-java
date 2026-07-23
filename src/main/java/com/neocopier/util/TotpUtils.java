package com.neocopier.util;

import org.apache.commons.codec.binary.Base32;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.time.Instant;

public class TotpUtils {

    private static final int TIME_STEP_SECONDS = 30;
    private static final int DIGITS = 6;

    public static boolean hasAutoTotpSecret(String secret) {
        if (secret == null) {
            return false;
        }
        String clean = secret.replaceAll("\\s+", "");
        return !clean.isEmpty() && clean.length() >= 16;
    }

    public static String generateTotp(String secret) {
        if (secret == null || secret.trim().isEmpty()) {
            throw new IllegalArgumentException("TOTP secret cannot be null or empty.");
        }
        String cleanSecret = secret.replaceAll("\\s+", "").toUpperCase();
        Base32 base32 = new Base32();
        byte[] key = base32.decode(cleanSecret);
        if (key == null || key.length == 0) {
            key = cleanSecret.getBytes();
        }

        long timeWindow = Instant.now().getEpochSecond() / TIME_STEP_SECONDS;
        return generateTotpForTimeWindow(key, timeWindow);
    }

    private static String generateTotpForTimeWindow(byte[] key, long timeWindow) {
        byte[] data = ByteBuffer.allocate(8).putLong(timeWindow).array();
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(data);

            int offset = hash[hash.length - 1] & 0xF;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);

            int otp = binary % (int) Math.pow(10, DIGITS);
            return String.format("%0" + DIGITS + "d", otp);
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Failed to generate TOTP: " + e.getMessage(), e);
        }
    }
}
