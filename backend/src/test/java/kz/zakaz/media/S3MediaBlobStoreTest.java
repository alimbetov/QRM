package kz.zakaz.media;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.core.sync.RequestBody;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class S3MediaBlobStoreTest {
    @Test void serverGeneratedKeyOnlyAndPrivatePutAndDelete() {
        var client=mock(S3Client.class);
        var store=new S3MediaBlobStore(client,"catalog-private");
        var key="listing/"+UUID.randomUUID()+"/"+UUID.randomUUID()+"/full.jpg";
        store.put(key,new byte[]{(byte)0xff,(byte)0xd8,(byte)0xff});
        verify(client).putObject(argThat((PutObjectRequest r)->
            r.bucket().equals("catalog-private")&&r.key().equals(key)&&
            r.contentType().equals("image/jpeg")),any(RequestBody.class));
        assertThatThrownBy(()->store.put("../etc/passwd",new byte[]{1}))
            .isInstanceOf(IllegalArgumentException.class);
        store.delete(key);
        verify(client).deleteObject(argThat((software.amazon.awssdk.services.s3.model.DeleteObjectRequest r)->r.bucket().equals("catalog-private")
            &&r.key().equals(key)));
        verifyNoMoreInteractions(client);
    }
}
