package ioniq6.cpclimate;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded structural diagnostics from values already decoded for handling. Never walks payload values. */
final class OpackSummary {
    static final int MAX_FIELDS = 8;
    private static final Set<String> FIELDS = new HashSet<String>(Arrays.asList(
            "pluginID", "pluginData", "command", "transactionID", "values", "errors", "iids"));
    private static final Set<String> COMMANDS = new HashSet<String>(Arrays.asList(
            "configRequest", "configResponse", "registerRequest", "registerResponse", "readRequest", "readResponse",
            "writeRequest", "writeResponse", "controlRequest", "controlResponse", "updateNotify", "follow", "read"));

    static String shape(Object value) {
        if (value instanceof byte[]) return "data(" + ((byte[]) value).length + " B)";
        if (value instanceof String) return "string(" + ((String) value).length() + " chars)";
        if (value instanceof Map) return "map(" + ((Map<?, ?>) value).size() + ")";
        if (value instanceof List) return "list(" + ((List<?>) value).size() + ")";
        if (value instanceof OpackCodec.Uuid) return "uuid";
        if (value instanceof Number) return "number";
        if (value instanceof Boolean) return "boolean";
        return value == null ? "null" : "value";
    }

    static String fields(Object value, Map<Long, String> names) {
        StringBuilder text = new StringBuilder(shape(value));
        if (!(value instanceof Map)) return text.toString();
        text.append(" fields=[");
        int count = 0;
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            if (count == MAX_FIELDS) { text.append(",..."); break; }
            if (count++ > 0) text.append(',');
            Object key = entry.getKey();
            Long iid = OpackCodec.asLong(key);
            String name = iid != null && names != null ? names.get(iid) : null;
            // Only trusted schema names, known envelope keys, and small numeric IIDs can enter the log.
            if (name != null) text.append(name);
            else if (iid != null && iid >= 0 && iid <= 65535) text.append("iid:").append(iid);
            else text.append(key instanceof String && FIELDS.contains(key) ? key : "field");
            text.append(':').append(shape(entry.getValue()));
        }
        return text.append(']').toString();
    }

    static String message(Object outer, Object request, Map<Long, String> names) {
        Object command = VehicleState.get(request, "command");
        return "outer=" + fields(outer, null) + " command=" + (command instanceof String && COMMANDS.contains(command) ? command : "unknown")
                + " request=" + fields(request, null) + " values=" + fields(VehicleState.get(request, "values"), names);
    }
}
