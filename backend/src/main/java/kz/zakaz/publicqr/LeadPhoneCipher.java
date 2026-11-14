package kz.zakaz.publicqr;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** AES-256-GCM with per-record random nonce. Only server-held key can decrypt guest phone. */
@Component
public final class LeadPhoneCipher {
    private static final SecureRandom RANDOM=new SecureRandom();
    private final byte[] secret;
    public LeadPhoneCipher(@Value("${zakaz.leads.phone-key-base64:}") String base64){
        byte[] decoded;
        try{decoded=Base64.getDecoder().decode(base64);}
        catch(IllegalArgumentException ex){decoded=new byte[0];}
        secret=decoded.length==32?decoded:new byte[0];
    }
    public boolean configured(){return secret.length==32;}
    public String encrypt(String phone){
        if(!configured())throw new IllegalStateException("Lead encryption key not configured");
        try {
            byte[] nonce=new byte[12];RANDOM.nextBytes(nonce);
            var cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(secret,"AES"),new GCMParameterSpec(128,nonce));
            var encrypted=cipher.doFinal(phone.getBytes(StandardCharsets.UTF_8));
            var bytes=new byte[nonce.length+encrypted.length];
            System.arraycopy(nonce,0,bytes,0,nonce.length);
            System.arraycopy(encrypted,0,bytes,nonce.length,encrypted.length);
            return "v1."+Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } catch(GeneralSecurityException ex){throw new IllegalStateException("Phone encryption failure",ex);}
    }
    public String decrypt(String ciphertext){
        if(!configured())throw new IllegalStateException("Lead encryption key not configured");
        if(ciphertext==null||!ciphertext.startsWith("v1."))
            throw new IllegalStateException("Unknown encrypted phone key version");
        try {
            var bytes=Base64.getUrlDecoder().decode(ciphertext.substring(3));
            if(bytes.length<29)throw new IllegalStateException("Truncated encrypted phone");
            var nonce=Arrays.copyOfRange(bytes,0,12);
            var value=Arrays.copyOfRange(bytes,12,bytes.length);
            var cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(secret,"AES"),new GCMParameterSpec(128,nonce));
            return new String(cipher.doFinal(value),StandardCharsets.UTF_8);
        }catch(GeneralSecurityException|IllegalArgumentException ex){
            throw new IllegalStateException("Unable to decrypt encrypted phone",ex);
        }
    }
    public String digest(String message){
        if(!configured())throw new IllegalStateException("Lead encryption key not configured");
        try{
            var mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret,"HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        }catch(GeneralSecurityException ex){throw new IllegalStateException("Lead digest failure",ex);}
    }
}
