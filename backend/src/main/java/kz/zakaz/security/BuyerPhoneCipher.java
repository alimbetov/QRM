package kz.zakaz.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Dedicated application-secret AES-GCM encryption for UNVERIFIED buyer contact phones.
 * A phone value is never used as an account ownership or recovery factor here.
 * Key rotation must be completed before the first production enrollment.
 */
@Component
public final class BuyerPhoneCipher {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final byte[] secret;

    public BuyerPhoneCipher(@Value("${zakaz.identity.phone-key-base64:}") String base64) {
        byte[] key;
        try { key = Base64.getDecoder().decode(base64); }
        catch (IllegalArgumentException e) { key = new byte[0]; }
        secret = key.length == 32 ? key : new byte[0];
    }
    public boolean configured() { return secret.length == 32; }

    public String encrypt(String phone, String issuer, String subject) {
        if (!configured()) throw new IllegalStateException("Buyer contact encryption unavailable");
        if (phone == null || !phone.matches("\\+[1-9][0-9]{7,14}"))
            throw new IllegalArgumentException("Invalid phone");
        try {
            byte[] nonce = new byte[12];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(secret,"AES"),
                new GCMParameterSpec(128,nonce));
            // Bind ciphertext to OIDC identity; moving it to another user must fail.
            cipher.updateAAD((issuer+"|"+subject).getBytes(StandardCharsets.UTF_8));
            byte[] data = cipher.doFinal(phone.getBytes(StandardCharsets.UTF_8));
            byte[] bytes = Arrays.copyOf(nonce,nonce.length+data.length);
            System.arraycopy(data,0,bytes,nonce.length,data.length);
            return "v1."+Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to encrypt buyer contact",e);
        }
    }
}
