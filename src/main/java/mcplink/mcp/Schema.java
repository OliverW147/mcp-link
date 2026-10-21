package mcplink.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Small builder for a tool's JSON Schema input object. */
public final class Schema {
    private final JsonObject props = new JsonObject();
    private final JsonArray required = new JsonArray();

    public static Schema of() {
        return new Schema();
    }

    private Schema prop(String name, String type, String desc, boolean req) {
        JsonObject p = new JsonObject();
        p.addProperty("type", type);
        p.addProperty("description", desc);
        props.add(name, p);
        if (req) required.add(name);
        return this;
    }

    public Schema num(String name, String desc) {
        return prop(name, "number", desc, false);
    }

    public Schema numReq(String name, String desc) {
        return prop(name, "number", desc, true);
    }

    public Schema integer(String name, String desc) {
        return prop(name, "integer", desc, false);
    }

    public Schema intReq(String name, String desc) {
        return prop(name, "integer", desc, true);
    }

    public Schema str(String name, String desc) {
        return prop(name, "string", desc, false);
    }

    public Schema strReq(String name, String desc) {
        return prop(name, "string", desc, true);
    }

    public Schema bool(String name, String desc) {
        return prop(name, "boolean", desc, false);
    }

    public Schema strList(String name, String desc) {
        JsonObject p = new JsonObject();
        p.addProperty("type", "array");
        JsonObject items = new JsonObject();
        items.addProperty("type", "string");
        p.add("items", items);
        p.addProperty("description", desc);
        props.add(name, p);
        return this;
    }

    public Schema intList(String name, String desc) {
        JsonObject p = new JsonObject();
        p.addProperty("type", "array");
        JsonObject items = new JsonObject();
        items.addProperty("type", "integer");
        p.add("items", items);
        p.addProperty("description", desc);
        props.add(name, p);
        return this;
    }

    /** Common box/area arguments: either x1..z2 corners, or a radius around a centre (default: the player). */
    public Schema area(int defaultRadius) {
        integer("x1", "Box corner 1 X (use with y1 z1 x2 y2 z2)");
        integer("y1", "Box corner 1 Y (also limits the height in radius mode)");
        integer("z1", "Box corner 1 Z");
        integer("x2", "Box corner 2 X");
        integer("y2", "Box corner 2 Y (also limits the height in radius mode)");
        integer("z2", "Box corner 2 Z");
        integer("radius", "Instead of a box: horizontal radius around cx/cz (default " + defaultRadius + ")");
        integer("cx", "Centre X for radius (default player)");
        integer("cy", "Centre Y for radius (default player)");
        integer("cz", "Centre Z for radius (default player)");
        integer("dy", "Vertical half-height for radius mode (default: radius, clamped to world height)");
        return this;
    }

    public JsonObject build() {
        JsonObject s = new JsonObject();
        s.addProperty("type", "object");
        s.add("properties", props);
        if (!required.isEmpty()) s.add("required", required);
        return s;
    }
}
