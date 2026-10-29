package app.fileflash.core;

import java.util.Locale;
import java.util.Set;
import java.util.Arrays;
import java.util.HashSet;

/** File-list presentation only; never changes a file's bytes, name or download MIME type. */
public enum FileKind {
    IMAGE, VIDEO, DOCUMENT, AUDIO, OTHER;

    private static final Set<String> IMAGES = extensions("jpg", "jpeg", "jpe", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "tif", "tiff", "dng", "ico", "svg");
    private static final Set<String> VIDEOS = extensions("mp4", "m4v", "mov", "qt", "webm", "mkv", "avi", "3gp", "3g2", "mpeg", "mpg", "mpe", "ts", "m2ts", "mts", "wmv", "flv");
    private static final Set<String> DOCUMENTS = extensions("pdf", "xml", "txt", "text", "md", "markdown", "csv", "tsv", "json", "yaml", "yml", "log", "rtf", "doc", "docx", "odt", "xls", "xlsx", "ods", "ppt", "pptx", "odp", "epub", "html", "htm", "xhtml", "ini", "conf", "toml");
    private static final Set<String> AUDIO_FILES = extensions("mp3", "wav", "wave", "ncm", "flac", "aac", "m4a", "m4b", "ogg", "oga", "opus", "wma", "aiff", "aif", "amr", "ape", "alac", "mid", "midi");

    private static Set<String> extensions(String... values) { return new HashSet<>(Arrays.asList(values)); }

    public static FileKind classify(String name, String mime) {
        String normalizedName = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        int dot = normalizedName.lastIndexOf('.');
        String extension = dot < 0 ? "" : normalizedName.substring(dot + 1);
        if (IMAGES.contains(extension)) return IMAGE;
        if (VIDEOS.contains(extension)) return VIDEO;
        if (DOCUMENTS.contains(extension)) return DOCUMENT;
        if (AUDIO_FILES.contains(extension)) return AUDIO;
        String type = mime == null ? "" : mime.toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
        if (type.startsWith("image/")) return IMAGE;
        if (type.startsWith("video/")) return VIDEO;
        if (type.startsWith("audio/")) return AUDIO;
        if (type.startsWith("text/") || type.equals("application/pdf") || type.equals("application/xml")
                || type.endsWith("+xml") || type.equals("application/json") || type.endsWith("+json")
                || type.equals("application/rtf") || type.equals("application/msword")
                || type.startsWith("application/vnd.openxmlformats-officedocument.")
                || type.startsWith("application/vnd.oasis.opendocument.")
                || type.equals("application/vnd.ms-excel") || type.equals("application/vnd.ms-powerpoint")
                || type.equals("application/epub+zip")) return DOCUMENT;
        return OTHER;
    }

    public boolean isMedia() { return this == IMAGE || this == VIDEO; }
}
