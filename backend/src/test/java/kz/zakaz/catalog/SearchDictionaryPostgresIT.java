package kz.zakaz.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import kz.zakaz.domain.types.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test-pg")
@TestPropertySource(properties={
    "zakaz.catalog.http.enabled=true",
    "zakaz.search.http.enabled=true",
    "zakaz.search.cursor-secret-base64=U0VBUkNILVQwMS1ub24tcHJvZHVjdGlvbi1yYW5kb20tMzItYnl0ZXM="
})
class SearchDictionaryPostgresIT {
    @Container static PostgreSQLContainer<?> pg=new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", pg::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired CategoryRepository categories;
    @Autowired CanonicalProductRepository products;
    @Autowired SellerListingRepository listings;
    @Autowired SellerSkuRepository skus;
    @Autowired CatalogPublishingService publishing;

    private record Hierarchy(Category root,Category child) {}

    private Hierarchy hierarchy(ProductType kind) {
        String n=UUID.randomUUID().toString().replace("-","");
        String prefix=String.format("%02d%02d%03d",
            1+Integer.parseInt(n.substring(0,2),16)%99,
            1+Integer.parseInt(n.substring(2,4),16)%99,
            1+Integer.parseInt(n.substring(4,7),16)%999);
        var root=categories.saveAndFlush(Category.create(prefix+"0000000000000",kind,
            kind==ProductType.SERVICE?"Услуги":"Инструменты",null));
        var child=categories.saveAndFlush(Category.create(prefix+"00001"+"00000000",kind,
            kind==ProductType.SERVICE?"Ремонт":"Шлифмашины",root));
        return new Hierarchy(root,child);
    }

    private SellerListing listing(Category category,String title,boolean published,int skuCount) {
        var product=products.saveAndFlush(CanonicalProduct.create(category,title));
        UUID seller=UUID.randomUUID();
        var listing=listings.saveAndFlush(SellerListing.draft(seller,product));
        for(int i=0;i<skuCount;i++) {
            skus.saveAndFlush(SellerSku.create(listing,"SKU-"+i,
                StockTrackingMode.TRACKED,MeasurementUnit.PCS));
        }
        if(published) publishing.publishForSeller(seller,listing.getId());
        return listing;
    }

