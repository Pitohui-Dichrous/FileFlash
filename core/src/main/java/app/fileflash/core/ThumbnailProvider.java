package app.fileflash.core;

import java.io.IOException;

/** Creates a small JPEG from a local shared source, or null when decoding is unavailable. */
@FunctionalInterface
public interface ThumbnailProvider {
    byte[] create(ShareFile file) throws IOException;
}
