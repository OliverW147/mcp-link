package mcplink;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.Collection;

/** Terse JSON building. Doubles are rounded so replies stay short. */
public final class J {
    private final JsonObject o = new JsonObject();

    public static J obj() {
        return new J();
    }

    public J put(String k, Object v) {
        o.add(k, of(v));
        return this;
    }

    /** Only adds the key when the value is not null / not false / not zero-length. */
    public J opt(String k, Object v) {
        if (v == null) return this;
        if (v instanceof Boolean b && !b) return this;
        if (v instanceof String s && s.isEmpty()) return this;
        if (v instanceof Collection<?> c && c.isEmpty()) return this;
        if (v instanceof JsonArray a && a.isEmpty()) return this;
        o.add(k, of(v));
        return this;
    }

    public JsonObject build() {
        return o;
    }

    public static JsonArray arr(Collection<?> items) {
        JsonArray a = new JsonArray();
        for (Object x : items) a.add(of(x));
        return a;
    }

    public static JsonArray arr(Object... items) {
        JsonArray a = new JsonArray();
        for (Object x : items) a.add(of(x));
        return a;
    }

    public static double r(double d) {
        return Math.round(d * 100.0) / 100.0;
    }

    public static JsonElement of(Object v) {
        if (v == null) return JsonNull.INSTANCE;
        if (v instanceof JsonElement e) return e;
        if (v instanceof J j) return j.build();
        if (v instanceof Boolean b) return new JsonPrimitive(b);
        if (v instanceof Double d) {
            if (d.isNaN() || d.isInfinite()) return new JsonPrimitive(String.valueOf(d));
            double rd = r(d);
            return rd == Math.rint(rd) && Math.abs(rd) < 1e15 ? new JsonPrimitive((long) rd) : new JsonPrimitive(rd);
        }
        if (v instanceof Float f) return of((double) f);
        if (v instanceof Number n) return new JsonPrimitive(n);
        if (v instanceof Character c) return new JsonPrimitive(c);
        if (v instanceof Collection<?> c) return arr(c);
        if (v instanceof int[] ia) {
            JsonArray a = new JsonArray();
            for (int x : ia) a.add(x);
            return a;
        }
        return new JsonPrimitive(String.valueOf(v));
    }
}
