package ioniq6.cpclimate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The car's page in CarPlay's Vehicle app: the automaker settings accessory (1280), which LIVI fills with its
 * two version lines, rebuilt as the car's own page. Live readings as static lines (battery, range, odometer),
 * a switch for the ambient light effect, and buttons that open the head unit's own screens and apps. The
 * services and characteristics are Apple's CarAccessoryFramework types (iOS 27.0 24A437, _CAFServiceType* and
 * _CAFCharacteristicType*): StaticSetting 0x16000015, BooleanSetting 0x16000001, ButtonSetting 0x16000002 and
 * SettingsSection 0x16000020. A button's buttonAction is 0 Ready / 1 PerformAction (NSStringFromButtonAction):
 * the phone writes 1 and the car puts it back to 0.
 *
 * Tiles: on the car (2026-11-08) the Vehicle app drew nothing for LIVI's two plain lines. Apple's settings UI has a
 * tile data source, and a setting's ProminenceInfo (0x36000020) carries a prominenceLevel bit mask whose bit 0x1 is
 * "appHomeTile"; a StaticSetting's ChildrenSettingsIdentifiers (0x36000012) names the settings shown behind it. So
 * each section also gets a tile: a StaticSetting with that prominence and the section's rows as children. How
 * ProminenceInfo travels isn't known (a dictionary, or an array of them), so `tiles` picks: "dict", "array", "off".
 *
 * Nothing here touches Android or the car: CarVehicle reads the car and does what is pressed.
 */
final class VehiclePage {
    static final long ACCESSORY = 1280;
    /** the page's own iids, clear of LIVI's (which end below 4400); 16 per service */
    static final long BASE = 20480;
    static final int STEP = 16;
    static final long STATIC_SETTING = 0x16000015L, BOOLEAN_SETTING = 0x16000001L, BUTTON_SETTING = 0x16000002L;
    static final long SECTION = 0x16000020L;
    static final long T_IDENTIFIER = 0x30000019L, T_LABEL = 0x30000001L, T_VALUE = 0x30000017L, T_CATEGORY = 0x36000006L;
    static final long T_SYMBOL = 0x3000005EL, T_SORT = 0x30000003L, T_SECTION = 0x36000028L, T_SECTION_NAME = 0x36000004L;
    static final long T_ON = 0x30000002L, T_BUTTON_ACTION = 0x36000010L;
    static final long T_PROMINENCE = 0x36000020L, T_CHILDREN = 0x36000012L;
    static final long F_BOOL = 0, F_UINT8 = 1, F_STRING = 10, F_DICT = 12, F_ARRAY = 13;
    /** ProminenceInfo's prominenceLevel bit for a tile on the Vehicle app's first screen */
    static final long APP_HOME_TILE = 1;
    /** how the tiles' ProminenceInfo is sent (setting vehicle_tiles): "dict", "array", or "off" for no tiles */
    static volatile String tiles = "off";
    static final long READY = 0, PERFORM = 1;
    static final long MAX_LENGTH = 65535, HIGH_PRIORITY = 1;
    /** shown for a reading the car didn't give */
    static final String NONE = "—";

    static final int LINE = 0, SWITCH = 1, BUTTON = 2;

    /** what the phone did on the page; called with VehicleState's lock held, so it must not block */
    interface Listener {
        void pressed(String id);

        void switched(String id, boolean on);
    }

    static final class Item {
        final int kind;
        final String id, label, symbol, section;
        /** the service's iid; the value (line), on (switch) or buttonAction (button) is iid + 3 */
        long iid;

        Item(int kind, String id, String label, String symbol, String section) {
            this.kind = kind;
            this.id = id;
            this.label = label;
            this.symbol = symbol;
            this.section = section;
        }

        long valueIid() {
            return iid + 3;
        }
    }

    private final List<String> sections = new ArrayList<String>();
    /** a section's shown name, when it isn't its id (the car's own section shows the car's name) */
    private final Map<String, String> sectionNames = new LinkedHashMap<String, String>();
    private final Map<String, String> tileSymbols = new LinkedHashMap<String, String>();
    private final List<Item> items = new ArrayList<Item>();
    /** the latest value of every line and switch, by item id: a new session starts from them */
    private final Map<String, Object> current = new LinkedHashMap<String, Object>();
    volatile Listener listener;

    /** shows section id under another name; its items keep pointing at the id */
    VehiclePage sectionName(String id, String name) {
        if (name != null && name.trim().length() > 0) sectionNames.put(id, name.trim());
        return this;
    }

    /** the symbol of a section's tile */
    VehiclePage tile(String section, String symbol) {
        tileSymbols.put(section, symbol);
        return this;
    }

    VehiclePage line(String section, String id, String label, String symbol) {
        return add(new Item(LINE, id, label, symbol, section), NONE);
    }

    VehiclePage toggle(String section, String id, String label, String symbol, boolean on) {
        return add(new Item(SWITCH, id, label, symbol, section), on);
    }

    VehiclePage button(String section, String id, String label, String symbol) {
        return add(new Item(BUTTON, id, label, symbol, section), READY);
    }

