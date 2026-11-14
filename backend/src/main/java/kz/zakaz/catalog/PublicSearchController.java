package kz.zakaz.catalog;

import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

/**
 * SEARCH-S1 public endpoints; all access to results passes authoritative
 * Catalog publication/QR gates in PublicSearchReadService.
 */
@RestController
@ConditionalOnProperty(name="zakaz.search.http.enabled",havingValue="true")
public class PublicSearchController {
    private final PublicSearchReadService searches;

    public PublicSearchController(PublicSearchReadService searches) {
        this.searches=searches;
    }

    @GetMapping("/api/v1/public/catalog/search")
    public PublicSearchReadService.SearchPage search(
        @RequestParam(name="q") String query,
        @RequestParam(required=false) String categoryCode,
        @RequestParam(required=false) String productType,
        @RequestParam(defaultValue="24") int limit,
        @RequestParam(required=false) String cursor) {
        return searches.search(query,categoryCode,productType,limit,cursor);
    }

    @GetMapping("/api/v1/public/search/suggest")
    public List<PublicSearchReadService.Suggestion> suggestions(
        @RequestParam(name="q") String query,
        @RequestParam(defaultValue="ru") String locale) {
        return searches.suggest(query,locale);
    }
}
