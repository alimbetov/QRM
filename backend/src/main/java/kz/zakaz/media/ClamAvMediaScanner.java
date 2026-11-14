package kz.zakaz.media;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Strict ClamAV TCP INSTREAM protocol. No shell or user-supplied URL. */
@Component
@ConditionalOnProperty(name="zakaz.media.scanner.provider",havingValue="clamav")
public final class ClamAvMediaScanner implements MediaMalwareScanner {
    private final String host;
    private final int port;

    public ClamAvMediaScanner(
        @Value("${zakaz.media.scanner.host:}") String host,
        @Value("${zakaz.media.scanner.port:3310}") int port) {
        if(host==null||host.isBlank()||port<1||port>65535)
            throw new IllegalStateException("Private ClamAV endpoint is required");
        this.host=host;this.port=port;
    }

    @Override public void requireClean(byte[] data) {
        if(data==null||data.length<1||data.length>5_000_000)
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Invalid media");
        try(var socket=new Socket()) {
            socket.connect(new InetSocketAddress(host,port),2000);
            socket.setSoTimeout(5000);
            var out=new DataOutputStream(socket.getOutputStream());
            out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            out.writeInt(data.length);
            out.write(data);
            out.writeInt(0);
            out.flush();
            var response=new ByteArrayOutputStream();
            var input=socket.getInputStream();
            int next;
            while((next=input.read())!=-1&&next!=0&&response.size()<1024) response.write(next);
            String verdict=response.toString(StandardCharsets.US_ASCII);
            if(!verdict.endsWith(" OK"))
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Image failed security scan");
        } catch(ResponseStatusException ex) {
            throw ex;
        } catch(IOException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Image security scanning unavailable");
        }
    }
}