    private VehiclePage add(Item item, Object value) {
        if (!sections.contains(item.section)) sections.add(item.section);
        items.add(item);
        current.put(item.id, value);
        // sections first, then a tile's place for each section, then the items, in the order they were added
        long iid = BASE + 2L * STEP * sections.size();
        for (Item it : items) {
            it.iid = iid;
            iid += STEP;
        }
        return this;
    }

    Item item(String id) {
        for (Item it : items) if (it.id.equals(id)) return it;
        return null;
    }

    List<Item> items() {
        return items;
    }

    private Item byValueIid(long iid) {
        for (Item it : items) if (it.valueIid() == iid) return it;
        return null;
    }

    /** true for a value the phone may write: a switch's on, a button's action */
    boolean owns(long iid) {
        Item it = byValueIid(iid);
        return it != null && it.kind != LINE;
    }

    /**
     * Puts the page into the accessory list in place of LIVI's automaker settings services, and its values,
     * names and write permissions into the session's tables; null if the list has no such accessory.
     */
    @SuppressWarnings("unchecked")
    synchronized byte[] install(byte[] accessories, Map<Long, Object> values, Map<Long, String> names,
            Set<Long> writable, Set<Long> shared) {
        Object list = OpackCodec.decode(accessories);
        if (!(list instanceof List)) return null;
        for (Object a : (List<Object>) list) {
            if (!(a instanceof Map) || !Long.valueOf(ACCESSORY).equals(OpackCodec.asLong(((Map<Object, Object>) a).get("iid")))) continue;
            List<Object> services = new ArrayList<Object>();
            for (int i = 0; i < sections.size(); i++) services.add(section(BASE + (long) STEP * i, sections.get(i), i, values));
            String mode = tiles;
            if (!"off".equals(mode)) {
                for (int i = 0; i < sections.size(); i++)
                    services.add(tile(BASE + (long) STEP * (sections.size() + i), sections.get(i), i, "array".equals(mode), values));
            }
            for (int i = 0; i < items.size(); i++) {
                Item it = items.get(i);
                services.add(setting(it, i, values, names));
                if (it.kind != LINE) {
                    writable.add(it.valueIid());
                    shared.add(it.valueIid());
                }
            }
            ((Map<Object, Object>) a).put("services", services);
            return OpackCodec.encode(list);
        }
        return null;
    }

    private Map<Object, Object> section(long iid, String id, int order, Map<Long, Object> values) {
        List<Object> c = new ArrayList<Object>();
        c.add(fixed(iid + 1, T_IDENTIFIER, id, values));
        c.add(number(iid + 2, T_SORT, order, 100, values));
        c.add(fixed(iid + 3, T_SECTION_NAME, sectionNames.containsKey(id) ? sectionNames.get(id) : id, values));
        return VehicleState.dict("iid", iid, "type", SECTION, "characteristics", c);
    }

    /** a section's tile: a static setting shown on the Vehicle app's first screen, its children the section's rows */
    private Map<Object, Object> tile(long iid, String section, int order, boolean asArray, Map<Long, Object> values) {
        String name = sectionNames.containsKey(section) ? sectionNames.get(section) : section;
        String symbol = tileSymbols.containsKey(section) ? tileSymbols.get(section) : "car";
        List<Object> c = new ArrayList<Object>();
        c.add(fixed(iid + 1, T_IDENTIFIER, "tile." + section, values));
        c.add(fixed(iid + 2, T_LABEL, name, values));
        c.add(fixed(iid + 3, T_VALUE, "", values));
        c.add(number(iid + 4, T_CATEGORY, 0, 15, values));
        c.add(fixed(iid + 5, T_SYMBOL, symbol, values));
        c.add(number(iid + 6, T_SORT, order, 100, values));
        Map<Object, Object> prominence = VehicleState.dict("color", 0L, "prominenceLevel", APP_HOME_TILE, "sortOrder", (long) order,
                "userVisibleLabel", name, "userVisibleValue", "");
        Object sent = prominence;
        if (asArray) {
            List<Object> one = new ArrayList<Object>();
            one.add(prominence);
            sent = one;
        }
        c.add(composite(iid + 8, T_PROMINENCE, asArray ? F_ARRAY : F_DICT, sent, values));
        List<Object> children = new ArrayList<Object>();
        for (Item it : items) if (it.section.equals(section)) children.add(it.id);
        c.add(composite(iid + 9, T_CHILDREN, F_ARRAY, children, values));
        return VehicleState.dict("iid", iid, "type", STATIC_SETTING, "characteristics", c);
    }

    private static Map<Object, Object> composite(long iid, long type, long format, Object value, Map<Long, Object> values) {
        Map<Object, Object> c = characteristic(iid, type, format, false, false);
        c.put("maximumLength", MAX_LENGTH);
        c.put("initialValue", value);
        values.put(iid, value);
        return c;
    }

