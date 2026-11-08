package com.dsh.puppet;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 网络协议：一行一条 JSON（UTF-8，以 '\n' 结尾）。
 *
 * <pre>
 * 方向                    C2S type              说明
 * 游戏客户端 -> 服务端      puppet:command       玩家 (GUI/RL 回报/模组自己) 的指令
 * 游戏客户端 -> 服务端      puppet:hello         客户端上线自报家门
 * 游戏客户端 -> 服务端      puppet:arm           打开/关闭“由外部接管”开关
 * 游戏客户端 -> 服务端      puppet:wanted        客户端要求上报的状态
 * 服务端 -> 游戏客户端      puppet:snapshot      周期快照
 * 服务端 -> 游戏客户端      puppet:event         事件
 * 服务端 -> 游戏客户端      puppet:result        指令执行结果
 * 服务端 -> 游戏客户端      puppet:welcome       握手
 * 服务端 -> 游戏客户端      puppet:state/push    命令触发的状态/参数推送
 * </pre>
 */
public final class Protocol {
    public static final String MOD_ID = "puppet";
    public static final String PROTOCOL_VERSION = "1";

    public static final String P_COMMAND = "puppet:command";
    public static final String P_HELLO = "puppet:hello";
    public static final String P_ARM = "puppet:arm";
    public static final String P_WANTED = "puppet:wanted";
    public static final String P_SNAPSHOT = "puppet:snapshot";
    public static final String P_EVENT = "puppet:event";
    public static final String P_RESULT = "puppet:result";
    public static final String P_WELCOME = "puppet:welcome";
    public static final String P_PUSH = "puppet:push";

    public static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private Protocol() {
    }

    public static String line(JsonObject json) {
        return GSON.toJson(json) + "\n";
    }

    public static JsonObject command(String target, String text) {
        JsonObject o = new JsonObject();
        o.addProperty("type", P_COMMAND);
        o.addProperty("target", target == null ? "@s" : target);
        o.addProperty("text", text == null ? "" : text);
        return o;
    }

    public static JsonObject arm(boolean on, String target) {
        JsonObject o = new JsonObject();
        o.addProperty("type", P_ARM);
        o.addProperty("on", on);
        o.addProperty("target", target == null ? "@s" : target);
        return o;
    }

    /** 把一行文本解析成 JsonObject；不是 JSON 时返回 null。 */
    public static JsonObject tryParse(String raw) {
        try {
            var el = JsonParser.parseString(raw);
            return el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    public static String safe(UUID id) {
        return id == null ? "" : id.toString();
    }
}
