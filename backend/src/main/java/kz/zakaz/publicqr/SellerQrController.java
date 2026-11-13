package kz.zakaz.publicqr;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.util.*;
import kz.zakaz.domain.types.ListingStatus;
import kz.zakaz.security.SellerIdentityService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Seller-only PNG artifact; source URL is fixed by configuration, never Host header. */
@RestController
@RequestMapping("/api/v1/sellers/{sellerId}/listings")
@ConditionalOnProperty(name="zakaz.qr.http.enabled",havingValue="true")
public class SellerQrController {
    private final SellerIdentityService identities;
    private final PublicListingLinkRepository links;
    private final String publicBase;
    public SellerQrController(SellerIdentityService identities,PublicListingLinkRepository links,
                     @Value("${zakaz.qr.public-base-url:}") String publicBase){
        this.identities=identities;this.links=links;this.publicBase=publicBase;
    }
    @GetMapping(path="/{listingId}/qr.png",produces=MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> qr(@PathVariable UUID sellerId,@PathVariable UUID listingId,
                                      JwtAuthenticationToken verifiedSeller){
        var owner=identities.requireCatalogEditor(verifiedSeller,sellerId);
        var link=links.findByListing_Id(listingId)
            .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        if(!owner.equals(link.getListing().getSellerId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        if(link.getListing().getStatus()!=ListingStatus.PUBLIC)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        URI base;
        try{base=URI.create(publicBase);}
        catch(IllegalArgumentException e){
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"QR base URL not configured");
        }
        if(!"https".equalsIgnoreCase(base.getScheme())||base.getHost()==null
            ||base.getUserInfo()!=null||base.getQuery()!=null||base.getFragment()!=null
            ||!(base.getPath().isEmpty()||base.getPath().equals("/"))){
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"HTTPS QR base URL required");
        }
        var target=base.resolve("/q/"+link.getToken()).toString();
        try{
            var matrix=new QRCodeWriter().encode(target,BarcodeFormat.QR_CODE,360,360,
                Map.of(EncodeHintType.ERROR_CORRECTION,ErrorCorrectionLevel.M,
                       EncodeHintType.MARGIN,2));
            var out=new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix,"PNG",out);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.IMAGE_PNG).body(out.toByteArray());
        }catch(Exception e){
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"QR encoding failed");
        }
    }
}
