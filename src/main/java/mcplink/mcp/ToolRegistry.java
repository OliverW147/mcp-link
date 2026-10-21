package mcplink.mcp;

import mcplink.McpLink;
import mcplink.control.Control;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.LinkedHashMap;
import java.util.Map;

/** The tools offered over MCP. Action tools refuse to run unless control is switched on in game. */
public final class ToolRegistry {
    public static final class UnknownTool extends RuntimeException {
        UnknownTool(String name) {
            super("Unknown tool: " + name);
        }
    }

    /** A tool failure that should reach the agent as a readable message rather than a stack trace. */
    public static final class ToolError extends RuntimeException {
        public ToolError(String message) {
            super(message);
        }
    }

    @FunctionalInterface
    public interface Handler {
        JsonElement call(Args args) throws Exception;
    }

    /** action: needs control switched on in game. readOnly: only reads the game (shown to the client as a hint). */
    public record Tool(String name, String description, JsonObject schema, boolean action, boolean readOnly, Handler handler) {
    }

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public void add(String name, String description, Schema schema, Handler handler) {
        tools.put(name, new Tool(name, description, schema.build(), false, true, handler));
    }

    /** Changes something (scripts, settings) but is not an in-game action, so it works with control off. */
    public void addWrite(String name, String description, Schema schema, Handler handler) {
        tools.put(name, new Tool(name, description, schema.build(), false, false, handler));
    }

    public void addAction(String name, String description, Schema schema, Handler handler) {
        tools.put(name, new Tool(name, description + " Needs control switched on in game.", schema.build(), true, false, handler));
    }

    JsonObject list() {
        JsonArray arr = new JsonArray();
        for (Tool t : tools.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("name", t.name());
            o.addProperty("description", t.description());
            o.add("inputSchema", t.schema());
            JsonObject ann = new JsonObject();
            ann.addProperty("readOnlyHint", t.readOnly());
            ann.addProperty("openWorldHint", false);
            o.add("annotations", ann);
            arr.add(o);
        }
        JsonObject r = new JsonObject();
        r.add("tools", arr);
        return r;
    }

    JsonObject call(JsonObject params) {
        String name = params.get("name").getAsString();
        Tool t = tools.get(name);
        if (t == null) throw new UnknownTool(name);
        JsonObject args = params.has("arguments") && params.get("arguments").isJsonObject() ? params.getAsJsonObject("arguments") : new JsonObject();
        McpLink.noteRequest(t.action());
        try {
            if (t.action() && !Control.enabled()) {
                throw new ToolError("Control is off. Ask the player to press the MCP Link control key (" + McpLink.controlKeyName() + ") to allow actions.");
            }
            JsonElement out = t.handler().call(new Args(args));
            return content(out instanceof JsonPrimitive p && p.isString() ? p.getAsString() : McpServer.GSON.toJson(out), false);
        } catch (ToolError | Args.BadArgument e) {
            return content(e.getMessage(), true);
        } catch (Throwable e) {
            McpLink.LOG.warn("Tool {} failed", name, e);
            return content(name + " failed: " + e, true);
        }
    }

    private static JsonObject content(String text, boolean isError) {
        JsonObject c = new JsonObject();
        c.addProperty("type", "text");
        c.addProperty("text", text);
        JsonArray arr = new JsonArray();
        arr.add(c);
        JsonObject r = new JsonObject();
        r.add("content", arr);
        r.addProperty("isError", isError);
        return r;
    }
}
