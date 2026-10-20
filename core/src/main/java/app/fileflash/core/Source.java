package app.fileflash.core;

import java.io.IOException;
import java.io.InputStream;

/** Reopens a shared source at an absolute byte offset without buffering the file. */
@FunctionalInterface
public interface Source {
    InputStream open(long offset) throws IOException;
}
