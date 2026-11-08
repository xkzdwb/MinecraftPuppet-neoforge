package com.dsh.puppet.entity;

import com.dsh.puppet.Protocol;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 本地 TCP 桥：Python 端（tools/mc_puppet/）连到 127.0.0.1:25576，用一行一条 JSON 和游戏对话。
 *
 * <pre>
 * Python  ->  游戏    {"cmd": "goto", "x": 1, "y": 64, "z": 2, "player": "Name"}
 *                     {"cmd": "say",  "text": "去砍点树"}    // 自然语言，走同一个解析器
 *                     {"cmd": "state"}                        // 要一份观测
 * 游戏    ->  Python   {"type": "snapshot", ...}
 *                     {"type": "event", "kind": "damaged", ...}
 *                     {"type": "result", "ok": true, "text": "..."}
 * </pre>
 *
 * 只监听回环地址；所有改世界的操作都 {@code server.execute()} 回主线程执行，
 * 不跨线程碰游戏世界，保证线程安全。
 */
public final class ExternalBridge {
    private static final Logger LOG = LoggerFactory.getLogger("puppet/bridge");

    private static final int PORT = Integer.getInteger("puppet.bridge.port", 25576);

    private static ExternalBridge instance;

    private final MinecraftServer server;
    private final List<Client> clients = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ServerSocket socket;
    private Thread acceptThread;

    private ExternalBridge(MinecraftServer server) {
        this.server = server;
    }

    public static synchronized ExternalBridge get(MinecraftServer server) {
        if (server == null) {
            return new ExternalBridge(null);
        }
        if (instance == null || instance.server != server) {
            instance = new ExternalBridge(server);
        }
        return instance;
    }

    public int port() {
        return PORT;
    }

    public synchronized void setEnabled(boolean on) {
        if (on) {
            start();
        } else {
            stop();
        }
    }

    public synchronized String status() {
        return running.get()
                ? "§a桥接已开启§r  127.0.0.1:" + PORT + "  客户端数=" + clients.size()
                : "§7桥接未开启§r  （OP 输入 /puppetctl bridge-on 开启）";
    }

    public synchronized void start() {
        if (running.get() || server == null) {
            return;
        }
        try {
            socket = new ServerSocket(PORT, 8, InetAddress.getLoopbackAddress());
            running.set(true);
            acceptThread = new Thread(this::acceptLoop, "puppet-bridge");
            acceptThread.setDaemon(true);
            acceptThread.start();
            LOG.info("[puppet] bridge listening on 127.0.0.1:{}", PORT);
        } catch (IOException e) {
            LOG.warn("[puppet] bridge failed to start: {}", e.toString());
            running.set(false);
        }
    }

