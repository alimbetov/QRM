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
    @Test void forgedSellerIsRejectedBeforeImageReadOrObjectStorage() throws Exception {
        UUID seller=UUID.randomUUID(),listingId=UUID.randomUUID();
        var listing=mock(SellerListing.class);
        var repository=mock(SellerListingRepository.class);
        var store=mock(ListingMediaBlobStore.class);
        var multipart=mock(org.springframework.web.multipart.MultipartFile.class);
        var scanner=mock(MediaMalwareScanner.class);
        when(repository.findById(listingId)).thenReturn(Optional.of(listing));
        when(listing.getSellerId()).thenReturn(UUID.randomUUID());
        var service=new MediaUploadService(repository,mock(JdbcTemplate.class),
            mock(TransactionTemplate.class),mock(MediaImageSanitizer.class),
            scanner,store,2);
        assertThatThrownBy(()->service.upload(seller,listingId,multipart,true))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e->assertThat(((ResponseStatusException)e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND));
        verify(multipart,never()).getBytes();
        verifyNoInteractions(scanner,store);
    }

    @Test void scannerOutageFailsClosedWithoutStoringObject() throws Exception {
        UUID seller=UUID.randomUUID(),listingId=UUID.randomUUID();
        var listing=mock(SellerListing.class);
        var repository=mock(SellerListingRepository.class);
        var store=mock(ListingMediaBlobStore.class);
        var scanner=mock(MediaMalwareScanner.class);
        var multipart=mock(org.springframework.web.multipart.MultipartFile.class);
        when(repository.findById(listingId)).thenReturn(Optional.of(listing));
        when(listing.getSellerId()).thenReturn(seller);
        when(listing.getStatus()).thenReturn(ListingStatus.DRAFT);
        when(multipart.isEmpty()).thenReturn(false);
        when(multipart.getSize()).thenReturn(3L);
        byte[] payload=new byte[]{1,2,3};
        when(multipart.getBytes()).thenReturn(payload);
        doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"AV down"))
            .when(scanner).requireClean(same(payload));
        var service=new MediaUploadService(repository,mock(JdbcTemplate.class),
            mock(TransactionTemplate.class),mock(MediaImageSanitizer.class),
            scanner,store,2);
        assertThatThrownBy(()->service.upload(seller,listingId,multipart,false))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e->assertThat(((ResponseStatusException)e).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        verifyNoInteractions(store);
    }

    @Test void storageFailureCleansPartialObjectsAndSkipsDatabaseWrite() throws Exception {
        UUID seller=UUID.randomUUID(),listingId=UUID.randomUUID();
        var listing=mock(SellerListing.class);
        var repository=mock(SellerListingRepository.class);
        var store=mock(ListingMediaBlobStore.class);
        var scanner=mock(MediaMalwareScanner.class);
        var sanitizer=mock(MediaImageSanitizer.class);
        var multipart=mock(org.springframework.web.multipart.MultipartFile.class);
        var tx=mock(TransactionTemplate.class);
        when(repository.findById(listingId)).thenReturn(Optional.of(listing));
        when(listing.getSellerId()).thenReturn(seller);
        when(listing.getStatus()).thenReturn(ListingStatus.DRAFT);
        when(multipart.isEmpty()).thenReturn(false);
        when(multipart.getSize()).thenReturn(3L);
        byte[] payload=new byte[]{1,2,3};
        when(multipart.getBytes()).thenReturn(payload);
        when(sanitizer.sanitize(payload)).thenReturn(
            new MediaImageSanitizer.Sanitized(new byte[]{1},new byte[]{2},
                "a".repeat(64),1,1));
        doThrow(new IllegalStateException("RustFS unavailable"))
            .when(store).put(any(),any(byte[].class));
        var service=new MediaUploadService(repository,mock(JdbcTemplate.class),
            tx,sanitizer,scanner,store,2);
        assertThatThrownBy(()->service.upload(seller,listingId,multipart,false))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("RustFS");
        verify(store,times(2)).delete(any());
        verifyNoInteractions(tx);
    }

    @Test void databaseCommitFailureCompensatesBothStoredObjects() throws Exception {
        UUID seller=UUID.randomUUID(),listingId=UUID.randomUUID();
        var listing=mock(SellerListing.class);
        var repository=mock(SellerListingRepository.class);
        var store=mock(ListingMediaBlobStore.class);
        var sanitizer=mock(MediaImageSanitizer.class);
        var multipart=mock(org.springframework.web.multipart.MultipartFile.class);
        var tx=mock(TransactionTemplate.class);
        when(repository.findById(listingId)).thenReturn(Optional.of(listing));
        when(listing.getSellerId()).thenReturn(seller);
        when(listing.getStatus()).thenReturn(ListingStatus.DRAFT);
        when(multipart.isEmpty()).thenReturn(false);
        when(multipart.getSize()).thenReturn(3L);
        byte[] payload=new byte[]{1,2,3};
        when(multipart.getBytes()).thenReturn(payload);
        when(sanitizer.sanitize(payload)).thenReturn(
            new MediaImageSanitizer.Sanitized(new byte[]{1},new byte[]{2},
                "a".repeat(64),1,1));
        when(tx.execute(any())).thenThrow(new IllegalStateException("PostgreSQL failed"));
        var service=new MediaUploadService(repository,mock(JdbcTemplate.class),
            tx,sanitizer,mock(MediaMalwareScanner.class),store,2);
        assertThatThrownBy(()->service.upload(seller,listingId,multipart,false))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("PostgreSQL");
        verify(store,times(2)).put(any(),any(byte[].class));
        verify(store,times(2)).delete(any());
    }

}
