package kz.zakaz.media;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;

class MediaImageRegressionTest {
    private final MediaImageSanitizer sanitizer = new MediaImageSanitizer();

    private static MockMultipartFile png(int width,int height) throws Exception {
        var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
        var stream=new ByteArrayOutputStream();
        ImageIO.write(image,"png",stream);
        return new MockMultipartFile("image","upload.png","image/png",stream.toByteArray());
    }

    @Test void derivativeIsBoundedJpegWithContentDerivedDigest() throws Exception {
        var result=sanitizer.sanitize(png(2000,1000));
        assertThat(result.width()).isEqualTo(1600);
        assertThat(result.height()).isEqualTo(800);
        var full=ImageIO.read(new ByteArrayInputStream(result.full()));
        var thumb=ImageIO.read(new ByteArrayInputStream(result.thumbnail()));
        assertThat(full.getWidth()).isEqualTo(1600);
        assertThat(full.getHeight()).isEqualTo(800);
        assertThat(Math.max(thumb.getWidth(),thumb.getHeight())).isLessThanOrEqualTo(480);
        assertThat(result.sha256()).isEqualTo(HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(result.full())));
        assertThat(result.full()[0]&255).isEqualTo(255);
        assertThat(result.full()[1]&255).isEqualTo(216);
        // Transparent source pixels are flattened onto an opaque white background.
        assertThat(full.getRGB(100,100)&0xFFFFFF).isEqualTo(0xFFFFFF);
    }

    @Test void corruptImageAndUnsupportedMagicBytesFailWith422() {
        for(byte[] invalid : new byte[][] {
            {0x47,0x49,0x46,0x38,0x39,0x61},
            {(byte)0xff,(byte)0xd8,(byte)0xff,0x00,0x01},
            {(byte)0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a,0x00}
        }) {
            assertThatThrownBy(() -> sanitizer.sanitize(new MockMultipartFile(
                "image","upload.jpg","image/jpeg",invalid)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException)e).getStatusCode())
                    .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        }
    }
}