    private void dictionary(String term,String locale,Category category,String relationship) {
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO catalog_search_term(id,term,normalized_term,locale,status,revision,created_at)
            VALUES (?,?,?,?, 'ACTIVE',1,clock_timestamp())
            """,id,term,term.toLowerCase(Locale.ROOT),locale);
        jdbc.update("""
            INSERT INTO catalog_term_category(term_id,category_id,relation_type,weight)
            VALUES (?,?,?,100)
            """,id,category.getId(),relationship);
    }

    private JsonNode search(String q,String extra) throws Exception {
        var request=get("/api/v1/public/catalog/search").param("q",q);
        if(extra!=null&&!extra.isBlank()) {
            for(String pair:extra.replaceFirst("^&","").split("&")) {
                String[] kv=pair.split("=",2);
                request.param(kv[0],kv.length==2?kv[1]:"");
            }
        }
        var result=mvc.perform(request).andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    @Test void aliasFindsPublishedDescendantOnlyAndOneCardPerListing() throws Exception {
        var h=hierarchy(ProductType.PHYSICAL);
        var visible=listing(h.child(),"Угловая шлифовальная машина",true,3);
        listing(h.child(),"Черновик УШМ",false,1);
        var hidden=listing(h.child(),"Скрытая УШМ",true,1);
        jdbc.update("UPDATE catalog_listing SET status='UNLISTED' WHERE id=?",hidden.getId());
        var service=hierarchy(ProductType.SERVICE);
        listing(service.child(),"Ремонт угловой шлифмашины",true,1);
        dictionary("болгарка","ru",h.root(),"SYNONYM");

        JsonNode result=search("болгарка","&limit=24");
        assertThat(result.path("items").size()).isEqualTo(1);
        assertThat(result.path("items").get(0).path("listingId").asText())
            .isEqualTo(visible.getId().toString());
        assertThat(result.path("items").get(0).path("variants").size()).isEqualTo(3);
        assertThat(result.path("items").get(0).path("variants").get(0).path("priceKzt").isNull()).isTrue();
        assertThat(result.path("items").get(0).has("phone")).isFalse();
    }

    @Test void relatedIsNotExactMatchAndInactiveCategoryIsHidden() throws Exception {
        var h=hierarchy(ProductType.PHYSICAL);
        var glue=listing(h.child(),"Клей специализированный",true,1);
        String term="плитка"+UUID.randomUUID().toString().substring(0,7);
        dictionary(term,"ru",h.root(),"RELATED");
        assertThat(search(term,"").path("items").size()).isZero();
        dictionary("лобзик"+UUID.randomUUID().toString().substring(0,7),"ru",h.root(),"SYNONYM");
        jdbc.update("UPDATE catalog_category SET active=false WHERE id=?",h.child().getId());
        JsonNode inactive=search("Клей специализированный","");
        assertThat(inactive.path("items").toString()).doesNotContain(glue.getId().toString());
    }

    @Test void cursorRejectsTamperingOrDifferentSearchAndIsStable() throws Exception {
        var h=hierarchy(ProductType.PHYSICAL);
        String alias="лобзик"+UUID.randomUUID().toString().substring(0,7);
        dictionary(alias,"ru",h.root(),"CATEGORY_TERM");
        for(int i=0;i<5;i++)listing(h.child(),"Электролобзик "+i,true,2);
        JsonNode first=search(alias,"&limit=2");
        assertThat(first.path("items").size()).isEqualTo(2);
        String cursor=first.path("nextCursor").asText();
        assertThat(cursor).isNotBlank();
        JsonNode second=search(alias,"&limit=2&cursor="+cursor);
        assertThat(second.path("items").size()).isEqualTo(2);
        Set<String> ids=new HashSet<>();
        first.path("items").forEach(n->ids.add(n.path("listingId").asText()));
        second.path("items").forEach(n->
            assertThat(ids.add(n.path("listingId").asText())).isTrue());

        mvc.perform(get("/api/v1/public/catalog/search")
            .param("q","несовпадение").param("cursor",cursor))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/public/catalog/search")
            .param("q",alias).param("cursor",cursor+"x"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/public/catalog/search")
            .param("q",alias).param("limit","1000"))
            .andExpect(status().isBadRequest());
    }


    @Test void fourLocalesAndHccParentFilterFindSamePublishedListing() throws Exception {
        var h=hierarchy(ProductType.PHYSICAL);
        String suffix=UUID.randomUUID().toString().replace("-","").substring(0,7);
        var target=listing(h.child(),"Аккумуляторный инструмент "+suffix,true,1);
        Map<String,String> terms=Map.of(
            "ru","болгарка"+suffix,
            "kk","бұрыштық тегістеуіш"+suffix,
            "en","angle grinder "+suffix,
            "zh","角磨机"+suffix);
        for(var entry:terms.entrySet()) {
            dictionary(entry.getValue(),entry.getKey(),h.child(),"SYNONYM");
            var matched=search(entry.getValue(),
                "&categoryCode="+h.root().getHccCode()+"&productType=PHYSICAL");
            assertThat(matched.path("items").size()).isEqualTo(1);
            assertThat(matched.path("items").get(0).path("listingId").asText())
                .isEqualTo(target.getId().toString());
            assertThat(search(entry.getValue(),"&productType=SERVICE")
                .path("items").size()).isZero();
        }
    }

    @Test void dictionaryRevisionRevokesCursorAndMalformedInputIsRejected() throws Exception {
        var h=hierarchy(ProductType.PHYSICAL);
        String suffix=UUID.randomUUID().toString().replace("-","").substring(0,7);
        String term="электропила"+suffix;
        dictionary(term,"ru",h.root(),"CATEGORY_TERM");
        for(int i=0;i<3;i++) listing(h.child(),"Ручная электрическая пила "+i,true,1);
        String cursor=search(term,"&limit=1").path("nextCursor").asText();
        assertThat(cursor).isNotBlank();
        dictionary("другой термин"+suffix,"ru",h.root(),"CATEGORY_TERM");
        mvc.perform(get("/api/v1/public/catalog/search")
            .param("q",term).param("cursor",cursor))
            .andExpect(status().isConflict());
        mvc.perform(get("/api/v1/public/catalog/search")
            .param("q","%")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/public/catalog/search")
            .param("q","' OR 1=1 --")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/public/catalog/search")
            .param("q",term).param("categoryCode","invalid"))
            .andExpect(status().isBadRequest());
    }

    @Test void suggestionsAreLanguageScopedAndDoNotExposeInactiveTerms() throws Exception {
        var h=hierarchy(ProductType.PHYSICAL);
        String word="сверло"+UUID.randomUUID().toString().substring(0,7);
        dictionary(word,"ru",h.root(),"CATEGORY_TERM");
        var response=mvc.perform(get("/api/v1/public/search/suggest").param("q",word.substring(0,6))
            .param("locale","ru")).andExpect(status().isOk()).andReturn();
        assertThat(response.getResponse().getContentAsString()).contains(word);
        var wrongLocale=mvc.perform(get("/api/v1/public/search/suggest").param("q",word)
            .param("locale","zh")).andExpect(status().isOk()).andReturn();
        assertThat(wrongLocale.getResponse().getContentAsString()).doesNotContain(word);
    }
}
