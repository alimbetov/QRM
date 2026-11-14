package kz.zakaz.media;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import kz.zakaz.security.SellerIdentityService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** Explicit opt-in endpoints. No Media controller is registered by default. */
@RestController
@ConditionalOnProperty(name="zakaz.catalog.media.enabled",havingValue="true")
public class ListingMediaController {
    public record MediaCreated(UUID mediaId){}
    private final SellerIdentityService identity;
    private final MediaUploadService upload;
    private final PublicMediaReadService publicReads;

    public ListingMediaController(SellerIdentityService identity,MediaUploadService upload,
                                  PublicMediaReadService publicReads) {
        this.identity=identity;this.upload=upload;this.publicReads=publicReads;
    }

    @PostMapping(path="/api/v1/sellers/{sellerId}/listings/{listingId}/media",
                 consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MediaCreated> upload(
            @PathVariable UUID sellerId,@PathVariable UUID listingId,
            @RequestPart("image") MultipartFile image,
            @RequestParam(defaultValue="false") boolean cover,
            JwtAuthenticationToken principal) {
        UUID owner=identity.requireCatalogEditor(principal,sellerId);
        UUID mediaId=upload.upload(owner,listingId,image,cover);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(new MediaCreated(mediaId));
    }

    @GetMapping("/api/v1/public/catalog/{listingId}/media")
    public List<PublicMediaReadService.ImageView> gallery(@PathVariable UUID listingId) {
        return publicReads.gallery(listingId);
    }

    @GetMapping("/api/v1/public/media/{mediaId}")
    public ResponseEntity<byte[]> image(@PathVariable UUID mediaId,
                                        @RequestParam(defaultValue="thumb") String size) {
        if(!size.equals("thumb")&&!size.equals("full"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid image size");
        byte[] bytes=publicReads.image(mediaId,size.equals("thumb"));
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG)
            .cacheControl(CacheControl.noStore()).body(bytes);
    }
}
