package kz.zakaz.security;

import java.util.*;
import kz.zakaz.catalog.*;
import kz.zakaz.domain.types.ProductType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** R1 HTTP seller draft/create/read/publication with real JPA ownership. */
@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
@TestPropertySource(properties="zakaz.seller.workspace.enabled=true")
class SellerCatalogWorkspaceHttpIT {
    @Autowired MockMvc mvc;
    @Autowired CategoryRepository categories;
    @Autowired CanonicalProductRepository products;
    @Autowired SellerListingRepository listings;
    @Autowired SellerMembershipRepository memberships;
    @MockitoBean JwtDecoder decoder;

    private Jwt token(String subject,String scope) {
        return Jwt.withTokenValue("test-auth").header("alg","RS256")
            .issuer("https://idp.example.test").subject(subject)
            .claim("aud",List.of("zakaz-api")).claim("scope",scope).build();
    }
    private UUID physicalProduct(String categoryCode) {
        var category=categories.saveAndFlush(Category.create(categoryCode,
            ProductType.PHYSICAL,"Workspace R1",null));
        return products.saveAndFlush(
            CanonicalProduct.create(category,"R1 Seller Item")).getId();
    }
    private String draft(UUID id) {
        return "{\"productId\":\""+id+"\",\"skuCode\":\"R1-PCS\","
            +"\"unit\":\"PCS\",\"trackingMode\":\"TRACKED\","
            +"\"publicPriceKzt\":\"125000.50\"}";
    }

    @Test void authorizedEditorCanCreateListReadAndPublishOwnedListing() throws Exception {
        UUID seller=UUID.randomUUID(),product=physicalProduct("71020030000000000000");
        memberships.saveAndFlush(SellerMembership.grant(
            "https://idp.example.test","catalog-author",seller,
            SellerMembership.Role.CATALOG_EDITOR));
        when(decoder.decode(anyString())).thenReturn(token("catalog-author","catalog:write"));
        String base="/api/v1/sellers/"+seller+"/listings";
        var created=mvc.perform(post(base)
                .header("Authorization","Bearer signed")
                .contentType(MediaType.APPLICATION_JSON)
                .content(draft(product)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("DRAFT"))
            .andExpect(jsonPath("$.title").value("R1 Seller Item"))
            .andExpect(jsonPath("$.variants[0].code").value("R1-PCS"))
            .andExpect(jsonPath("$.variants[0].priceKzt").value("125000.50"))
            .andReturn().getResponse().getContentAsString();
        UUID listing=UUID.fromString(new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(created).get("listingId").asText());
        mvc.perform(get(base).header("Authorization","Bearer signed"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].listingId").value(listing.toString()));
        mvc.perform(get(base+"/"+listing).header("Authorization","Bearer signed"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.qrToken").doesNotExist());
        mvc.perform(post(base+"/"+listing+"/publish")
                .header("Authorization","Bearer signed"))
            .andExpect(status().isNoContent());
        mvc.perform(get(base+"/"+listing).header("Authorization","Bearer signed"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PUBLIC"))
            .andExpect(jsonPath("$.qrToken").isNotEmpty());
    }

    @Test void missingMembershipOrForeignListingMustNotLeakSellerData() throws Exception {
        UUID owner=UUID.randomUUID(),other=UUID.randomUUID();
        UUID product=physicalProduct("72020030000000000000");
        var foreign=listings.saveAndFlush(
            SellerListing.draft(other,products.findById(product).orElseThrow()));
        memberships.saveAndFlush(SellerMembership.grant(
            "https://idp.example.test","seller-a",owner,
            SellerMembership.Role.CATALOG_EDITOR));
        when(decoder.decode(anyString())).thenReturn(token("seller-a","catalog:write"));
        String base="/api/v1/sellers/"+owner+"/listings";
        mvc.perform(get(base+"/"+foreign.getId()).header("Authorization","Bearer signed"))
            .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/sellers/"+other+"/listings")
            .header("Authorization","Bearer signed")).andExpect(status().isForbidden());
        mvc.perform(post(base).header("Authorization","Bearer signed")
            .contentType(MediaType.APPLICATION_JSON)
            .content(draft(UUID.randomUUID()))).andExpect(status().isNotFound());
        mvc.perform(get(base).header("Authorization","Bearer signed"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
    }

    @Test void anonymousUnscopedAndInvalidDraftCannotWrite() throws Exception {
        UUID seller=UUID.randomUUID(),product=physicalProduct("73020030000000000000");
        String url="/api/v1/sellers/"+seller+"/listings";
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
            .content(draft(product))).andExpect(status().isUnauthorized());
        memberships.saveAndFlush(SellerMembership.grant(
            "https://idp.example.test","seller-limited",seller,
            SellerMembership.Role.CATALOG_EDITOR));
        when(decoder.decode(anyString())).thenReturn(token("seller-limited","profile"));
        mvc.perform(post(url).header("Authorization","Bearer signed")
            .contentType(MediaType.APPLICATION_JSON)
            .content(draft(product))).andExpect(status().isForbidden());
        when(decoder.decode(anyString())).thenReturn(token("seller-limited","catalog:write"));
        mvc.perform(post(url).header("Authorization","Bearer signed")
            .contentType(MediaType.APPLICATION_JSON)
            .content(draft(product).replace("TRACKED","UNTRACKED")))
            .andExpect(status().isUnprocessableEntity());
        mvc.perform(get(url+"?page=-1&size=100")
            .header("Authorization","Bearer signed"))
            .andExpect(status().isUnprocessableEntity());
    }
}
