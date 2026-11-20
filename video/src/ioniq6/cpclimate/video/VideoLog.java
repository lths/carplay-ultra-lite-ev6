package ioniq6.cpclimate.video;

import java.util.regex.Pattern;

/** Exception messages can contain signed URLs or HTTP authentication. Keep those out of both logs. */
final class VideoLog {
    private static final Pattern URL = Pattern.compile("(?i)https?://[^\\s<>\"']+");
    private static final Pattern AUTH = Pattern.compile("(?i)(proxy-authorization|authorization)\\s*[:=]\\s*[^\\r\\n]+");
    static String clean(String text) {
        if (text == null) return "";
        boolean truncated = text.length() > 8192;
        if (truncated) text = text.substring(0, 8192);
        text = URL.matcher(text).replaceAll("<url omitted>");
        text = AUTH.matcher(text).replaceAll("$1: <omitted>");
        if (text.length() > 4096) { text = text.substring(0, 4096); truncated = true; }
        return truncated ? text + "…" : text;
    }
}
