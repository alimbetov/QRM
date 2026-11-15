package kz.zakaz.catalog;

import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

/** Public, bounded listing discovery. No stock, seller contact or private SKU data. */
@RestController
@ConditionalOnProperty(name="zakaz.catalog.http.enabled",havingValue="true")
@RequestMapping("/api/v1/public/catalog")
public class PublicCatalogController {
    private final PublicCatalogReadService reads;
    public PublicCatalogController(PublicCatalogReadService reads) { this.reads=reads; }

    @GetMapping
    public PublicCatalogReadService.CatalogPage search(
        @RequestParam(required=false) String query,
        @RequestParam(required=false) String categoryCode,
        @RequestParam(defaultValue="0") int page,
        @RequestParam(defaultValue="12") int size) {
        return reads.search(query,categoryCode,page,size);
    }

    @GetMapping("/categories")
    public List<PublicCatalogReadService.CategoryOption> categories() {
        return reads.categories();
    }
}
