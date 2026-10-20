package app.fileflash.core;

public final class ShareFile {
    public String id;
    public String name;
    public long size;
    public String mime;
    public String sha256;
    public long createdAt;
    public boolean received;
    transient volatile Source source;
}
