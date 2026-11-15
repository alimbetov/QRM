package kz.zakaz.deal;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Deterministic validation only. True merchant/product ownership must be verified against
 * a server-owned catalog; client-provided sellerId/skuId is never accepted as proof.
 */
public final class DealQualification {
    private DealQualification() {}

    public static void validate(BuyerDemand demand, SellerBid offer, Instant now) {
        Objects.requireNonNull(demand); Objects.requireNonNull(offer); Objects.requireNonNull(now);
        if (!demand.id().equals(offer.demandId())) throw new IllegalArgumentException("Wrong demand");
        if (!demand.isOpenAt(now) || !now.isBefore(offer.expiresAt()))
            throw new IllegalStateException("Expired demand or offer");
        var demandLines = demand.lines().stream()
                .collect(Collectors.toMap(BuyerDemand.DemandLine::productId, l -> l));
        if (offer.lines().size() != demandLines.size())
            throw new IllegalArgumentException("Partial basket offers not allowed in this MVP slice");
        for (var l : offer.lines()) {
            var requested = demandLines.get(l.productId());
            if (requested == null || l.quantity().compareTo(requested.quantity()) != 0)
                throw new IllegalArgumentException("Offer differs from requested basket");
        }
        // maxUnitPrice is buyer guidance, NOT a cap on incoming seller bids:
        // a higher price may be viewed but requires explicit buyer acceptance.
    }

    public static List<SellerBid> rankedOffers(BuyerDemand demand, Collection<SellerBid> offers, Instant now) {
        Objects.requireNonNull(offers);
        var valid = new ArrayList<SellerBid>();
        for (var bid : offers) {
            try { validate(demand, bid, now); valid.add(bid); }
            catch (IllegalArgumentException | IllegalStateException ignored) { /* invalid offers excluded */ }
        }
        valid.sort(Comparator.comparing(SellerBid::total).thenComparing(SellerBid::id));
        return List.copyOf(valid);
    }
}
