package kz.zakaz.media;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;

class MediaImageSanitizerTest {
    private final MediaImageSanitizer sanitizer=new MediaImageSanitizer();

    @Test void pngIsReencodedAsJpegAndThumbHasBoundedSize() throws Exception {
        BufferedImage image=new BufferedImage(1200,600,BufferedImage.TYPE_INT_ARGB);
        var out=new ByteArrayOutputStream();
        ImageIO.write(image,"png",out);
        var file=new MockMultipartFile("image","photo.png","image/png",out.toByteArray());
        var processed=sanitizer.sanitize(file);
        assertThat(processed.width()).isEqualTo(1200);
        assertThat(processed.height()).isEqualTo(600);
        assertThat(processed.sha256()).matches("[0-9a-f]{64}");
        assertThat(processed.full()[0]&255).isEqualTo(255);
        assertThat(processed.full()[1]&255).isEqualTo(216);
        BufferedImage thumb=ImageIO.read(new java.io.ByteArrayInputStream(processed.thumbnail()));
        assertThat(Math.max(thumb.getWidth(),thumb.getHeight())).isLessThanOrEqualTo(480);
    }

    @Test void rejectsSvgPolyglotAndOversizedData() {
        byte[] svg="<svg xmlns='http://www.w3.org/2000/svg'></svg>"
            .getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(()->sanitizer.sanitize(
            new MockMultipartFile("image","asset.svg","image/jpeg",svg)))
            .isInstanceOf(ResponseStatusException.class);
        byte[] tooLarge=new byte[5_000_001];
        assertThatThrownBy(()->sanitizer.sanitize(
            new MockMultipartFile("image","large.jpg","image/jpeg",tooLarge)))
            .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->sanitizer.sanitize(
            new MockMultipartFile("image","empty.png","image/png",new byte[0])))
            .isInstanceOf(ResponseStatusException.class);
    }
}
