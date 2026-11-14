package kz.zakaz.media;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Iterator;
import javax.imageio.*;
import javax.imageio.stream.ImageInputStream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Decode+reencode JPEG/PNG into fresh RGB JPEG derivatives without EXIF/GPS.
 * Rejects oversized payload/dimensions and all other formats incl. SVG/GIF.
 * Malware/decoder fuzz and S3 storage still require independent qualification.
 */
@Component
public final class MediaImageSanitizer {
    public record Sanitized(byte[] full,byte[] thumbnail,String sha256,int width,int height) {}
    private static final int MAX_BYTES=5_000_000,MAX_WIDTH=8192,MAX_PIXELS=16_000_000;

    public Sanitized sanitize(MultipartFile file) {
        if(file==null||file.isEmpty()||file.getSize()>MAX_BYTES)
            throw invalid();
        try {
            return sanitize(file.getBytes());
        } catch(IOException ex) {
            throw invalid();
        }
    }

    /**
     * Single-buffer entry point used after AV scanning. Never read the
     * MultipartFile a second time and never retain the uploaded bytes.
     */
    public Sanitized sanitize(byte[] bytes) {
        if(bytes==null||bytes.length==0||bytes.length>MAX_BYTES)
            throw invalid();
        try {
            if(!supported(bytes))
                throw invalid();
            try(ImageInputStream stream=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                if(stream==null) throw invalid();
                Iterator<ImageReader> readers=ImageIO.getImageReaders(stream);
                if(!readers.hasNext()) throw invalid();
                ImageReader reader=readers.next();
                try {
                    reader.setInput(stream,false,true);
                    int w=reader.getWidth(0),h=reader.getHeight(0);
                    if(w<1||h<1||w>MAX_WIDTH||h>MAX_WIDTH||((long)w*h)>MAX_PIXELS)
                        throw invalid();
                    BufferedImage input=reader.read(0);
                    if(input==null) throw invalid();
                    BufferedImage full=scale(input,1600);
                    BufferedImage thumb=scale(input,480);
                    byte[] mainBytes=jpeg(full);
                    byte[] thumbBytes=jpeg(thumb);
                    byte[] digest=MessageDigest.getInstance("SHA-256").digest(mainBytes);
                    return new Sanitized(mainBytes,thumbBytes,HexFormat.of().formatHex(digest),
                                         full.getWidth(),full.getHeight());
                } finally { reader.dispose(); }
            }
        } catch(ResponseStatusException ex) { throw ex; }
        catch(Exception ex) { throw invalid(); }
    }

    private static boolean supported(byte[] b) {
        boolean jpg=b.length>3 && (b[0]&255)==0xff && (b[1]&255)==0xd8 && (b[2]&255)==0xff;
        boolean png=b.length>8 && (b[0]&255)==137 && b[1]==80 && b[2]==78 && b[3]==71
                       && b[4]==13 && b[5]==10 && b[6]==26 && b[7]==10;
        return jpg||png;
    }

    private static BufferedImage scale(BufferedImage src,int maxSide) {
        double factor=Math.min(1.0,(double)maxSide/Math.max(src.getWidth(),src.getHeight()));
        int w=Math.max(1,(int)Math.round(src.getWidth()*factor));
        int h=Math.max(1,(int)Math.round(src.getHeight()*factor));
        BufferedImage result=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);
        Graphics2D g=result.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0,0,w,h);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(src,0,0,w,h,null);
        } finally {g.dispose();}
        return result;
    }

    private static byte[] jpeg(BufferedImage image) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        if(!ImageIO.write(image,"jpeg",out)) throw new IOException("JPEG encoder unavailable");
        return out.toByteArray();
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Invalid or unsupported image");
    }
}
