package app.fileflash.core;

public final class ShareFile {
    public String id;
    public String name;
    public long size;
    public String mime;
    public String sha256;
    public long createdAt;
    public boolean received;
    /** Completed private relay data that has not been explicitly retained by the host. */
    public boolean temporary;
    transient volatile Source source;
}
