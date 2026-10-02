package dev.railway;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

final class Passwords {
    static final int ITERATIONS = 600_000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private Passwords() { }

    static String hash(String password) {
        byte[] salt = new byte[16]; RANDOM.nextBytes(salt);
        return "pbkdf2-sha256$" + ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt)
                + "$" + Base64.getEncoder().encodeToString(derive(password, salt, ITERATIONS));
    }
    static boolean verify(String password, String encoded) {
        try {
            String[] fields = encoded.split("\\$");
            if (fields.length != 4 || !fields[0].equals("pbkdf2-sha256")) return false;
            int iterations = Integer.parseInt(fields[1]);
            if (iterations < ITERATIONS || iterations > 2_000_000) return false;
            return MessageDigest.isEqual(Base64.getDecoder().decode(fields[3]),
                    derive(password, Base64.getDecoder().decode(fields[2]), iterations));
        } catch (IllegalArgumentException e) { return false; }
    }
    private static byte[] derive(String password, byte[] salt, int iterations) {
        char[] chars = password.toCharArray();
        PBEKeySpec spec = new PBEKeySpec(chars, salt, iterations, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        catch (GeneralSecurityException e) { throw new IllegalStateException("Password hashing unavailable.", e); }
        finally { spec.clearPassword(); Arrays.fill(chars, '\0'); }
    }
    static String token() {
        byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    static String digest(String value) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
