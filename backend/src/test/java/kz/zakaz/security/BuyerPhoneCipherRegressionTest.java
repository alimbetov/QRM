package kz.zakaz.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class BuyerPhoneCipherRegressionTest {
    private static final byte[] KEY = new byte[32];
    private static final String ISSUER = "https://idp.example.test";
    private static final String SUBJECT = "buyer-test";
    private static final String PHONE = "+77001234567";
    private static final BuyerPhoneCipher CIPHER =
        new BuyerPhoneCipher(Base64.getEncoder().encodeToString(KEY));

    private static String decrypt(String envelope, String issuer, String subject)
        throws GeneralSecurityException {
        byte[] encrypted = Base64.getUrlDecoder().decode(envelope.substring(3));
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(KEY,"AES"),
            new GCMParameterSpec(128,Arrays.copyOf(encrypted,12)));
        cipher.updateAAD((issuer+"|"+subject).getBytes(StandardCharsets.UTF_8));
        return new String(cipher.doFinal(encrypted,12,encrypted.length-12),StandardCharsets.UTF_8);
    }

    @Test void encryptionUsesRandomNonceAndBindsContactToOidcIdentity() throws Exception {
        String first=CIPHER.encrypt(PHONE,ISSUER,SUBJECT);
        String second=CIPHER.encrypt(PHONE,ISSUER,SUBJECT);
        assertThat(first).startsWith("v1.").doesNotContain(PHONE);
        assertThat(first).isNotEqualTo(second);
        assertThat(decrypt(first,ISSUER,SUBJECT)).isEqualTo(PHONE);
        assertThat(decrypt(second,ISSUER,SUBJECT)).isEqualTo(PHONE);
        assertThatThrownBy(() -> decrypt(first,ISSUER,"another-user"))
            .isInstanceOf(GeneralSecurityException.class);
        assertThatThrownBy(() -> decrypt(first,"https://other-idp.test",SUBJECT))
            .isInstanceOf(GeneralSecurityException.class);
    }

    @Test void missingOrMalformedKeyFailsClosed() {
        assertThat(new BuyerPhoneCipher("").configured()).isFalse();
        assertThat(new BuyerPhoneCipher("not-base64").configured()).isFalse();
        assertThat(new BuyerPhoneCipher(Base64.getEncoder().encodeToString(new byte[16]))
            .configured()).isFalse();
        assertThatThrownBy(() -> new BuyerPhoneCipher("").encrypt(PHONE,ISSUER,SUBJECT))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test void onlyCanonicalE164PhoneCanBeEncrypted() {
        for(String invalid : new String[] {"77001234567","123","+07001234567","+7700 1234567",""}) {
            assertThatThrownBy(() -> CIPHER.encrypt(invalid,ISSUER,SUBJECT))
                .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> CIPHER.encrypt(null,ISSUER,SUBJECT))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
