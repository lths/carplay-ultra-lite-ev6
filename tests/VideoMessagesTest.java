package ioniq6.cpclimate;

import java.util.*;

/** Native command datagrams and private video URL fields must survive transport without reaching logs. */
public final class VideoMessagesTest {
    static int fails;
    static void check(String name, boolean ok) { if (!ok) fails++; System.out.println((ok ? "ok   " : "FAIL ") + name); }
    static Map<String, Object> map(Object... kv) { Map<String, Object> m = new LinkedHashMap<String, Object>(); for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]); return m; }
    public static void main(String[] args) {
        Map<String, Object> request = map("type", "insertPlayQueueItem", "kind", "request", "messageID", 23L,
                "item", map("uuid", "test-item", "url", "https://test.invalid/private-title/movie.m3u8?token=private-token"));
        long sid = 0x1020304050607080L;
        byte[] packet = VideoMessages.datagram(sid, request);
        check("outbound video commands have the native CPV2 header", packet[0] == 'C' && packet[3] == '2');
        check("64-bit stream ID survives the command bridge", VideoMessages.sid(packet) == sid);
        packet[3] = '1';
        Map<?, ?> read = VideoMessages.message(packet, packet.length);
        check("nested binary plists retain the video item", request.equals(read));
        String log = VideoMessages.summary(read);
        check("video diagnostics omit URLs, titles, UUIDs and tokens", !log.contains("private-") && !log.contains("test-item") && !log.contains("test.invalid") && log.contains("insertPlayQueueItem"));
        check("unknown message names cannot inject text into diagnostics", !VideoMessages.summary(map("type", "private-token")).contains("private-token"));
        check("truncated native datagrams are rejected", VideoMessages.message(new byte[11], 11) == null);
        Map<String, Object> answer = VideoMessages.reply(map("kind", "request", "type", "playbackInfo", "messageID", 9L), "test-item");
        check("requests get a correlated playbackInfo response", Long.valueOf(9).equals(answer.get("messageID")) && "response".equals(answer.get("kind")));
        check("notifications do not generate response loops", VideoMessages.reply(map("kind", "event", "type", "setRate"), null) == null);
        System.exit(fails == 0 ? 0 : 1);
    }
}
