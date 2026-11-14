package kz.zakaz.media;

import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;

class ClamAvMediaScannerTest {
    @Test void streamsBytesAndRequiresCleanVerdict() throws Exception {
        try(var server=new ServerSocket(0)) {
            server.setSoTimeout(3000);
            CompletableFuture<byte[]> read=CompletableFuture.supplyAsync(()->{
                try(var client=server.accept()) {
                    client.setSoTimeout(3000);
                    DataInputStream in=new DataInputStream(client.getInputStream());
                    assertThat(new String(in.readNBytes(10),StandardCharsets.US_ASCII))
                        .isEqualTo("zINSTREAM\0");
                    int n=in.readInt();
                    byte[] data=in.readNBytes(n);
                    assertThat(in.readInt()).isZero();
                    client.getOutputStream().write("stream: OK\0".getBytes(StandardCharsets.US_ASCII));
                    client.getOutputStream().flush();
                    return data;
                } catch(Exception e) {throw new CompletionException(e);}
            });
            byte[] data="safe-demo".getBytes(StandardCharsets.UTF_8);
            new ClamAvMediaScanner("127.0.0.1",server.getLocalPort()).requireClean(data);
            assertThat(read.get(3,TimeUnit.SECONDS)).isEqualTo(data);
        }
    }

    @Test void virusVerdictIsRejected() throws Exception {
        try(var server=new ServerSocket(0)) {
            server.setSoTimeout(3000);
            var future=CompletableFuture.runAsync(()->{
                try(var client=server.accept()) {
                    client.setSoTimeout(3000);
                    DataInputStream in=new DataInputStream(client.getInputStream());
                    in.readNBytes(10);
                    int size=in.readInt();
                    in.readNBytes(size);
                    in.readInt();
                    client.getOutputStream().write(
                        "stream: Eicar-Test-Signature FOUND\0".getBytes(StandardCharsets.US_ASCII));
                    client.getOutputStream().flush();
                } catch(Exception ex) {throw new CompletionException(ex);}
            });
            assertThatThrownBy(()->new ClamAvMediaScanner(
                "127.0.0.1",server.getLocalPort()).requireClean(new byte[]{1,2,3}))
                .isInstanceOf(ResponseStatusException.class);
            future.get(3,TimeUnit.SECONDS);
        }
    }
}
