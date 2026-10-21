package mcplink.state;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Trades of every villager / wandering trader whose trading screen was opened this session, keyed by entity UUID. */
public final class TradeLog {
    private static final Map<String, JsonObject> BY_TRADER = new LinkedHashMap<>();
    private static volatile int lastInteractId = -1;
    private static volatile long lastInteractTime;

    private TradeLog() {
    }

    public static void interacted(int entityId) {
        lastInteractId = entityId;
        lastInteractTime = System.currentTimeMillis();
    }

    public static int lastInteractId() {
        return System.currentTimeMillis() - lastInteractTime < 10_000 ? lastInteractId : -1;
    }

    public static synchronized void put(String key, JsonObject record) {
        BY_TRADER.remove(key);
        BY_TRADER.put(key, record);
    }

    public static synchronized List<JsonObject> all() {
        return new ArrayList<>(BY_TRADER.values());
    }

    public static synchronized void clear() {
        BY_TRADER.clear();
    }
}
