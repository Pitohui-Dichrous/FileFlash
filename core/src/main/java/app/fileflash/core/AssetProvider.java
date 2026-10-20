package app.fileflash.core;

import java.io.IOException;
import java.io.InputStream;

/** Opens an APK-bundled web asset. Paths are relative, for example index.html. */
@FunctionalInterface
public interface AssetProvider {
    InputStream open(String path) throws IOException;
}