    private Map<Object, Object> setting(Item it, int order, Map<Long, Object> values, Map<Long, String> names) {
        long iid = it.iid;
        List<Object> c = new ArrayList<Object>();
        c.add(fixed(iid + 1, T_IDENTIFIER, it.id, values));
        c.add(fixed(iid + 2, T_LABEL, it.label, values));
        Object now = current.get(it.id);
        Map<Object, Object> value;
        if (it.kind == LINE) {
            value = characteristic(iid + 3, T_VALUE, F_STRING, true, false);
            value.put("maximumLength", MAX_LENGTH);
        } else if (it.kind == SWITCH) {
            value = characteristic(iid + 3, T_ON, F_BOOL, true, true);
        } else {
            value = characteristic(iid + 3, T_BUTTON_ACTION, F_UINT8, true, true);
            range(value, PERFORM);
            now = READY;
        }
        value.put("initialValue", now);
        values.put(iid + 3, now);
        names.put(iid + 3, it.id);
        c.add(value);
        c.add(number(iid + 4, T_CATEGORY, 0, 15, values));
        c.add(fixed(iid + 5, T_SYMBOL, it.symbol, values));
        c.add(number(iid + 6, T_SORT, Math.min(order, 100), 100, values));
        c.add(fixed(iid + 7, T_SECTION, it.section, values));
        long type = it.kind == LINE ? STATIC_SETTING : it.kind == SWITCH ? BOOLEAN_SETTING : BUTTON_SETTING;
        return VehicleState.dict("iid", iid, "type", type, "characteristics", c);
    }

    /** a characteristic's description, keys in the order LIVI's describe() writes them */
    private static Map<Object, Object> characteristic(long iid, long type, long format, boolean mutable, boolean phoneWrites) {
        return VehicleState.dict("iid", iid, "type", type, "format", format, "mutable", mutable, "writable", phoneWrites,
                "oemWritable", true, "priority", HIGH_PRIORITY);
    }

    private static void range(Map<Object, Object> c, long max) {
        c.put("minimumValue", 0L);
        c.put("maximumValue", max);
        c.put("stepValue", 1L);
    }

    private static Map<Object, Object> fixed(long iid, long type, String text, Map<Long, Object> values) {
        Map<Object, Object> c = characteristic(iid, type, F_STRING, false, false);
        c.put("maximumLength", MAX_LENGTH);
        c.put("initialValue", text);
        values.put(iid, text);
        return c;
    }

    private static Map<Object, Object> number(long iid, long type, long n, long max, Map<Long, Object> values) {
        Map<Object, Object> c = characteristic(iid, type, F_UINT8, false, false);
        range(c, max);
        c.put("initialValue", n);
        values.put(iid, n);
        return c;
    }

    /**
     * New values from the car, by item id (a line's text, a switch's state); returns them by iid for
     * VehicleState.fromCar, only the ones that changed.
     */
    synchronized Map<Long, Object> update(Map<String, Object> fresh) {
        Map<Long, Object> changed = new LinkedHashMap<Long, Object>();
        for (Map.Entry<String, Object> e : fresh.entrySet()) {
            Item it = item(e.getKey());
            if (it == null || it.kind == BUTTON || e.getValue() == null) continue;
            if (e.getValue().equals(current.get(it.id))) continue;
            current.put(it.id, e.getValue());
            changed.put(it.valueIid(), e.getValue());
        }
        return changed;
    }

    /**
     * What the phone wrote on the page: tells the listener, and returns what the car sets in answer (a pressed
     * button goes back to Ready).
     */
    synchronized Map<Long, Object> phoneWrote(Map<Long, Object> written) {
        Map<Long, Object> back = new LinkedHashMap<Long, Object>();
        Listener l = listener;
        for (Map.Entry<Long, Object> e : written.entrySet()) {
            Item it = byValueIid(e.getKey());
            if (it == null) continue;
            if (it.kind == SWITCH && e.getValue() instanceof Boolean) {
                current.put(it.id, e.getValue());
                if (l != null) l.switched(it.id, (Boolean) e.getValue());
            } else if (it.kind == BUTTON) {
                Long action = OpackCodec.asLong(e.getValue());
                if (action != null && action == PERFORM && l != null) l.pressed(it.id);
                back.put(it.valueIid(), READY);
            }
        }
        return back;
    }

    // ---- how the car's readings are shown ----

    static final double KM_PER_MILE = 1.609344;

    /**
     * A battery level in percent; NONE outside 0-100 (the car answers 0 or garbage while its CAN side is asleep).
     * Rounded down, as the cluster does (the car said 90.5 while the cluster showed 90; 2026-11-10).
     */
    static String percent(float soc) {
        if (Float.isNaN(soc) || soc <= 0f || soc > 100.5f) return NONE;
        return (int) Math.floor(Math.min(soc, 100f)) + " %";
    }

    /**
     * A distance the car gives in km, in the unit the car is set to; NONE for a negative or absurd one. Rounded
     * down, as the cluster does (17315.8 km is 10,759.6 mi: the cluster showed 10,759).
     */
    static String distance(double km, boolean miles) {
        if (Double.isNaN(km) || km < 0 || km > 2000000) return NONE;
        long n = (long) Math.floor(miles ? km / KM_PER_MILE : km);
        return String.format(Locale.US, "%,d %s", n, miles ? "mi" : "km");
    }
}
