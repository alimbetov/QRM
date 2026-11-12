package kz.zakaz.security;

import java.util.UUID;
import kz.zakaz.catalog.*;
import kz.zakaz.domain.types.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class SellerPublishingSecurityIT {
    @Autowired MockMvc mvc;
    @Autowired CategoryRepository categories;
    @Autowired CanonicalProductRepository products;
    @Autowired SellerListingRepository listings;
    @Autowired SellerMembershipRepository memberships;
    @MockitoBean JwtDecoder decoder;

    private Jwt jwt(String subject,String scope) {
        return Jwt.withTokenValue("opaque-test-value").header("alg","RS256")
            .issuer("https://idp.example.test").subject(subject)
            .claim("aud",java.util.List.of("zakaz-api"))
            .claim("scope",scope).build();
    }

    @Test void anonymousAndUnenrolledPrincipalCannotPublishAnotherSellersListing() throws Exception {
        var category=categories.saveAndFlush(Category.create(
            "01020030000000000000",ProductType.PHYSICAL,"Phones",null));
        var product=products.saveAndFlush(CanonicalProduct.create(category,"Phone"));
        UUID seller=UUID.randomUUID();
        var listing=listings.saveAndFlush(SellerListing.draft(seller,product));
        String url="/api/v1/sellers/"+seller+"/listings/"+listing.getId()+"/publish";
        mvc.perform(post(url)).andExpect(status().isUnauthorized());
        when(decoder.decode(anyString())).thenReturn(jwt("unregistered","catalog:write"));
        mvc.perform(post(url).header("Authorization","Bearer signed"))
            .andExpect(status().isForbidden());
    }

    @Test void jwtScopeAndActiveMembershipBothRequired() throws Exception {
        var category=categories.saveAndFlush(Category.create(
            "01020040000000000000",ProductType.PHYSICAL,"Tablets",null));
        var product=products.saveAndFlush(CanonicalProduct.create(category,"Tablet"));
        UUID seller=UUID.randomUUID();
        var listing=listings.saveAndFlush(SellerListing.draft(seller,product));
        memberships.saveAndFlush(SellerMembership.grant(
            "https://idp.example.test","owner",seller,SellerMembership.Role.CATALOG_EDITOR));
        String url="/api/v1/sellers/"+seller+"/listings/"+listing.getId()+"/publish";
        when(decoder.decode(anyString())).thenReturn(jwt("owner","profile"));
        mvc.perform(post(url).header("Authorization","Bearer signed"))
            .andExpect(status().isForbidden());
        when(decoder.decode(anyString())).thenReturn(jwt("owner","catalog:write"));
        // Membership succeeds; publication still rejects absent SKU as a business precondition.
        mvc.perform(post(url).header("Authorization","Bearer signed"))
            .andExpect(status().isUnprocessableEntity());
    }
    @Test void merchantCannotForgeSellerPathOrOperateOtherCompanyListing() throws Exception {
        var category=categories.saveAndFlush(Category.create(
            "01020050000000000000",ProductType.PHYSICAL,"Accessories",null));
        var product=products.saveAndFlush(CanonicalProduct.create(category,"Cable"));
        UUID merchantA=UUID.randomUUID(),merchantB=UUID.randomUUID();
        var listingB=listings.saveAndFlush(SellerListing.draft(merchantB,product));
        memberships.saveAndFlush(SellerMembership.grant(
            "https://idp.example.test","owner-A",merchantA,SellerMembership.Role.CATALOG_EDITOR));
        when(decoder.decode(anyString())).thenReturn(jwt("owner-A","catalog:write"));
        // Client controls a path ID, not the authoritative company association.
        mvc.perform(post("/api/v1/sellers/"+merchantB+"/listings/"+listingB.getId()+"/publish")
                .header("Authorization","Bearer signed"))
            .andExpect(status().isForbidden());
        // Membership for A is valid, but Listing B belongs to a different merchant.
        mvc.perform(post("/api/v1/sellers/"+merchantA+"/listings/"+listingB.getId()+"/publish")
                .header("Authorization","Bearer signed"))
            .andExpect(status().isForbidden());
    }

    @Test void readOnlyMembershipCannotPublishEvenWithCorrectJwtScope() throws Exception {
        var category=categories.saveAndFlush(Category.create(
            "01020060000000000000",ProductType.PHYSICAL,"Displays",null));
        var product=products.saveAndFlush(CanonicalProduct.create(category,"Monitor"));
        UUID merchant=UUID.randomUUID();
        var listing=listings.saveAndFlush(SellerListing.draft(merchant,product));
        memberships.saveAndFlush(SellerMembership.grant(
            "https://idp.example.test","viewer",merchant,SellerMembership.Role.VIEWER));
        when(decoder.decode(anyString())).thenReturn(jwt("viewer","catalog:write"));
        mvc.perform(post("/api/v1/sellers/"+merchant+"/listings/"+listing.getId()+"/publish")
                .header("Authorization","Bearer signed"))
            .andExpect(status().isForbidden());
    }

}
