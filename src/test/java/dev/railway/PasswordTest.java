package dev.railway;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PasswordTest {
    @Test void hashesVerifyWithoutStoringPlaintext() {
        String password = "My secure password 2026"; String hash = Passwords.hash(password);
        assertFalse(hash.contains(password)); assertTrue(Passwords.verify(password, hash));
        assertFalse(Passwords.verify("incorrect password", hash));
    }
    @Test void identicalPasswordsGetDifferentSalts() {
        assertNotEquals(Passwords.hash("same-password"), Passwords.hash("same-password"));
    }
    @Test void invalidHashFormatsAreRejected() {
        assertFalse(Passwords.verify("password", "invalid"));
        assertFalse(Passwords.verify("password", "pbkdf2-sha256$1$AA==$AA=="));
        assertFalse(Passwords.verify("password", "pbkdf2-sha256$600000$not base64$invalid"));
    }
    @Test void sessionTokensHaveEnoughEntropyAndFixedLength() {
        String first = Passwords.token(), second = Passwords.token();
        assertEquals(43, first.length()); assertNotEquals(first, second);
        assertEquals(64, Passwords.digest(first).length()); assertNotEquals(first, Passwords.digest(first));
    }
}
