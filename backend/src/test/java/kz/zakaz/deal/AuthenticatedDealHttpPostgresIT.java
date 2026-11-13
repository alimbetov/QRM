package kz.zakaz.deal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import kz.zakaz.catalog.*;
import kz.zakaz.domain.types.*;
import kz.zakaz.inventory.*;
import kz.zakaz.location.*;
import kz.zakaz.order.*;
import kz.zakaz.security.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test-pg") @Testcontainers
@TestPropertySource(properties="zakaz.commerce.http.enabled=true")
class AuthenticatedDealHttpPostgresIT {
    @Container static PostgreSQLContainer<?> db=new PostgreSQLContainer<>("postgres:17-alpine");
    @DynamicPropertySource static void datasource(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",db::getJdbcUrl);
        r.add("spring.datasource.username",db::getUsername);
        r.add("spring.datasource.password",db::getPassword);
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean JwtDecoder decoder;
    @Autowired CategoryRepository categories;
    @Autowired CanonicalProductRepository products;
    @Autowired SellerListingRepository listings;
    @Autowired SellerSkuRepository skus;
    @Autowired CatalogPublishingService publishing;
    @Autowired SellerMembershipRepository memberships;
    @Autowired BuyerAccountLinkRepository buyerLinks;
    @Autowired InventoryLocationRepository locations;
    @Autowired SellerDeliveryAreaRepository areas;
    @Autowired InventoryBalanceRepository balances;
    @Autowired CustomerOrderRepository orders;
    @Autowired InventoryReservationRepository reservations;
    @Autowired OrderOutboxEventRepository events;

    private record Fixture(UUID productId,UUID skuId,UUID locationId,
                           UUID sellerId,UUID buyerId,String buyerToken,String sellerToken){}
    private Jwt jwt(String subject,String scope) {
        return Jwt.withTokenValue("mocked-decoder-result").header("alg","RS256")
            .issuer("https://idp.example.test").subject(subject)
            .claim("aud",List.of("zakaz-api")).claim("scope",scope).build();
    }
    private Fixture seed(){
        String code=String.format("%02d%02d%03d",1+Math.floorMod(UUID.randomUUID().hashCode(),99),
                1+Math.floorMod(UUID.randomUUID().hashCode(),99),
                1+Math.floorMod(UUID.randomUUID().hashCode(),999))+"0000000000000";
        var category=categories.saveAndFlush(Category.create(
            code,ProductType.PHYSICAL,"Phones",null));
        var product=products.saveAndFlush(CanonicalProduct.create(category,"Phone"));
        UUID seller=UUID.randomUUID(),buyer=UUID.randomUUID();
        var listing=listings.saveAndFlush(SellerListing.draft(seller,product));
        var sku=skus.saveAndFlush(SellerSku.create(listing,"SKU-1",StockTrackingMode.TRACKED,MeasurementUnit.PCS));
        publishing.publishForSeller(seller,listing.getId());
        var location=locations.saveAndFlush(InventoryLocation.create(
            seller,"WH-"+UUID.randomUUID(),"Test pickup","KZ-ALA","Almaty region",
            "Almaty","Testing avenue 1",LocationType.SHOP,null,true));
        balances.saveAndFlush(InventoryBalance.open(seller,location.getId(),sku.getId(),BigDecimal.ONE));
        areas.saveAndFlush(SellerDeliveryArea.allow(seller,"Almaty"));
        String buyerSubject="buyer-"+UUID.randomUUID(), sellerSubject="seller-"+UUID.randomUUID();
        buyerLinks.saveAndFlush(BuyerAccountLink.provision("https://idp.example.test",buyerSubject,buyer));
        memberships.saveAndFlush(SellerMembership.grant("https://idp.example.test",sellerSubject,
            seller,SellerMembership.Role.SELLER_ADMIN));
        String bt="buyer-"+UUID.randomUUID(),st="seller-"+UUID.randomUUID();
        when(decoder.decode(bt)).thenReturn(jwt(buyerSubject,"deal:write order:read"));
        when(decoder.decode(st)).thenReturn(jwt(sellerSubject,"deal:bid order:confirm"));
        return new Fixture(product.getId(),sku.getId(),location.getId(),seller,buyer,bt,st);
    }
    private UUID idFrom(String body,String name) throws Exception{
        return UUID.fromString(json.readTree(body).get(name).asText());
    }
    @Test void signedBuyerSellerWorkflowFromHttpCreatesAndConfirmsAtomicOrder() throws Exception{
        var f=seed();
        String expires=Instant.now().plusSeconds(900).toString();
        String demandBody="{\"city\":\"Almaty\",\"expiresAt\":\""+expires+
            "\",\"lines\":[{\"productId\":\""+f.productId()+
            "\",\"quantity\":1,\"unit\":\"PCS\"}]}";
        var demandCreated=mvc.perform(post("/api/v1/buyers/demands")
                .header("Authorization","Bearer "+f.buyerToken())
                .contentType(MediaType.APPLICATION_JSON).content(demandBody))
            .andExpect(status().isCreated()).andReturn();
        UUID demandId=idFrom(demandCreated.getResponse().getContentAsString(),"demandId");
        String bidBody="{\"revision\":1,\"expiresAt\":\""+expires+
            "\",\"lines\":[{\"productId\":\""+f.productId()+
            "\",\"skuId\":\""+f.skuId()+
            "\",\"quantity\":1,\"unitPrice\":100}],"+
            "\"method\":\"PICKUP\",\"originLocationId\":\""+f.locationId()+
            "\",\"deliveryFee\":0}";
        var bidCreated=mvc.perform(post("/api/v1/sellers/"+f.sellerId()+"/demands/"+demandId+"/bids")
                .header("Authorization","Bearer "+f.sellerToken())
                .contentType(MediaType.APPLICATION_JSON).content(bidBody))
            .andExpect(status().isCreated()).andReturn();
        UUID bidId=idFrom(bidCreated.getResponse().getContentAsString(),"bidId");
        String quotesUrl="/api/v1/buyers/demands/"+demandId+"/bids";
        mvc.perform(get(quotesUrl).header("Authorization","Bearer "+f.buyerToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.acceptedOrderId").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.bids[0].bidId").value(bidId.toString()))
            .andExpect(jsonPath("$.bids[0].totalKzt").value("100.00"))
            .andExpect(jsonPath("$.bids[0].method").value("PICKUP"))
            .andExpect(jsonPath("$.bids[0].lines[0].skuId").value(f.skuId().toString()));
        mvc.perform(get(quotesUrl).header("Authorization","Bearer "+f.sellerToken()))
            .andExpect(status().isForbidden());

        String accept="/api/v1/buyers/demands/"+demandId+"/bids/"+bidId+"/accept";
        var accepted=mvc.perform(post(accept)
                .header("Authorization","Bearer "+f.buyerToken())
                .header("Idempotency-Key","buyer-accept-one"))
            .andExpect(status().isCreated()).andReturn();
        UUID orderId=idFrom(accepted.getResponse().getContentAsString(),"orderId");
        mvc.perform(post(accept)
                .header("Authorization","Bearer "+f.buyerToken())
                .header("Idempotency-Key","buyer-accept-one"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.orderId").value(orderId.toString()));
        mvc.perform(get("/api/v1/buyers/orders/"+orderId)
                .header("Authorization","Bearer "+f.buyerToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PLACED"))
            .andExpect(jsonPath("$.lines[0].quantity").value(1));
        // A separately provisioned buyer cannot read or enumerate another buyer's order.
        String anotherSubject="unrelated-buyer-"+UUID.randomUUID();
        String anotherToken="unrelated-token-"+UUID.randomUUID();
        buyerLinks.saveAndFlush(BuyerAccountLink.provision(
            "https://idp.example.test",anotherSubject,UUID.randomUUID()));
        when(decoder.decode(anotherToken)).thenReturn(jwt(anotherSubject,"order:read"));
        String unauthorizedBuyer="foreign-bid-"+UUID.randomUUID();
        when(decoder.decode(unauthorizedBuyer)).thenReturn(jwt(anotherSubject,"deal:write"));
        mvc.perform(get(quotesUrl).header("Authorization","Bearer "+unauthorizedBuyer))
            .andExpect(status().isForbidden());

        mvc.perform(get("/api/v1/buyers/orders/"+orderId)
                .header("Authorization","Bearer "+anotherToken))
            .andExpect(status().isForbidden());
        assertThat(orders.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.PLACED);
        assertThat(reservations.findByOrderId(orderId)).isEmpty();
        var confirmation="/api/v1/sellers/"+f.sellerId()+"/orders/"+orderId+"/confirm";
        mvc.perform(post(confirmation).header("Authorization","Bearer "+f.sellerToken()))
            .andExpect(status().isNoContent());
        mvc.perform(post(confirmation).header("Authorization","Bearer "+f.sellerToken()))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/buyers/orders/"+orderId)
                .header("Authorization","Bearer "+f.buyerToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CONFIRMED"));
        assertThat(orders.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(reservations.findByOrderId(orderId)).hasSize(1);
        assertThat(events.findByAggregateIdAndEventType(orderId,"ORDER_CONFIRMED")).isPresent();
    }
    @Test void unverifiedAndForeignActorsCannotCreateDemandOrConfirmOrder() throws Exception{
        var f=seed();
        var request="{\"city\":\"Almaty\",\"expiresAt\":\""+
            Instant.now().plusSeconds(900)+"\",\"lines\":[{\"productId\":\""+
            f.productId()+"\",\"quantity\":1,\"unit\":\"PCS\"}]}";
        mvc.perform(post("/api/v1/buyers/demands")
                .contentType(MediaType.APPLICATION_JSON).content(request))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/buyers/demands")
                .header("Authorization","Bearer "+f.sellerToken())
                .contentType(MediaType.APPLICATION_JSON).content(request))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/sellers/"+UUID.randomUUID()+"/orders/"+UUID.randomUUID()+"/confirm")
                .header("Authorization","Bearer "+f.sellerToken()))
            .andExpect(status().isForbidden());
    }
}
