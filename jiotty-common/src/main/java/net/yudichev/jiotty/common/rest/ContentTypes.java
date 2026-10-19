package net.yudichev.jiotty.common.rest;

/// Values of the HTTP headers that describe a body: its type, its charset and its content coding.
public final class ContentTypes {
    public static final String CONTENT_TYPE_JSON = "application/json";
    public static final String CONTENT_TYPE_TEXT = "text/plain";
    public static final String CONTENT_TYPE_CSV = "text/csv";
    public static final String CONTENT_TYPE_HTML = "text/html";
    public static final String CONTENT_TYPE_EVENT_STREAM = "text/event-stream";
    public static final String CHARSET_UTF_8 = "utf-8";
    public static final String CONTENT_CODING_GZIP = "gzip";

    private ContentTypes() {
    }
}
