package mcplink.mcp;

import mcplink.McpLink;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.Executors;

/**
 * A minimal MCP server over the Streamable HTTP transport: JSON-RPC requests are POSTed to /mcp
 * and answered with a single application/json body. No SSE stream and no sessions, which the
 * spec allows for a server that only offers tools.
 */
public final class McpServer {
    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private static final String FALLBACK_PROTOCOL = "2025-06-18";

    private final ToolRegistry tools;
    private final String token;
    private HttpServer http;

    public McpServer(ToolRegistry tools, String token) {
        this.tools = tools;
        this.token = token;
    }

    public void start(String host, int port) throws IOException {
        http = HttpServer.create(new InetSocketAddress(InetAddress.getByName(host), port), 0);
        http.createContext("/mcp", this::handle);
        http.setExecutor(Executors.newFixedThreadPool(3, r -> {
            Thread t = new Thread(r, "mcp-link-http");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        }));
        http.start();
    }

    public void stop() {
        if (http != null) http.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            if (!originOk(ex)) {
                send(ex, 403, "text/plain", "Forbidden origin");
                return;
            }
            cors(ex);
            if ("OPTIONS".equals(ex.getRequestMethod())) {
                ex.sendResponseHeaders(204, -1);
                ex.close();
                return;
            }
            if (!authorized(ex)) {
                send(ex, 401, "text/plain", "Missing or wrong bearer token");
                return;
            }
            if (!"POST".equals(ex.getRequestMethod())) {
                ex.getResponseHeaders().add("Allow", "POST");
                send(ex, 405, "text/plain", "Only POST is supported");
                return;
            }
            JsonElement body;
            try (InputStream in = ex.getRequestBody()) {
                body = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (Exception e) {
                send(ex, 400, "application/json", GSON.toJson(error(null, -32700, "Parse error")));
                return;
            }
            JsonElement reply;
            if (body.isJsonArray()) {
                JsonArray out = new JsonArray();
                for (JsonElement msg : body.getAsJsonArray()) {
                    JsonObject r = dispatch(msg.getAsJsonObject());
                    if (r != null) out.add(r);
                }
                reply = out.isEmpty() ? null : out;
            } else {
                reply = dispatch(body.getAsJsonObject());
            }
            if (reply == null) {
                ex.sendResponseHeaders(202, -1);
                ex.close();
            } else {
                send(ex, 200, "application/json", GSON.toJson(reply));
            }
        } catch (Throwable t) {
            McpLink.LOG.warn("MCP request failed", t);
            try {
                send(ex, 500, "text/plain", String.valueOf(t));
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * Browsers send Origin; anything not from this machine is refused (DNS rebinding guard). "null" is a page opened from a
     * local file (such as the structure maps), which still has to present the bearer token.
     */
    private static boolean originOk(HttpExchange ex) {
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin == null || origin.equals("null")) return true;
        try {
            String host = URI.create(origin).getHost();
            return "127.0.0.1".equals(host) || "localhost".equals(host) || "[::1]".equals(host);
        } catch (Exception e) {
            return false;
        }
    }

    /** Lets local pages call the server from a browser (preflight and response headers). */
    private static void cors(HttpExchange ex) {
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin == null) return;
        var h = ex.getResponseHeaders();
        h.set("Access-Control-Allow-Origin", origin);
        h.set("Vary", "Origin");
        h.set("Access-Control-Allow-Methods", "POST, OPTIONS");
        h.set("Access-Control-Allow-Headers", "authorization, content-type, accept, mcp-protocol-version");
        h.set("Access-Control-Max-Age", "600");
        if (ex.getRequestHeaders().getFirst("Access-Control-Request-Private-Network") != null) {
            h.set("Access-Control-Allow-Private-Network", "true");
        }
    }

    private boolean authorized(HttpExchange ex) {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) return false;
        return MessageDigest.isEqual(auth.substring(7).trim().getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }

    private JsonObject dispatch(JsonObject msg) {
        JsonElement id = msg.get("id");
        String method = msg.has("method") ? msg.get("method").getAsString() : null;
        if (method == null) return null;   // a response or junk; nothing to answer
        if (id == null || id.isJsonNull()) return null;   // notifications (initialized, cancelled...)
        JsonObject params = msg.has("params") && msg.get("params").isJsonObject() ? msg.getAsJsonObject("params") : new JsonObject();
        try {
            return switch (method) {
                case "initialize" -> result(id, initialize(params));
                case "ping" -> result(id, new JsonObject());
                case "tools/list" -> result(id, tools.list());
                case "tools/call" -> result(id, tools.call(params));
                default -> error(id, -32601, "Method not found: " + method);
            };
        } catch (ToolRegistry.UnknownTool e) {
            return error(id, -32602, e.getMessage());
        } catch (Throwable t) {
            McpLink.LOG.warn("MCP method {} failed", method, t);
            return error(id, -32603, String.valueOf(t));
        }
    }

    private static JsonObject initialize(JsonObject params) {
        JsonObject r = new JsonObject();
        String asked = params.has("protocolVersion") ? params.get("protocolVersion").getAsString() : FALLBACK_PROTOCOL;
        r.addProperty("protocolVersion", asked);
        JsonObject caps = new JsonObject();
        JsonObject toolCaps = new JsonObject();
        toolCaps.addProperty("listChanged", false);
        caps.add("tools", toolCaps);
        r.add("capabilities", caps);
        JsonObject info = new JsonObject();
        info.addProperty("name", "mcp-link");
        info.addProperty("version", McpLink.VERSION);
        r.add("serverInfo", info);
        r.addProperty("instructions", Instructions.TEXT);
        return r;
    }

    private static JsonObject result(JsonElement id, JsonElement result) {
        JsonObject o = new JsonObject();
        o.addProperty("jsonrpc", "2.0");
        o.add("id", id);
        o.add("result", result);
        return o;
    }

    private static JsonObject error(JsonElement id, int code, String message) {
        JsonObject o = new JsonObject();
        o.addProperty("jsonrpc", "2.0");
        o.add("id", id);
        JsonObject e = new JsonObject();
        e.addProperty("code", code);
        e.addProperty("message", message);
        o.add("error", e);
        return o;
    }

    private static void send(HttpExchange ex, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}
