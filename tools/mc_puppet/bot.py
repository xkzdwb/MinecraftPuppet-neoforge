"""与 Minecraft 模组通信的客户端。

依赖：仅标准库（socket / json / threading）。Python >= 3.9。
"""
from __future__ import annotations

import itertools
import json
import socket
import threading
import time
from typing import Any, Callable, Dict, Iterator, List, Optional

DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 25576


class PuppetError(RuntimeError):
    """连接失败或游戏返回错误。"""


class Bot:
    """一路到游戏里“那个玩家”的 TCP 连接。

    参数
    ----
    player:
        玩家名或 UUID。留空 = 服务器里第一个在线玩家（单人游戏就写 None）。
    host / port:
        模组桥接地址，默认 127.0.0.1:25576（可用 -Dpuppet.bridge.port 改端口）。
    autoconnect:
        True（默认）时构造即连接。
    """

    def __init__(self, player: Optional[str] = None, host: str = DEFAULT_HOST,
                 port: int = DEFAULT_PORT, autoconnect: bool = True,
                 timeout: float = 10.0) -> None:
        self.player = player
        self.host = host
        self.port = port
        self.timeout = timeout
        self._sock: Optional[socket.socket] = None
        self._reader: Optional[threading.Thread] = None
        self._lock = threading.Lock()
        self._events: List[Dict[str, Any]] = []
        self._event_cv = threading.Condition()
        self._state: Dict[str, Any] = {}
        self._last_snap_events: List[str] = []
        self._results: List[Dict[str, Any]] = []
        self._closed = False
        if autoconnect:
            self.connect()

    # ------------------------------------------------------------------ 连接

    def connect(self) -> "Bot":
        if self._sock is not None:
            return self
        try:
            sock = socket.create_connection((self.host, self.port), timeout=self.timeout)
        except OSError as exc:
            raise PuppetError(
                f"连不上 {self.host}:{self.port} —— 确认游戏已启动、模组已加载，"
                f"并在游戏里执行 /puppetctl bridge 查看桥接状态（{exc}）"
            ) from exc
        sock.settimeout(None)
        self._sock = sock
        self._closed = False
        self._reader = threading.Thread(target=self._read_loop, name="mc-puppet-reader", daemon=True)
        self._reader.start()
        welcome = self._next_of({"welcome", "players"}, timeout=self.timeout) or {}
        players = welcome.get("players") or []
        if self.player is None and players:
            self.player = players[0].get("name")
        return self

    def close(self) -> None:
        self._closed = True
        if self._sock is not None:
            try:
                self._sock.close()
            except OSError:
                pass
            self._sock = None

    def __enter__(self) -> "Bot":
        return self

    def __exit__(self, *exc: Any) -> None:
        self.close()

    # ------------------------------------------------------------------ 收发

    def _send(self, obj: Dict[str, Any]) -> None:
        if self._sock is None:
            self.connect()
        assert self._sock is not None
        line = json.dumps(obj, ensure_ascii=False)
        with self._lock:
            self._sock.sendall(line.encode("utf-8") + b"\n")

    def _read_loop(self) -> None:
        assert self._sock is not None
        buf = b""
        try:
            while not self._closed:
                chunk = self._sock.recv(65536)
                if not chunk:
                    break
                buf += chunk
                while b"\n" in buf:
                    raw, buf = buf.split(b"\n", 1)
                    if not raw.strip():
                        continue
                    try:
                        msg = json.loads(raw.decode("utf-8"))
                    except json.JSONDecodeError:
                        continue
                    self._dispatch(msg)
        except OSError:
            pass
        finally:
            self._closed = True

    def _dispatch(self, msg: Dict[str, Any]) -> None:
        t = msg.get("type")
        if t == "snapshot":
            self._state = msg
            snap_events = msg.get("events") or []
            with self._event_cv:
                self._last_snap_events = list(snap_events)
                self._event_cv.notify_all()
        elif t == "result":
            with self._event_cv:
                self._results.append(msg)
                self._event_cv.notify_all()
        elif t == "status":
            # 桥接自己的状态应答，不是“玩家状态”，忽略
            return
        elif t in ("event", "welcome", "players"):
            with self._event_cv:
                self._events.append(msg)
                self._event_cv.notify_all()

    def _next_of(self, types, timeout: Optional[float] = None) -> Optional[Dict[str, Any]]:
        """在事件流里等某种类型的消息（覆盖连接早期的 welcome 等）。"""
        types = {types} if isinstance(types, str) else set(types)
        deadline = time.time() + (timeout if timeout is not None else 5.0)
        while time.time() < deadline:
            with self._event_cv:
                for i, e in enumerate(self._events):
                    if e.get("type") in types:
                        return self._events.pop(i)
                self._event_cv.wait(0.1)
        return None

    def _wait_state(self, timeout: float = 1.0) -> None:
        deadline = time.time() + timeout
        while time.time() < deadline and not self._state:
            time.sleep(0.02)

    def _request(self, obj: Dict[str, Any], wait_state: bool = True,
                 timeout: float = 8.0) -> Dict[str, Any]:
        """发一条指令，等 result（必要时再等 snapshot），返回 result。"""
        self._send(obj)
        deadline = time.time() + timeout
        result: Optional[Dict[str, Any]] = None
        while time.time() < deadline:
            with self._event_cv:
                if self._results:
                    result = self._results.pop(0)
                    self._results.clear()
            if result is not None:
                break
            time.sleep(0.02)
        if result is None:
            raise PuppetError("游戏没有回应（模组可能没加载，或桥接已关闭）")
        if wait_state:
            self._wait_state(0.5)
        return result

    # ------------------------------------------------------------------ 指令

    def say(self, text: str) -> Dict[str, Any]:
        """纯自然语言（中文/英文都行），走游戏里同一个解析器。"""
        return self._request({"cmd": text, "player": self.player, "kind": "say"})

    def command(self, text: str) -> Dict[str, Any]:
        return self.say(text)

    # ---- 移动 ----
    def goto(self, x: float, y: float, z: float) -> Dict[str, Any]:
        return self._request({"cmd": "goto", "x": x, "y": y, "z": z, "player": self.player})

    def stop(self) -> Dict[str, Any]:
        return self._request({"cmd": "stop", "player": self.player})

    # ---- 采集 ----
    def mine(self, ore: str = "iron", count: int = 8) -> Dict[str, Any]:
        return self._request({"cmd": "mine", "ore": ore, "count": count, "player": self.player})

    def chop(self, count: int = 4, wood: str = "") -> Dict[str, Any]:
        return self._request({"cmd": "chop", "count": count, "wood": wood, "player": self.player})

    def harvest(self, count: int = 16) -> Dict[str, Any]:
        return self._request({"cmd": "harvest", "count": count, "player": self.player})

    def farm(self, count: int = 1) -> Dict[str, Any]:
        return self._request({"cmd": "farm", "count": count, "player": self.player})

    def pickup(self) -> Dict[str, Any]:
        return self._request({"cmd": "pickup", "player": self.player})

    # ---- 社交 / 战斗 ----
    def follow(self, target: str = "") -> Dict[str, Any]:
        return self._request({"cmd": "follow", "target": target, "player": self.player})

    def guard(self, target: str = "") -> Dict[str, Any]:
        return self._request({"cmd": "guard", "target": target, "player": self.player})

    def kill(self, mob: str = "mob", count: int = 1) -> Dict[str, Any]:
        return self._request({"cmd": "kill", "mob": mob, "count": count, "player": self.player})

    # ---- 逐帧手动（RL） ----
    def input(self, forward: float = 0.0, strafe: float = 0.0, yaw: Optional[float] = None,
              pitch: Optional[float] = None, jump: bool = False, sprint: bool = False) -> Dict[str, Any]:
        """逐帧输入。如果当前不是手动模式，会自动先切过去。"""
        if self.task != "manual":
            self.say("手动")
        if yaw is None:
            yaw = self.yaw
        if pitch is None:
            pitch = self.pitch
        return self._request({
            "cmd": "input", "forward": forward, "strafe": strafe, "yaw": yaw, "pitch": pitch,
            "jump": jump, "sprint": sprint, "player": self.player,
        }, wait_state=False, timeout=3.0)

    # ------------------------------------------------------------------ 观测

    @property
    def state(self) -> Dict[str, Any]:
        """最近一次快照（游戏每 100ms 推一次）。"""
        return self._state

    @property
    def pos(self):
        return tuple(self._state.get("pos", (0.0, 0.0, 0.0)))

    @property
    def yaw(self) -> float:
        return float(self._state.get("yaw", 0.0))

    @property
    def pitch(self) -> float:
        return float(self._state.get("pitch", 0.0))

    @property
    def task(self) -> str:
        return str(self._state.get("task", "idle"))

    @property
    def health(self) -> float:
        return float(self._state.get("health", 0.0))

    @property
    def events(self) -> List[Dict[str, Any]]:
        with self._event_cv:
            return list(self._events)

    def wait_events(self, kind: Optional[str] = None, timeout: float = 10.0,
                    contains: Optional[str] = None) -> Optional[Dict[str, Any]]:
        """阻塞等待一条事件（可按 kind / 关键词过滤）。

        同时看两条来源：
          * 事件推送（模组实时推的 ``puppet:event``）；
          * 快照里的 ``events`` 列表（最近 10 条，纯 python 环境或漏推时兜底）。
        """
        deadline = time.time() + timeout
        seen = set()

        def matches(d: Dict[str, Any]) -> bool:
            if kind is not None and d.get("kind") != kind:
                return False
            return contains is None or contains in str(d.get("text", ""))

        while time.time() < deadline:
            with self._event_cv:
                for i, e in enumerate(self._events):
                    if matches(e):
                        return self._events.pop(i)
                for line in self._last_snap_events:
                    if line in seen:
                        continue
                    seen.add(line)
                    # 快照里的事件是 "[kind] text" 形式的字符串
                    k, _, text = line.partition("] ")
                    d = {"type": "event", "kind": k.lstrip("["), "text": text}
                    if matches(d) and (kind is not None or contains is not None):
                        return d
                self._event_cv.wait(0.1)
        return None

    def wait_task_done(self, timeout: float = 300.0, poll: float = 0.25) -> str:
        """等到任务回到 idle，返回最后的任务名。"""
        deadline = time.time() + timeout
        last = self.task
        while time.time() < deadline:
            t = self.task
            if last != "idle" and t == "idle":
                return last
            if t != "idle":
                last = t
            time.sleep(poll)
        return last

    def snapshot(self) -> Dict[str, Any]:
        self._request({"cmd": "status", "player": self.player})
        return self._state

    def idle(self) -> Iterator[None]:
        """一个无限循环，方便脚本 `for _ in bot.idle(): ...` 常驻。"""
        while not self._closed:
            yield None
            time.sleep(0.05)
