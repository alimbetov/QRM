package kz.zakaz.security;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Actual Flyway V19 PostgreSQL and scoped Spring Security routes, no public buyerId trust. */
@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test-pg") @Testcontainers
@TestPropertySource(properties={
  "zakaz.buyer.enrollment.enabled=true",
  "zakaz.identity.require-verified-email=true",
  "zakaz.identity.phone-key-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
class BuyerEnrollmentPostgresIT {
    @Container static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:17-alpine");
    @DynamicPropertySource static void postgres(DynamicPropertyRegistry props){
        props.add("spring.datasource.url",db::getJdbcUrl);
        props.add("spring.datasource.username",db::getUsername);
        props.add("spring.datasource.password",db::getPassword);
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired BuyerAccountLinkRepository links;
    @Autowired BuyerIdentityService buyerIdentity;
    @MockitoBean JwtDecoder decoder;
    private static final String ISSUER="https://keycloak.example.test/identity/realms/zakaz-staging";
    private static final String VALID_BODY=
        "{\"nickname\":\"Buyer123\",\"phone\":\"+77000000000\",\"consentToStorePhone\":true}";

    private String identity(boolean verified,String scope){
        String bearer="test-token-"+UUID.randomUUID();
        String subject="buyer-"+UUID.randomUUID();
        Jwt jwt=Jwt.withTokenValue(bearer).header("alg","RS256")
            .issuer(ISSUER).subject(subject).claim("aud",List.of("zakaz-api"))
            .claim("scope",scope).claim("email","buyer@example.test")
            .claim("email_verified",verified).build();
        when(decoder.decode(bearer)).thenReturn(jwt);
        return bearer;
    }
    private String issuerSubject(String bearer){
        return decoder.decode(bearer).getSubject();
    }
    /** Unit-level actor simulating a completed Spring Security JWT filter chain. */
    private JwtAuthenticationToken authenticated(String bearer){
        var actor=new JwtAuthenticationToken(decoder.decode(bearer),
            List.of(new SimpleGrantedAuthority("SCOPE_deal:write")));
        actor.setAuthenticated(true);
        return actor;
    }
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder register(String bearer){
        return post("/api/v1/buyers/enroll").header("Authorization","Bearer "+bearer)
            .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY);
    }

    @Test void verifiedBuyerCanEnrollOnlyOnceAndPhoneRemainsEncryptedAndUnverified() throws Exception{
        String token=identity(true,"deal:write");
        mvc.perform(get("/api/v1/buyers/me").header("Authorization","Bearer "+token))
            .andExpect(status().isNotFound());
        var created=mvc.perform(register(token)).andExpect(status().isCreated())
            .andExpect(jsonPath("$.nickname").value("Buyer123"))
            .andExpect(jsonPath("$.phoneVerified").value(false))
            .andExpect(jsonPath("$.maskedPhone").value("••••0000"))
            .andExpect(jsonPath("$.email").value("buyer@example.test"))
            .andExpect(jsonPath("$.created").value(true))
            .andReturn().getResponse().getContentAsString();
        var original=links.findByIssuerAndSubject(ISSUER,issuerSubject(token)).orElseThrow();
        UUID internalId=original.getBuyerId();
        assertThat(created).contains(internalId.toString());
        var encrypted=jdbc.queryForObject(
            "SELECT phone_ciphertext FROM buyer_profile WHERE buyer_id=?",
            String.class,internalId);
        assertThat(encrypted).startsWith("v1.").doesNotContain("+77000000000");
        assertThat(jdbc.queryForObject(
            "SELECT phone_verified FROM buyer_profile WHERE buyer_id=?",Boolean.class,internalId))
            .isFalse();

        mvc.perform(register(token)).andExpect(status().isOk())
            .andExpect(jsonPath("$.buyerId").value(internalId.toString()))
            .andExpect(jsonPath("$.created").value(false));
        mvc.perform(get("/api/v1/buyers/me").header("Authorization","Bearer "+token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.buyerId").value(internalId.toString()))
            .andExpect(jsonPath("$.phoneVerified").value(false));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM buyer_profile WHERE buyer_id=?",
            Integer.class,internalId)).isEqualTo(1);
    }

    @Test void anonymousUnverifiedEmailWrongScopeAndMissingConsentFailClosed() throws Exception{
        mvc.perform(post("/api/v1/buyers/enroll")
            .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
            .andExpect(status().isUnauthorized());
        String unverified=identity(false,"deal:write");
        mvc.perform(register(unverified)).andExpect(status().isForbidden());
        String seller=identity(true,"catalog:write order:confirm");
        mvc.perform(register(seller)).andExpect(status().isForbidden());
        String buyer=identity(true,"deal:write");
        mvc.perform(post("/api/v1/buyers/enroll").header("Authorization","Bearer "+buyer)
            .contentType(MediaType.APPLICATION_JSON)
            .content(VALID_BODY.replace("true","false")))
            .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/v1/buyers/enroll").header("Authorization","Bearer "+buyer)
            .contentType(MediaType.APPLICATION_JSON)
            .content(VALID_BODY.replace("+77000000000","07000000000")))
            .andExpect(status().isUnprocessableEntity());
        assertThat(links.findByIssuerAndSubject(ISSUER,issuerSubject(unverified))).isEmpty();
        assertThat(links.findByIssuerAndSubject(ISSUER,issuerSubject(buyer))).isEmpty();
    }

    @Test void disabledBuyerCannotReEnrollAndDifferentBuyerCannotReadProfile() throws Exception{
        String token=identity(true,"deal:write");
        mvc.perform(register(token)).andExpect(status().isCreated());
        String other=identity(true,"deal:write");
        mvc.perform(get("/api/v1/buyers/me").header("Authorization","Bearer "+other))
            .andExpect(status().isNotFound());
        jdbc.update("UPDATE buyer_account_link SET active=false WHERE issuer=? AND subject=?",
            ISSUER,issuerSubject(token));
        mvc.perform(register(token)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/buyers/me").header("Authorization","Bearer "+token))
            .andExpect(status().isNotFound());
    }

    @Test void simultaneousEnrollmentRetriesShareOneBuyerAndOneEncryptedProfile() throws Exception {
        String token=identity(true,"deal:write");
        var pool=Executors.newFixedThreadPool(2);
        var start=new CountDownLatch(1);
        try {
            Callable<Integer> request=()->{
                if(!start.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("Latch timed out");
                return mvc.perform(register(token)).andReturn().getResponse().getStatus();
            };
            var first=pool.submit(request);
            var second=pool.submit(request);
            start.countDown();
            assertThat(List.of(first.get(45,TimeUnit.SECONDS),
                               second.get(45,TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(201,200);
            var linked=links.findByIssuerAndSubject(ISSUER,issuerSubject(token)).orElseThrow();
            assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM buyer_profile WHERE buyer_id=?",
                Integer.class,linked.getBuyerId())).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM buyer_account_link WHERE issuer=? AND subject=?",
                Integer.class,ISSUER,issuerSubject(token))).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test void deactivatingBuyerLinkImmediatelyBlocksValidSignedSubjectFromCheckout() throws Exception {
        String bearer=identity(true,"deal:write");
        mvc.perform(register(bearer)).andExpect(status().isCreated());
        var linked=links.findByIssuerAndSubject(ISSUER,issuerSubject(bearer)).orElseThrow();
        // Decoder still accepts the original bearer; business authorization
        // must use the CURRENT database link, not stale token claims.
        var authenticated=authenticated(bearer);
        assertThat(buyerIdentity.requireBuyer(authenticated)).isEqualTo(linked.getBuyerId());
        jdbc.update("UPDATE buyer_account_link SET active=false WHERE issuer=? AND subject=?", ISSUER,issuerSubject(bearer));
        assertThatThrownBy(()->buyerIdentity.requireBuyer(authenticated))
            .isInstanceOf(AccessDeniedException.class);
        mvc.perform(get("/api/v1/buyers/me")
            .header("Authorization","Bearer "+bearer))
            .andExpect(status().isNotFound());
    }

    @Test void preexistingBuyerCannotCheckoutWithUnverifiedEmailWhenStagingGateEnabled(){
        String token=identity(false,"deal:write order:read");
        UUID buyerId=UUID.randomUUID();
        links.saveAndFlush(BuyerAccountLink.provision(ISSUER,issuerSubject(token),buyerId));
        var authenticated=authenticated(token);
        assertThatThrownBy(()->buyerIdentity.requireBuyer(authenticated))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessageContaining("Verified email");
    }

    @Test void existingBuyerLinkCanCompleteProfileWithoutChangingBuyerIdentity() throws Exception{
        String token=identity(true,"deal:write");
        UUID buyer=UUID.randomUUID();
        links.saveAndFlush(BuyerAccountLink.provision(ISSUER,issuerSubject(token),buyer));
        mvc.perform(register(token)).andExpect(status().isCreated())
            .andExpect(jsonPath("$.buyerId").value(buyer.toString()));
        assertThat(links.findByIssuerAndSubject(ISSUER,issuerSubject(token)).orElseThrow()
            .getBuyerId()).isEqualTo(buyer);
    }
}
