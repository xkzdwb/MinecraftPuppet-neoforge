package com.dsh.puppet.network;

import com.dsh.puppet.Protocol;
import com.google.gson.JsonObject;

/** 编解码工具：把 (type, body) 拍成一行 JSON。 */
public final class PuppetMessages {
    private PuppetMessages() {
    }

    public static String encode(String type, JsonObject body) {
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        if (body != null) {
            for (var e : body.entrySet()) {
                o.add(e.getKey(), e.getValue());
            }
        }
        return Protocol.GSON.toJson(o);
    }

    public static String encodeType(String type) {
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        return Protocol.GSON.toJson(o);
    }
}
