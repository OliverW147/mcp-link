package mcplink.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** Typed access to a tool call's arguments. */
public final class Args {
    public static final class BadArgument extends RuntimeException {
        BadArgument(String message) {
            super(message);
        }
    }

    private final JsonObject o;

    Args(JsonObject o) {
        this.o = o;
    }

    public boolean has(String k) {
        return o.has(k) && !o.get(k).isJsonNull();
    }

    public int i(String k) {
        if (!has(k)) throw new BadArgument("Missing argument: " + k);
        double d;
        try {
            d = o.get(k).getAsDouble();
        } catch (Exception e) {
            throw new BadArgument("Argument " + k + " must be a number");
        }
        if (!Double.isFinite(d) || d > Integer.MAX_VALUE || d < Integer.MIN_VALUE) throw new BadArgument("Argument " + k + " is out of range");
        return (int) Math.round(d);
    }

    public int i(String k, int def) {
        return has(k) ? i(k) : def;
    }

    public double d(String k) {
        if (!has(k)) throw new BadArgument("Missing argument: " + k);
        double d;
        try {
            d = o.get(k).getAsDouble();
        } catch (Exception e) {
            throw new BadArgument("Argument " + k + " must be a number");
        }
        if (!Double.isFinite(d)) throw new BadArgument("Argument " + k + " must be a finite number");
        return d;
    }

    public double d(String k, double def) {
        return has(k) ? d(k) : def;
    }

    public String s(String k) {
        if (!has(k)) throw new BadArgument("Missing argument: " + k);
        return o.get(k).getAsString();
    }

    public String s(String k, String def) {
        return has(k) ? o.get(k).getAsString() : def;
    }

    public boolean b(String k, boolean def) {
        return has(k) ? o.get(k).getAsBoolean() : def;
    }

    public List<String> strs(String k) {
        List<String> out = new ArrayList<>();
        if (!has(k)) return out;
        JsonElement e = o.get(k);
        if (e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) out.add(x.getAsString());
        } else {
            // commas inside [...] belong to a block state, not between filters
            out.addAll(mcplink.tools.Scan.splitSpecs(e.getAsString()));
        }
        return out;
    }

    public List<Integer> ints(String k) {
        List<Integer> out = new ArrayList<>();
        if (!has(k)) return out;
        JsonElement e = o.get(k);
        if (e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) out.add(x.getAsInt());
        } else {
            for (String part : e.getAsString().split(",")) {
                if (part.isBlank()) continue;
                try {
                    out.add(Integer.parseInt(part.trim()));
                } catch (NumberFormatException ex) {
                    throw new BadArgument("Argument " + k + " must be a list of whole numbers");
                }
            }
        }
        return out;
    }

    public static BadArgument bad(String message) {
        return new BadArgument(message);
    }
}
