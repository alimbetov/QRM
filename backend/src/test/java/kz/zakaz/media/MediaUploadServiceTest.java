package kz.zakaz.media;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import kz.zakaz.catalog.SellerListing;
import kz.zakaz.catalog.SellerListingRepository;
import kz.zakaz.domain.types.ListingStatus;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Critical resource-safety tests of server-proxied uploads; no RustFS credentials needed. */
class MediaUploadServiceTest {
    @Test void scanAndDecodeReceiveSameBytesAndMultipartIsReadOnlyOnce() throws Exception {
        var seller=UUID.randomUUID();
        var listingId=UUID.randomUUID();
        var listing=mock(SellerListing.class);
        var listings=mock(SellerListingRepository.class);
        var jdbc=mock(JdbcTemplate.class);
        var tx=mock(TransactionTemplate.class);
        var sanitizer=mock(MediaImageSanitizer.class);
        var scanner=mock(MediaMalwareScanner.class);
        var blobs=mock(ListingMediaBlobStore.class);
        byte[] bytes=new byte[]{(byte)0xff,(byte)0xd8,(byte)0xff,3,4};
        var file=mock(org.springframework.web.multipart.MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getSize()).thenReturn((long)bytes.length);
        when(file.getBytes()).thenReturn(bytes);
        when(listings.findById(listingId)).thenReturn(Optional.of(listing));
        when(listing.getSellerId()).thenReturn(seller);
        when(listing.getStatus()).thenReturn(ListingStatus.DRAFT);
        when(sanitizer.sanitize(bytes)).thenReturn(
            new MediaImageSanitizer.Sanitized(new byte[]{1},new byte[]{2},
                "a".repeat(64),1,1));
        var service=new MediaUploadService(listings,jdbc,tx,sanitizer,scanner,blobs,1);

        service.upload(seller,listingId,file,false);
        verify(file,times(1)).getBytes();
        verify(scanner).requireClean(same(bytes));
        verify(sanitizer).sanitize(same(bytes));
        verify(blobs,times(2)).put(any(),any(byte[].class));
    }

    @Test void concurrentUploadLimitReturns429BeforeAdditionalImageReads() throws Exception {
        var seller=UUID.randomUUID();
        var listingId=UUID.randomUUID();
        var listing=mock(SellerListing.class);
        var listings=mock(SellerListingRepository.class);
        var jdbc=mock(JdbcTemplate.class);
        var tx=mock(TransactionTemplate.class);
        var sanitizer=mock(MediaImageSanitizer.class);
        var scanner=mock(MediaMalwareScanner.class);
        var blobs=mock(ListingMediaBlobStore.class);
        var first=mock(org.springframework.web.multipart.MultipartFile.class);
        var second=mock(org.springframework.web.multipart.MultipartFile.class);
        byte[] bytes=new byte[]{1,2,3};
        when(listings.findById(listingId)).thenReturn(Optional.of(listing));
        when(listing.getSellerId()).thenReturn(seller);
        when(listing.getStatus()).thenReturn(ListingStatus.DRAFT);
        when(first.isEmpty()).thenReturn(false);
        when(first.getSize()).thenReturn(3L);
        when(first.getBytes()).thenReturn(bytes);
        var entered=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        doAnswer(invocation->{
            entered.countDown();
            if(!release.await(5,TimeUnit.SECONDS))
                throw new IllegalStateException("Test upload timed out");
            return null;
        }).when(scanner).requireClean(any(byte[].class));
        when(sanitizer.sanitize(bytes)).thenReturn(
            new MediaImageSanitizer.Sanitized(new byte[]{1},new byte[]{2},
                "a".repeat(64),1,1));
        var service=new MediaUploadService(listings,jdbc,tx,sanitizer,scanner,blobs,1);
        var ongoing=CompletableFuture.runAsync(()->service.upload(
            seller,listingId,first,true));
        try {
            assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->service.upload(seller,listingId,second,true))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(t->assertThat(((ResponseStatusException)t).getStatusCode())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
            verify(second,never()).getBytes();
        } finally {
            release.countDown();
        }
        ongoing.get(5,TimeUnit.SECONDS);
    }

    @Test void invalidConcurrencyConfigurationFailsAtStartup() {
        assertThatThrownBy(()->new MediaUploadService(
            mock(SellerListingRepository.class),mock(JdbcTemplate.class),
            mock(TransactionTemplate.class),mock(MediaImageSanitizer.class),
            mock(MediaMalwareScanner.class),mock(ListingMediaBlobStore.class),0))
            .isInstanceOf(IllegalStateException.class);
    }
}