    public synchronized void stop() {
        running.set(false);
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            socket = null;
        }
        for (Client c : clients) {
            c.close();
        }
        clients.clear();
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket s = socket.accept();
                Client c = new Client(s);
                clients.add(c);
                c.start();
                LOG.info("[puppet] python client connected from {}", s.getRemoteSocketAddress());
            } catch (IOException e) {
                if (running.get()) {
                    LOG.debug("[puppet] accept ended: {}", e.toString());
                }
            }
        }
    }

    /** 所有已连接的 Python 客户端都会收到同一条快照（外部按 player 字段过滤）。 */
    public void broadcast(String line) {
        for (Client c : clients) {
            c.send(line);
        }
    }

    /** 人偶产生的事件（受伤/拾取/任务结束…）同时推给 Python。 */
    public void push(String line) {
        broadcast(line);
    }

    public boolean hasClients() {
        return !clients.isEmpty();
    }

    // ---------------------------------------------------------------- 单连接

    private final class Client implements Runnable {
        private final Socket s;
        private PrintWriter out;
        private final Object writeLock = new Object();
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private volatile String playerName;

        Client(Socket s) {
            this.s = s;
        }

        void start() {
            Thread t = new Thread(this, "puppet-bridge-client");
            t.setDaemon(true);
            t.start();
        }

        @Override
        public void run() {
            try {
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                synchronized (writeLock) {
                    out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);
                }
                writeLine("{\"type\":\"welcome\",\"ok\":true,\"players\":" + playersJson() + "}");
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    try {
                        handle(line.trim());
                    } catch (Throwable t) {
                        LOG.warn("[puppet] bridge request failed: {}", t.toString());
                    }
                }
            } catch (IOException e) {
                LOG.debug("[puppet] client io: {}", e.toString());
            } finally {
                close();
                clients.remove(this);
            }
        }

        void send(String line) {
            PrintWriter w = out;
            if (w == null) {
                return;
            }
            synchronized (writeLock) {
                w.print(line);
                w.flush();
            }
        }

        private void writeLine(String json) {
            send(json + "\n");
        }

        private void handle(String line) {
            com.google.gson.JsonObject req = Protocol.tryParse(line);
            if (req == null) {
                writeLine("{\"type\":\"result\",\"ok\":false,\"text\":\"不是合法 JSON\"}");
                return;
            }
            String cmd = req.has("cmd") ? req.get("cmd").getAsString() : "";
            String who = req.has("player") ? req.get("player").getAsString() : playerName;
            if (cmd.equals("hello")) {
                playerName = who;
                writeLine("{\"type\":\"welcome\",\"ok\":true,\"players\":" + playersJson() + "}");
                return;
            }
            if (cmd.equals("players")) {
                writeLine("{\"type\":\"players\",\"players\":" + playersJson() + "}");
                return;
            }
            if (cmd.equals("status")) {
                writeLine("{\"type\":\"status\",\"running\":" + running.get() + ",\"port\":" + PORT
                        + ",\"clients\":" + clients.size() + "}");
                return;
            }
            // 其余指令都转给玩家的人偶，全部在主线程执行
            server.execute(() -> {
                ServerPlayer player = resolvePlayer(who);
                if (player == null) {
                    writeLine("{\"type\":\"result\",\"ok\":false,\"text\":\"找不到玩家：" + who + "\"}");
                    return;
                }
                PuppetRuntime rt = PuppetRuntime.of(player);
                String text = req.has("text") ? req.get("text").getAsString() : toCommand(cmd, req);
                PuppetRuntime.Result r = rt.submit(text, PuppetRuntime.Source.EXTERNAL);
                rt.setArmed(true);
                String snap = line(rt.snapshot(), "snapshot");
                writeLine(result(r));
                writeLine(snap);
            });
        }

        private String toCommand(String cmd, com.google.gson.JsonObject req) {
            StringBuilder sb = new StringBuilder();
            switch (cmd) {
                case "goto" -> sb.append("goto ").append(req.get("x").getAsDouble()).append(' ')
                        .append(req.get("y").getAsDouble()).append(' ').append(req.get("z").getAsDouble());
                case "mine" -> sb.append("mine ").append(req.has("count") ? req.get("count").getAsInt() : 8)
                        .append(' ').append(req.has("ore") ? req.get("ore").getAsString() : "");
                case "chop" -> sb.append("chop ").append(req.has("count") ? req.get("count").getAsInt() : 4)
                        .append(' ').append(req.has("wood") ? req.get("wood").getAsString() : "");
                case "harvest" -> sb.append("harvest ").append(req.has("count") ? req.get("count").getAsInt() : 16);
                case "follow" -> sb.append("follow ").append(req.has("target") ? req.get("target").getAsString() : "");
                case "guard" -> sb.append("guard ").append(req.has("target") ? req.get("target").getAsString() : "");
                case "kill" -> sb.append("kill ").append(req.has("mob") ? req.get("mob").getAsString() : "mob")
                        .append(' ').append(req.has("count") ? req.get("count").getAsInt() : 1);
                case "farm" -> sb.append("farm ").append(req.has("count") ? req.get("count").getAsInt() : 1);
                case "pickup" -> sb.append("pickup");
                case "stop" -> sb.append("stop");
                case "status" -> sb.append("status");
                case "input" -> sb.append("input ")
                        .append(req.has("forward") ? req.get("forward").getAsDouble() : 0).append(' ')
                        .append(req.has("strafe") ? req.get("strafe").getAsDouble() : 0).append(' ')
                        .append(req.has("yaw") ? req.get("yaw").getAsDouble() : 0).append(' ')
                        .append(req.has("pitch") ? req.get("pitch").getAsDouble() : 0)
                        .append(req.has("jump") && req.get("jump").getAsBoolean() ? " jump" : "")
                        .append(req.has("sprint") && req.get("sprint").getAsBoolean() ? " sprint" : "");
                default -> sb.append(cmd);
            }
            return sb.toString();
        }

        void close() {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private ServerPlayer resolvePlayer(String name) {
        var list = server.getPlayerList().getPlayers();
        if (name == null || name.isBlank() || name.equals("@p") || name.equals("@s")) {
            return list.isEmpty() ? null : list.get(0);
        }
        ServerPlayer p = server.getPlayerList().getPlayerByName(name);
        if (p != null) {
            return p;
        }
        for (ServerPlayer q : list) {
            if (q.getStringUUID().equals(name)) {
                return q;
            }
        }
        return null;
    }

    private String playersJson() {
        StringBuilder sb = new StringBuilder("[");
        var list = server == null ? List.<ServerPlayer>of() : server.getPlayerList().getPlayers();
        for (int i = 0; i < list.size(); i++) {
            ServerPlayer p = list.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"name\":\"").append(p.getName().getString().replace("\"", "\\\""))
                    .append("\",\"uuid\":\"").append(p.getStringUUID()).append("\"}");
        }
        return sb.append(']').toString();
    }

    private static String result(PuppetRuntime.Result r) {
        com.google.gson.JsonObject o = new com.google.gson.JsonObject();
        o.addProperty("type", "result");
        o.addProperty("ok", r.ok());
        o.addProperty("text", r.message());
        return Protocol.GSON.toJson(o);
    }

    private static String line(com.google.gson.JsonObject body, String type) {
        com.google.gson.JsonObject o = new com.google.gson.JsonObject();
        o.addProperty("type", type);
        for (var e : body.entrySet()) {
            o.add(e.getKey(), e.getValue());
        }
        return Protocol.GSON.toJson(o);
    }
}
