package kz.zakaz.catalog;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

/** Separate feature gate so the existing /catalog endpoint remains unchanged. */
@RestController
@ConditionalOnProperty(name="zakaz.catalog.feed.enabled",havingValue="true")
public final class PublicCatalogFeedController {
    private final PublicCatalogFeedService service;
    public PublicCatalogFeedController(PublicCatalogFeedService service) {
        this.service=service;
    }

    @GetMapping("/api/v1/public/catalog/feed")
    public PublicCatalogFeedService.FeedPage feed(
        @RequestParam(required=false) String query,
        @RequestParam(required=false) String categoryCode,
        @RequestParam(required=false) String productType,
        @RequestParam(defaultValue="24") int limit,
        @RequestParam(required=false) String cursor) {
        return service.feed(query,categoryCode,productType,limit,cursor);
    }
}
