package sh.vork.relay;

import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * AES-256-GCM encrypt/decrypt for the zero-knowledge relay protocol.
 *
 * <p>The encryption key is generated fresh for each form dispatch and is embedded
 * in the auth URL hash fragment ({@code #k=…}), which browsers never send to the
 * server — keeping the relay cryptographically blind to both the form schema and
 * the user's response.
 */
@Service
public class RelayEncryptionService {

    /**
     * Result of encrypting a plaintext payload.
     *
     * @param key       the raw AES-256 secret key (caller must keep this in RAM to decrypt the response)
     * @param ciphertext base64url-encoded ciphertext (without auth tag)
     * @param nonce      base64url-encoded 12-byte GCM IV
     * @param authTag    base64url-encoded 16-byte GCM authentication tag
     */
    public record EncryptionResult(SecretKey key, String ciphertext, String nonce, String authTag) {

        /** The key bytes encoded as base64url (no padding) — suitable for URL hash fragments. */
        public String keyBase64Url() {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(key.getEncoded());
        }
    }

    /**
     * Encrypts {@code plaintext} with a freshly-generated AES-256-GCM key.
     *
     * <p>Java's {@link Cipher#doFinal} returns {@code ciphertext ‖ authTag} concatenated;
     * the last 16 bytes are split off as the auth tag to match the relay API's three-field
     * JSON structure.
     */
    public EncryptionResult encrypt(String plaintext) throws Exception {
        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(256, new SecureRandom());
        SecretKey key = kg.generateKey();

        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));

        byte[] ciphertextWithTag = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        int    cipherLen         = ciphertextWithTag.length - 16;
        byte[] ciphertext        = Arrays.copyOf(ciphertextWithTag, cipherLen);
        byte[] authTag           = Arrays.copyOfRange(ciphertextWithTag, cipherLen, ciphertextWithTag.length);

        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        return new EncryptionResult(
                key,
                enc.encodeToString(ciphertext),
                enc.encodeToString(iv),
                enc.encodeToString(authTag));
    }

    /**
     * Decrypts a relay response payload using the session key.
     *
     * @param key        the AES-256 key used when the form was uploaded
     * @param ciphertext base64url-encoded ciphertext
     * @param nonce      base64url-encoded 12-byte GCM IV
     * @param authTag    base64url-encoded 16-byte GCM authentication tag
     * @return decrypted plaintext (response JSON)
     */
    public String decrypt(SecretKey key, String ciphertext, String nonce, String authTag) throws Exception {
        Base64.Decoder dec = Base64.getUrlDecoder();
        byte[] ciphertextBytes = dec.decode(ciphertext);
        byte[] iv              = dec.decode(nonce);
        byte[] authTagBytes    = dec.decode(authTag);

        // Java GCM expects ciphertext ‖ authTag concatenated
        byte[] combined = new byte[ciphertextBytes.length + authTagBytes.length];
        System.arraycopy(ciphertextBytes, 0, combined, 0, ciphertextBytes.length);
        System.arraycopy(authTagBytes, 0, combined, ciphertextBytes.length, authTagBytes.length);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));

        return new String(cipher.doFinal(combined), StandardCharsets.UTF_8);
    }
}
