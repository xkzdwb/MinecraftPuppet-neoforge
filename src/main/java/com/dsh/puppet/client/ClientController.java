package com.dsh.puppet.client;

import com.dsh.puppet.Protocol;

/** 客户端发消息的唯一出口。 */
public final class ClientController {
    private ClientController() {
    }

    public static void onPacket(String line) {
        PuppetState.apply(line);
    }

    public static void command(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        PuppetState.pushHistory(text);
        send(Protocol.command("@s", text));
    }

    public static void setWanted(boolean on) {
        var o = new com.google.gson.JsonObject();
        o.addProperty("type", Protocol.P_WANTED);
        o.addProperty("mode", on ? "on" : "off");
        send(o);
    }

    public static void hello() {
        send(Protocol.tryParse("{\"type\":\"" + Protocol.P_HELLO + "\"}"));
    }

    private static void send(com.google.gson.JsonObject o) {
        if (o == null) {
            return;
        }
        try {
            ClientNetwork.send(Protocol.GSON.toJson(o));
        } catch (Throwable ignored) {
        }
    }
}
