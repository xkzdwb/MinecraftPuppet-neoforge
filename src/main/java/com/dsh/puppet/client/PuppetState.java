package com.dsh.puppet.client;

import com.dsh.puppet.Protocol;
import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** 客户端缓存的“最近一份观测”，面板直接读这里。 */
public final class PuppetState {
    public static volatile JsonObject lastSnapshot = new JsonObject();
    public static volatile boolean panelVisible = false;
    public static volatile long lastUpdate;
    public static final Deque<String> events = new ArrayDeque<>();
    public static final List<String> history = new ArrayList<>();

    private PuppetState() {
    }

    public static String playerName() {
        return lastSnapshot.has("player") ? lastSnapshot.get("player").getAsString() : "?";
    }

    public static String taskName() {
        return lastSnapshot.has("task") ? lastSnapshot.get("task").getAsString() : "idle";
    }

    public static String posText() {
        if (!lastSnapshot.has("pos")) {
            return "-";
        }
        var a = lastSnapshot.getAsJsonArray("pos");
        return String.format("%.1f, %.1f, %.1f", a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble());
    }

    public static String healthText() {
        if (!lastSnapshot.has("health")) {
            return "-";
        }
        return String.format("%.0f/%.0f  饱食 %d",
                lastSnapshot.get("health").getAsDouble(),
                lastSnapshot.get("max_health").getAsDouble(),
                lastSnapshot.has("food") ? lastSnapshot.get("food").getAsInt() : 0);
    }

    public static void pushEvent(String line) {
        synchronized (events) {
            events.addLast(line);
            while (events.size() > 12) {
                events.removeFirst();
            }
        }
    }

    public static void pushHistory(String cmd) {
        synchronized (history) {
            history.add(cmd);
            while (history.size() > 50) {
                history.remove(0);
            }
        }
    }

    public static void apply(String line) {
        JsonObject o = Protocol.tryParse(line);
        if (o == null || !o.has("type")) {
            return;
        }
        String type = o.get("type").getAsString();
        switch (type) {
            case Protocol.P_SNAPSHOT -> {
                lastSnapshot = o;
                lastUpdate = System.currentTimeMillis();
            }
            case Protocol.P_EVENT -> pushEvent("[" + o.get("kind").getAsString() + "] "
                    + (o.has("text") ? o.get("text").getAsString() : ""));
            case Protocol.P_RESULT -> pushEvent((o.has("ok") && o.get("ok").getAsBoolean() ? "✓ " : "✗ ")
                    + (o.has("text") ? o.get("text").getAsString() : ""));
            case Protocol.P_PUSH -> {
                if (o.has("key") && "gui".equals(o.get("key").getAsString())) {
                    panelVisible = true;
                }
            }
            default -> {
            }
        }
    }
}
