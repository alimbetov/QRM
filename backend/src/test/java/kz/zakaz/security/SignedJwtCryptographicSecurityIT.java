package kz.zakaz.security;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.*;
import java.security.*;
import java.security.interfaces.*;
import java.time.Instant;
import java.util.*;
import kz.zakaz.catalog.*;
import kz.zakaz.domain.types.ProductType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real RSA-signed JWT integration (no mocked decoder): covers signature, issuer,
 * audience, scope and DB membership. Does not replace production OIDC/JWKS UAT.
 */
@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
@Import(SignedJwtCryptographicSecurityIT.SignatureFixture.class)
class SignedJwtCryptographicSecurityIT {
    private static final String ISSUER="https://auth.zakaz.test";
    @Autowired MockMvc mvc;
    @Autowired KeyPair keys;
    @Autowired CategoryRepository categories;
    @Autowired CanonicalProductRepository products;
    @Autowired SellerListingRepository listings;
    @Autowired SellerMembershipRepository memberships;

    @TestConfiguration(proxyBeanMethods=false)
    static class SignatureFixture {
        @Bean KeyPair testKeys() throws Exception {
            var generator=KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        }
        @Bean JwtDecoder signedJwtDecoder(KeyPair testKeys) {
            NimbusJwtDecoder decoder=NimbusJwtDecoder.withPublicKey(
                (RSAPublicKey)testKeys.getPublic()).build();
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(ISSUER),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                    audience->audience!=null&&audience.contains("zakaz-api"))));
            return decoder;
        }
    }

    private String token(PrivateKey key,String subject,String issuer,String audience,
                         String scope,Date expires) throws Exception {
        var claims=new JWTClaimsSet.Builder().issuer(issuer).subject(subject)
            .audience(audience).issueTime(new Date())
            .expirationTime(expires).claim("scope",scope).build();
        var signed=new SignedJWT(new JWSHeader(JWSAlgorithm.RS256),claims);
        signed.sign(new RSASSASigner((RSAPrivateKey)key));
        return signed.serialize();
    }
    private String signed(String subject,String issuer,String audience,
                          String scope,Date expiry) throws Exception {
        return token(keys.getPrivate(),subject,issuer,audience,scope,expiry);
    }
    private void expect(String path,String jwt,int expected) throws Exception {
        mvc.perform(post(path).header("Authorization","Bearer "+jwt))
            .andExpect(status().is(expected));
    }

    @Test void unsignedClaimSpoofingWrongSignatureIssuerAudienceOrExpiryNeverGrantsAccess()
        throws Exception {
        var category=categories.saveAndFlush(Category.create(
            "01020080000000000000",ProductType.PHYSICAL,"Imaging",null));
        var product=products.saveAndFlush(CanonicalProduct.create(category,"Camera"));
        var seller=UUID.randomUUID();
        var listing=listings.saveAndFlush(SellerListing.draft(seller,product));
        String path="/api/v1/sellers/"+seller+"/listings/"+listing.getId()+"/publish";
        var goodExpiry=Date.from(Instant.now().plusSeconds(600));
        var badExpiry=Date.from(Instant.now().minusSeconds(3600));
        KeyPairGenerator g=KeyPairGenerator.getInstance("RSA");g.initialize(2048);
        KeyPair wrongKeys=g.generateKeyPair();
        expect(path,token(wrongKeys.getPrivate(),"owner",ISSUER,"zakaz-api",
            "catalog:write",goodExpiry),401);
        expect(path,signed("owner","https://evil.example","zakaz-api",
            "catalog:write",goodExpiry),401);
        expect(path,signed("owner",ISSUER,"another-api",
            "catalog:write",goodExpiry),401);
        expect(path,signed("owner",ISSUER,"zakaz-api",
            "catalog:write",badExpiry),401);
        expect(path,signed("owner",ISSUER,"zakaz-api",
            "catalog:write",goodExpiry),403); // signed but NOT a member
        memberships.saveAndFlush(SellerMembership.grant(
            ISSUER,"owner",seller,SellerMembership.Role.CATALOG_EDITOR));
        expect(path,signed("owner",ISSUER,"zakaz-api",
            "profile",goodExpiry),403); // valid JWT but wrong scope
        expect(path,signed("owner",ISSUER,"zakaz-api",
            "catalog:write",goodExpiry),422); // auth succeeds; listing lacks SKU
    }
}
