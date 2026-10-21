package mcplink.tools;

import mcplink.J;
import mcplink.mcp.Args;
import mcplink.mcp.ToolRegistry.ToolError;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shows a long text (source code, a JSON file) the same way everywhere: a line range, regex hits with context, or all of it. */
final class TextView {
    private TextView() {
    }

    /** Splits on \n or \r\n, so Windows line endings never show up as stray \r. */
    static String[] lines(String text) {
        return text.split("\r?\n", -1);
    }

    private static final Pattern TYPE_DECL = Pattern.compile("\\b(?:class|interface|enum|record)\\s+\\w+");

    /**
     * The member outline of Java source: every line that sits directly in a class body (fields, constructors, methods, nested
     * types, enum constants), found by tracking brace depth, so statements inside methods never appear.
     */
    static String outline(String[] lines) {
        StringBuilder sb = new StringBuilder();
        java.util.ArrayDeque<Integer> bodies = new java.util.ArrayDeque<>();
        int depth = 0;
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i], t = l.strip();
            boolean member = bodies.isEmpty() ? depth == 0 : depth == bodies.peek();
            if (member && !t.isEmpty() && !t.startsWith("@") && !t.startsWith("//") && !t.startsWith("/*") && !t.startsWith("*")
                    && !t.startsWith("}") && !t.startsWith("import ") && !t.startsWith("package ")) {
                sb.append(String.format("%5d  %s", i + 1, t)).append('\n');
            }
            int delta = braceDelta(l);
            if (member && TYPE_DECL.matcher(t).find() && l.indexOf('{') >= 0) bodies.push(depth + 1);
            depth += delta;
            while (!bodies.isEmpty() && depth < bodies.peek()) bodies.pop();
        }
        return sb.toString();
    }

    /** Opening minus closing braces on a line, ignoring ones in strings, chars and // comments. */
    private static int braceDelta(String l) {
        int d = 0;
        char quote = 0;
        for (int i = 0; i < l.length(); i++) {
            char c = l.charAt(i);
            if (quote != 0) {
                if (c == '\\') i++;
                else if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '/' && i + 1 < l.length() && l.charAt(i + 1) == '/') {
                break;
            } else if (c == '{') {
                d++;
            } else if (c == '}') {
                d--;
            }
        }
        return d;
    }

    static String numbered(String[] lines, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i <= to; i++) sb.append(String.format("%5d  %s", i, lines[i - 1])).append('\n');
        return sb.toString();
    }

    /** Handles the 'lines' and 'find' arguments; returns null when neither was given. */
    static J range(J j, String[] lines, Args a) {
        if (a.has("lines")) {
            Matcher m = Pattern.compile("(\\d+)\\s*-\\s*(\\d+)").matcher(a.s("lines"));
            if (!m.find()) throw new ToolError("lines must look like 120-220");
            int from = Math.max(1, Integer.parseInt(m.group(1))), to = Math.min(lines.length, Integer.parseInt(m.group(2)));
            if (from > to) throw new ToolError("The text has " + lines.length + " lines.");
            if (to - from > 600) to = from + 600;
            return j.put("lines", from + "-" + to).put("text", numbered(lines, from, to));
        }
        if (a.has("find")) {
            Pattern f = Pattern.compile(a.s("find"));
            int ctx = Math.max(0, Math.min(30, a.i("context", 3)));
            boolean[] keep = new boolean[lines.length];
            int hits = 0;
            for (int i = 0; i < lines.length; i++) {
                if (!f.matcher(lines[i]).find()) continue;
                hits++;
                for (int k = Math.max(0, i - ctx); k <= Math.min(lines.length - 1, i + ctx); k++) keep[k] = true;
            }
            StringBuilder sb = new StringBuilder();
            int last = -2;
            for (int i = 0; i < lines.length; i++) {
                if (!keep[i]) continue;
                if (i != last + 1 && sb.length() > 0) sb.append("...\n");
                sb.append(String.format("%5d  %s", i + 1, lines[i])).append('\n');
                last = i;
                if (sb.length() > 120_000) {
                    sb.append("...(cut at 120000 characters)").append('\n');
                    break;
                }
            }
            return j.put("hits", hits).put("text", sb.toString());
        }
        return null;
    }
}
