package kz.zakaz.publicqr;

import java.util.*;
import kz.zakaz.catalog.*;
import kz.zakaz.domain.types.ListingStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Explicit DTO whitelist: no stock balances, buyer identity or warehouse address. */
@Service
public class PublicQrCatalogService {
    public record Variant(UUID id,String code,String unit,String priceKzt){}
    public record ListingView(UUID listingId,UUID sellerId,String title,
                              String category,List<Variant> variants){}
    private final PublicListingLinkRepository links;
    private final SellerSkuRepository skus;
    public PublicQrCatalogService(PublicListingLinkRepository links,SellerSkuRepository skus){
        this.links=links;this.skus=skus;
    }
    @Transactional(readOnly=true)
    public ListingView view(String token){
        var link=findPublicLink(token);
        var listing=link.getListing();
        var variants=skus.findByListingId(listing.getId()).stream()
            .sorted(Comparator.comparing(SellerSku::getId))
            .map(s->new Variant(s.getId(),s.getSellerSkuCode(),
                s.getSalesUnit()==null?null:s.getSalesUnit().name(),
                s.getPublicUnitPriceKzt()==null?null:s.getPublicUnitPriceKzt().toPlainString())).toList();
        return new ListingView(listing.getId(),listing.getSellerId(),
            listing.getProduct().getCanonicalName(),
            listing.getProduct().getCategory().getLabel(),variants);
    }
    @Transactional(readOnly=true)
    public PublicListingLink findPublicLink(String token){
        if(token==null||!token.matches("[A-Za-z0-9_-]{43}"))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var link=links.findByToken(token)
            .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        if(link.getListing().getStatus()!=ListingStatus.PUBLIC)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return link;
    }
}
