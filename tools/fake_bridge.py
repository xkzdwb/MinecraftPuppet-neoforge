"""假模组桥（FakeBridge）：在没有 Minecraft 的情况下验证 Python 端。

监听 127.0.0.1:25576（可用环境变量 PUPPET_PORT 改），模拟模组的下行消息
（welcome / snapshot / event / result），并对 goto / mine / chop / follow / stop 等指令
给出“假装在干活”的状态变化。用来跑通 tools/mc_puppet 的链路和示例脚本。
"""
from __future__ import annotations

import json
import math
import os
import socket
import threading
import time

PORT = int(os.environ.get("PUPPET_PORT", "25576"))

TASKS = {
    "mine": "mine_iron", "chop": "chop", "harvest": "harvest", "follow": "follow",
    "guard": "guard", "kill": "kill_mob", "farm": "tree_farm", "pickup": "pickup",
    "input": "manual",
}


class FakeWorld:
    def __init__(self) -> None:
        self.x, self.y, self.z = 0.5, 64.0, 0.5
        self.yaw, self.pitch = 0.0, 0.0
        self.task = "idle"
        self.goal: tuple[float, float] | None = None
        self.health, self.food = 20.0, 20
        self.events: list[str] = []
        self.lock = threading.Lock()
        self.t0 = time.time()

    def aim(self, tx: float, tz: float) -> None:
        self.yaw = math.degrees(math.atan2(tz - self.z, tx - self.x)) - 90.0

    def tick(self, dt: float) -> None:
        with self.lock:
            if self.task == "goto" and self.goal is not None:
                tx, tz = self.goal
                dx, dz = tx - self.x, tz - self.z
                d = math.hypot(dx, dz)
                if d < 0.05:
                    self.task = "idle"
                    self.goal = None
                    self.events.append("[task] 已到达")
                else:
                    self.aim(tx, tz)
                    step = min(d, 24.0 * dt)
                    self.x += dx / d * step
                    self.z += dz / d * step
            elif self.task != "idle" and int(time.time() * 2) % 7 == 0:
                self.health = max(1.0, self.health)
            if len(self.events) > 40:
                self.events = self.events[-40:]

    def snapshot(self) -> dict:
        with self.lock:
            return {
                "type": "snapshot", "player": "Steve", "armed": True, "task": self.task,
                "task_time": int((time.time() - self.t0) * 20), "dim": "minecraft:overworld",
                "game_time": int((time.time() - self.t0) * 20),
                "pos": [round(self.x, 3), round(self.y, 3), round(self.z, 3)],
                "vel": [0, 0, 0], "yaw": round(self.yaw, 1), "pitch": round(self.pitch, 1),
                "on_ground": True, "sneaking": False, "sprinting": False,
                "health": self.health, "max_health": 20.0, "food": self.food, "xp": 7,
                "held": {"item": "minecraft:iron_pickaxe", "count": 1, "name": "铁镐"},
                "air": 300, "is_op": True, "selected": 0,
                "hotbar": [
                    {"item": "minecraft:iron_pickaxe", "count": 1, "name": "铁镐"},
                    {"item": "minecraft:oak_log", "count": 12, "name": "橡木原木"},
                    None, None, None, None, None, None, None,
                ],
                "nearby": [{
                    "id": 42, "type": "minecraft:zombie", "name": "僵尸",
                    "pos": [self.x + 3, self.y, self.z], "dist": 3.0,
                    "health": 12.0, "hostile": True,
                }],
                "events": list(self.events[-10:]),
            }


def handle(conn: socket.socket, world: FakeWorld) -> None:
    world_lock = threading.Lock()

    def send(obj) -> None:
        try:
            conn.sendall((json.dumps(obj, ensure_ascii=False) + "\n").encode("utf-8"))
        except OSError:
            pass

    def push_loop() -> None:
        while True:
            send(world.snapshot())
            time.sleep(0.1)

    send({"type": "welcome", "ok": True, "players": [{"name": "Steve", "uuid": "fake-uuid"}]})
    threading.Thread(target=push_loop, daemon=True).start()
    buf = b""
    try:
        while True:
            chunk = conn.recv(65536)
            if not chunk:
                break
            buf += chunk
            while b"\n" in buf:
                raw, buf = buf.split(b"\n", 1)
                try:
                    req = json.loads(raw.decode("utf-8"))
                except json.JSONDecodeError:
                    continue
                with world_lock:
                    reply = world_handle(world, req)
                send(reply)
    except OSError:
        pass
    finally:
        try:
            conn.close()
        except OSError:
            pass


def world_handle(world: FakeWorld, req: dict) -> dict:
    cmd = str(req.get("cmd", ""))
    with world.lock:
        if cmd in ("hello", "status", "players"):
            return {"type": "result", "ok": True, "text": "status"}
        if cmd == "stop":
            world.task, world.goal = "idle", None
            world.events.append("[stop] 已停止")
            return {"type": "result", "ok": True, "text": "已停止"}
        if cmd == "goto":
            world.goal = (float(req.get("x", 0)), float(req.get("z", world.z)))
            world.task = "goto"
            world.events.append(f"[task] 开始：goto {world.goal}")
            return {"type": "result", "ok": True, "text": "前往目的地"}
        if cmd == "mine":
            world.task = "mine_iron"
            world.events.append("[pickup] 拾取 铁矿石 x3")
            return {"type": "result", "ok": True, "text": f"开始挖 {req.get('ore')}"}
        if cmd in TASKS:
            world.task = TASKS[cmd]
            world.events.append(f"[task] 开始：{world.task}")
            return {"type": "result", "ok": True, "text": f"{cmd} 已开始"}
        # 自然语言：粗略关键词
        for key, task in (("手动", "manual"), ("挖", "mine_iron"), ("砍", "chop"), ("树", "chop"),
                          ("跟", "follow"), ("保护", "guard"), ("警戒", "guard"), ("打", "kill_mob"),
                          ("收", "harvest"), ("捡", "pickup"), ("停", "idle"), ("去", "goto")):
            if key in cmd:
                world.task = task
                if task == "idle":
                    world.goal = None
                world.events.append(f"[task] 开始：{task}")
                return {"type": "result", "ok": True, "text": f"识别为 {task}"}
        return {"type": "result", "ok": False, "text": "没听懂这条指令：" + cmd}


def main() -> None:
    world = FakeWorld()

    def loop() -> None:
        while True:
            world.tick(0.05)
            time.sleep(0.05)

    threading.Thread(target=loop, daemon=True).start()
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", PORT))
    srv.listen(8)
    print(f"FakeBridge 监听 127.0.0.1:{PORT}", flush=True)
    while True:
        conn, _ = srv.accept()
        threading.Thread(target=handle, args=(conn, world), daemon=True).start()


if __name__ == "__main__":
    main()
