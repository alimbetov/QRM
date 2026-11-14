package kz.zakaz.intelligence;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import kz.zakaz.deal.*;
import static org.assertj.core.api.Assertions.*;

class IntelligenceFoundationTest {
    final UUID product = UUID.randomUUID(), sku = UUID.randomUUID();
    final Instant now = Instant.parse("2026-11-12T10:00:00Z");
    ClassifiedOfferMatcher.RequiredItem required() {
        return new ClassifiedOfferMatcher.RequiredItem(product, "0102003",
            Map.of("brand","SAMSUNG", "storage_gb","256"),
            Map.of("color","BLACK"));
    }
    ClassifiedOfferMatcher.SellerItem seller(String storage) {
        return new ClassifiedOfferMatcher.SellerItem(product, sku, "0102003",
            Map.of("brand","SAMSUNG","storage_gb",storage,"color","BLACK"),true,true,true);
    }
    @Test void hardStorageMismatchRejectsBeforeMl() {
        var m = ClassifiedOfferMatcher.match(required(), seller("128"));
        assertThat(m.eligible()).isFalse();
        assertThat(m.reasons()).contains("HARD_ATTRIBUTE_MISMATCH:storage_gb");
        assertThatThrownBy(() -> DealFeatureProjector.encode(m,true,true,BigDecimal.TEN,
            BigDecimal.valueOf(100),null)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void compatibleSellerProducesReproducibleNamedAndDenseVector() {
        var m = ClassifiedOfferMatcher.match(required(),seller("256"));
        assertThat(m.eligible()).isTrue();
        var v1 = DealFeatureProjector.encode(m,true,true,new BigDecimal("155000"),
            new BigDecimal("160000"),0.80);
        var v2 = DealFeatureProjector.encode(m,true,true,new BigDecimal("155000"),
            new BigDecimal("160000"),0.80);
        assertThat(v1.features()).isEqualTo(v2.features());
        assertThat(v1.dense()).containsExactly(v2.dense());
        assertThat(v1.dense()).hasSize(8);
        assertThat(v1.dense()[4]).isCloseTo(0.96875, within(0.0000001));
        assertThat(v1.features()).extracting(FeatureVectorEncoder.NamedFeature::name)
            .contains("MAT.hard_eligible","OFR.price_to_budget_ratio_known");
        var returned = v1.dense(); returned[0] = 99;
        assertThat(v1.dense()[0]).isEqualTo(1.0);
    }
    @Test void unknownValueDoesNotMeanZero() {
        var m = ClassifiedOfferMatcher.match(required(),seller("256"));
        var v = DealFeatureProjector.encode(m,true,false,null,null,null);
        assertThat(v.dense()[5]).isZero();
        assertThat(v.dense()[7]).isZero();
    }
    @Test void registryMustFreezeNamesAndRejectUnknownFeatures() {
        var registry = new FeatureRegistry("test","v1",List.of("a","b"));
        assertThatThrownBy(() -> new FeatureRegistry("test","v1",List.of("a","a")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FeatureVectorEncoder.encode(registry,Map.of("c",1.0)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FeatureVectorEncoder.encode(registry,Map.of("a",Double.NaN)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(FeatureVectorEncoder.exactMatch("4001","4002")).isZero();
        assertThat(FeatureVectorEncoder.exactMatch("4001","4001")).isEqualTo(1.0);
    }
    @Test void basketValidatesExistingBidAndEachClassifiedLine() {
        var demand = new BuyerDemand(UUID.randomUUID(),UUID.randomUUID(),"Алматы",now.plusSeconds(100),
            List.of(new BuyerDemand.DemandLine(product,BigDecimal.ONE,BigDecimal.valueOf(160000))));
        var bid = new SellerBid(UUID.randomUUID(),demand.id(),UUID.randomUUID(),1,now.plusSeconds(100),
            List.of(new SellerBid.BidLine(product,sku,BigDecimal.ONE,BigDecimal.valueOf(155000))));
        var result = ClassifiedOfferMatcher.matchBasket(demand,bid,now,Map.of(product,required()),
            Map.of(sku,seller("256")));
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().eligible()).isTrue();
        assertThat(ClassifiedOfferMatcher.matchBasket(demand,bid,now,Map.of(product,required()),
            Map.of(sku,seller("128"))).getFirst().eligible()).isFalse();
    }
    @Test void unverifiedOrOutsideCoverageSellerRejected() {
        var s = new ClassifiedOfferMatcher.SellerItem(product,sku,"0102003",
            Map.of("brand","SAMSUNG","storage_gb","256"),false,true,false);
        assertThat(ClassifiedOfferMatcher.match(required(),s).reasons())
            .contains("SELLER_NOT_VERIFIED","SERVICE_AREA_NOT_COVERED");
    }
}
