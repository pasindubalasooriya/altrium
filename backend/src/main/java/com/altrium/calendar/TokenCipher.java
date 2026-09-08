package com.altrium.calendar;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Encrypts the Google refresh token at rest.
 *
 * <p>A refresh token reads a person's calendar and writes to it, for as long as they leave the
 * connection in place. It is the only credential Altrium stores about anybody, and it is the
 * one field in the schema that would be worth something on its own if the database were copied.
 *
 * <h2>The key is derived from the OAuth client secret, deliberately</h2>
 *
 * <p>Not from a fourth secret of its own. The argument is that a refresh token is <em>already</em>
 * useless without the client secret: Google will not exchange one without the client id and
 * secret that issued it. So an attacker holding both the database and the client secret can use
 * these tokens whatever we do here, and an attacker holding only the database cannot use them
 * whatever we do here. What encryption under a key derived from the client secret buys is the
 * case in between, which is the realistic one: a database dump, a backup file, a log of a query.
 * Against that it is effective, and it adds no key that can be lost separately from the secret
 * that would already have to be rotated.
 *
 * <p>The consequence is honest and worth stating: <strong>rotating the client secret makes every
 * stored token unreadable</strong>. That is also the correct behaviour, because rotating it
 * invalidates them at Google's end in any case. People reconnect, which is one click.
 *
 * <p>AES-GCM, so a tampered ciphertext fails to decrypt rather than yielding rubbish that is
 * then sent to Google. The random nonce is prefixed to the ciphertext, which is why the column
 * is binary and not a string.
 */
@Component
public class TokenCipher {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    /**
     * A fixed salt, which is safe here and would not be for passwords.
     *
     * <p>A salt defends against a precomputed table covering likely inputs. The input is a
     * Google client secret, which is high-entropy and machine-generated, so there is no table
     * to build. What a per-row salt would cost is the ability to derive the key once at
     * startup, and it would have to be stored beside the ciphertext anyway.
     */
    private static final byte[] SALT = "altrium.google.refresh-token.v1".getBytes(StandardCharsets.UTF_8);

    private final SecureRandom random = new SecureRandom();
    private final SecretKey key;

    public TokenCipher(@Value("${altrium.google.client-secret:}") String clientSecret) {
        this.key = clientSecret == null || clientSecret.isBlank() ? null : deriveKey(clientSecret);
    }

    /**
     * Whether tokens can be stored at all.
     *
     * <p>False when no client secret is configured, which is also when the OAuth flow itself
     * cannot run. The two are the same condition on purpose: there is no state in which
     * Altrium accepts a token it cannot encrypt.
     */
    public boolean isConfigured() {
        return key != null;
    }

    public byte[] encrypt(String plaintext) {
        requireConfigured();
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] out = new byte[nonce.length + ciphertext.length];
            System.arraycopy(nonce, 0, out, 0, nonce.length);
            System.arraycopy(ciphertext, 0, out, nonce.length, ciphertext.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not encrypt the Google refresh token", e);
        }
    }

    public String decrypt(byte[] stored) {
        requireConfigured();
        if (stored == null || stored.length <= NONCE_BYTES) {
            throw new IllegalStateException("Stored Google refresh token is truncated");
        }
        try {
            byte[] nonce = Arrays.copyOfRange(stored, 0, NONCE_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(stored, NONCE_BYTES, stored.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            // Almost always the client secret having changed since the row was written. The
            // message says so rather than reporting a cryptographic fault, because the fix is
            // for the person to reconnect.
            throw new IllegalStateException(
                    "Could not read the stored Google refresh token. It was encrypted under a "
                            + "different OAuth client secret; the connection must be made again.", e);
        }
    }

    private void requireConfigured() {
        if (key == null) {
            throw new IllegalStateException("Google integration is not configured");
        }
    }

    private static SecretKey deriveKey(String clientSecret) {
        try {
            PBEKeySpec spec = new PBEKeySpec(clientSecret.toCharArray(), SALT, 210_000, 256);
            byte[] bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
            return new SecretKeySpec(bytes, "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not derive the token encryption key", e);
        }
    }
}
