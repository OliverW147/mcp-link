package mcplink.state;

import net.minecraft.network.chat.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Every chat, system and action-bar line the client has shown since launch (last 1000). */
public final class ChatLog {
    public record Line(long seq, long time, String kind, String sender, String text) {
    }

    private static final int MAX = 1000;
    private static final ArrayDeque<Line> LINES = new ArrayDeque<>();
    private static long seq;
    private static String lastActionBar;

    private ChatLog() {
    }

    public static synchronized void add(String kind, Component msg, String sender) {
        String text = msg.getString();
        if (kind.equals("actionbar")) {
            if (text.equals(lastActionBar)) return;   // action bars are resent every tick or so
            lastActionBar = text;
        }
        LINES.addLast(new Line(++seq, System.currentTimeMillis(), kind, sender, text));
        while (LINES.size() > MAX) LINES.removeFirst();
    }

    public static synchronized List<Line> since(long after, int limit, String contains, boolean includeActionBar) {
        List<Line> out = new ArrayList<>();
        String needle = contains == null ? null : contains.toLowerCase(java.util.Locale.ROOT);
        for (Line l : LINES) {
            if (l.seq() <= after) continue;
            if (!includeActionBar && l.kind().equals("actionbar")) continue;
            if (needle != null && !l.text().toLowerCase(java.util.Locale.ROOT).contains(needle) && (l.sender() == null || !l.sender().toLowerCase(java.util.Locale.ROOT).contains(needle))) continue;
            out.add(l);
        }
        return out.size() > limit ? out.subList(out.size() - limit, out.size()) : out;
    }

    public static synchronized long lastSeq() {
        return seq;
    }
}
