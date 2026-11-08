"""示例：几个常见用法。直接 `python tools/examples.py` 即可（需要游戏开着）。"""
from __future__ import annotations

import time

from mc_puppet import Bot


def demo_basic() -> None:
    with Bot() as bot:
        print("玩家:", bot.player, "位置:", bot.pos)
        assert bot.say("去 100 64 -30")["ok"]
        bot.wait_task_done(timeout=120)


def demo_gather() -> None:
    with Bot() as bot:
        bot.say("砍 5 棵树")
        while bot.task != "idle":
            print(bot.state.get("task"), bot.pos, bot.state.get("events", [])[-1:])
            time.sleep(1)


def demo_follow() -> None:
    with Bot() as bot:
        bot.follow()          # 跟着最近的人
        for _ in range(60):
            time.sleep(1)
            print(bot.state.get("task"), bot.pos)


def demo_guard() -> None:
    with Bot() as bot:
        bot.guard()           # 原地警戒，有怪自动打


def demo_react() -> None:
    """事件驱动：挨打就还手，捡到东西就播报。"""
    with Bot() as bot:
        bot.say("保护")       # 守护模式（自带战斗）
        for _ in bot.idle():
            ev = bot.wait_events(timeout=1.0)
            if ev:
                print("事件:", ev.get("kind"), ev.get("text"))


def demo_rl_loop() -> None:
    """逐帧手动：一个极简的“往前走 3 秒”的 RL 骨架。"""
    with Bot() as bot:
        bot.say("手动")
        t0 = time.time()
        while time.time() - t0 < 3.0:
            st = bot.state
            # 这里换成你的策略：动作 -> input
            bot.input(forward=1.0, yaw=st.get("yaw", 0.0), sprint=True)
            time.sleep(0.05)
        bot.stop()


if __name__ == "__main__":
    demo_basic()
