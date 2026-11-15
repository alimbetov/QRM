package kz.zakaz.publicqr;

import com.google.zxing.*;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import java.io.ByteArrayInputStream;
import java.util.*;
import javax.imageio.ImageIO;
import kz.zakaz.catalog.*;
import kz.zakaz.domain.types.*;
import kz.zakaz.security.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
@TestPropertySource(properties={
    "zakaz.qr.http.enabled=true",
    "zakaz.qr.public-base-url=https://shop.example.test"})
class SellerQrSecurityIT {
    @Autowired MockMvc mvc;
    @Autowired CategoryRepository categories;
    @Autowired CanonicalProductRepository products;
    @Autowired SellerListingRepository listings;
    @Autowired SellerSkuRepository skus;
    @Autowired CatalogPublishingService publisher;
    @Autowired SellerMembershipRepository members;
    @Autowired PublicListingLinkRepository links;
    @MockitoBean JwtDecoder decoder;

    @Test void onlyAuthorizedSellerCanDownloadRealDecodablePng() throws Exception{
        String code=String.format("%02d%02d%03d",1+Math.floorMod(UUID.randomUUID().hashCode(),99),
            1+Math.floorMod(UUID.randomUUID().hashCode(),99),
            1+Math.floorMod(UUID.randomUUID().hashCode(),999))+"0000000000000";
        var category=categories.saveAndFlush(Category.create(code,ProductType.PHYSICAL,"Electronics",null));
        var product=products.saveAndFlush(CanonicalProduct.create(category,"Phone"));
        UUID seller=UUID.randomUUID();var listing=listings.saveAndFlush(SellerListing.draft(seller,product));
        skus.saveAndFlush(SellerSku.create(listing,"QR-PHONE",StockTrackingMode.TRACKED,MeasurementUnit.PCS));
        publisher.publishForSeller(seller,listing.getId());
        var link=links.findByListing_Id(listing.getId()).orElseThrow();
        var url="/api/v1/sellers/"+seller+"/listings/"+listing.getId()+"/qr.png";
        mvc.perform(get(url)).andExpect(status().isUnauthorized());
        var jwt=Jwt.withTokenValue("test").header("alg","RS256")
            .issuer("https://idp.example.test").subject("merchant-owner")
            .claim("scope","catalog:write").build();
        when(decoder.decode("seller-token")).thenReturn(jwt);
        mvc.perform(get(url).header("Authorization","Bearer seller-token"))
            .andExpect(status().isForbidden());
        members.saveAndFlush(SellerMembership.grant(
            "https://idp.example.test","merchant-owner",seller,SellerMembership.Role.CATALOG_EDITOR));
        var response=mvc.perform(get(url).header("Authorization","Bearer seller-token"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.IMAGE_PNG)).andReturn();
        var image=ImageIO.read(new ByteArrayInputStream(response.getResponse().getContentAsByteArray()));
        assertThat(image).isNotNull();
        var bitmap=new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)));
        assertThat(new MultiFormatReader().decode(bitmap).getText())
            .isEqualTo("https://shop.example.test/q/"+link.getToken());
    }
}
