package kz.zakaz.deal;

import java.time.Instant;
import java.util.*;
import kz.zakaz.catalog.CanonicalProductRepository;
import kz.zakaz.catalog.CatalogSkuFactsResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Internal R2.0A writer; caller is responsible for verified buyer/seller
 * principals. No public buyer or seller deal endpoints are exposed yet.
 * Seller SKU identity and product equivalence are looked up on the server.
 */
@Service
public class DealPersistenceService {
    private final BuyerDemandSnapshotRepository demands;
    private final SellerBidSnapshotRepository bids;
    private final CanonicalProductRepository products;
    private final CatalogSkuFactsResolver catalog;
    private final BidAcceptanceRepository acceptances;
    public DealPersistenceService(BuyerDemandSnapshotRepository demands,
                                  SellerBidSnapshotRepository bids,
                                  CanonicalProductRepository products,
                                  CatalogSkuFactsResolver catalog,
                                  BidAcceptanceRepository acceptances) {
        this.demands=demands;this.bids=bids;this.products=products;this.catalog=catalog;
        this.acceptances=acceptances;
    }
    @Transactional
    public UUID createDemand(BuyerDemand demand, Instant now) {
        Objects.requireNonNull(demand);Objects.requireNonNull(now);
        if(!demand.isOpenAt(now))throw new IllegalStateException("Expired buyer demand");
        for(var line:demand.lines()) {
            var product=products.findById(line.productId())
                .orElseThrow(()->new IllegalArgumentException("Unknown canonical product"));
            if(!product.getCategory().isActive())
                throw new IllegalArgumentException("Inactive product category");
        }
        return demands.saveAndFlush(BuyerDemandSnapshot.capture(demand,now)).getId();
    }
    @Transactional
    public UUID submitBid(UUID trustedSellerId, SellerBid bid, Instant now) {
        Objects.requireNonNull(trustedSellerId);Objects.requireNonNull(bid);Objects.requireNonNull(now);
        if(!trustedSellerId.equals(bid.sellerId()))
            throw new SecurityException("Seller cannot submit on behalf of another merchant");
        var demand=demands.findForUpdate(bid.demandId())
            .orElseThrow(()->new NoSuchElementException("Buyer demand not found"));
        if(acceptances.existsByDemandId(demand.getId()))
            throw new IllegalStateException("Buyer demand already accepted");
        DealQualification.validate(demand.toDomain(),bid,now);
        int next=bids.findFirstByDemand_IdAndSellerIdOrderByRevisionDesc(
            demand.getId(),trustedSellerId).map(b->b.getRevision()+1).orElse(1);
        if(bid.revision()!=next)
            throw new IllegalArgumentException("Bid revision must equal "+next);
        for(var line:bid.lines()) {
            var facts=catalog.resolveForSeller(trustedSellerId,line.skuId());
            if(!facts.productId().equals(line.productId()))
                throw new IllegalArgumentException("Seller SKU canonical product mismatch");
        }
        return bids.saveAndFlush(SellerBidSnapshot.capture(demand,bid,now)).getId();
    }
    @Transactional(readOnly=true)
    public BuyerDemand getDemand(UUID demandId) {
        Objects.requireNonNull(demandId);
        return demands.findById(demandId)
            .orElseThrow(()->new NoSuchElementException("Buyer demand not found")).toDomain();
    }
    @Transactional(readOnly=true)
    public SellerBid getBid(UUID bidId) {
        Objects.requireNonNull(bidId);
        return bids.findById(bidId)
            .orElseThrow(()->new NoSuchElementException("Seller bid not found")).toDomain();
    }
}
