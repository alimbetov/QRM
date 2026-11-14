package kz.zakaz.media;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Explicitly restricted to dev/test; NOT a production antivirus substitute. */
@Component
@Profile({"dev","test","test-pg"})
@ConditionalOnProperty(name="zakaz.media.scanner.provider",havingValue="dev-only")
final class DevOnlyMediaScanner implements MediaMalwareScanner {
    @Override public void requireClean(byte[] data) {
        if(data==null||data.length<1||data.length>5_000_000)
            throw new IllegalArgumentException("Invalid dev media");
    }
}
