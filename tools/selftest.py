"""自测：用假桥验证 Python 驱动端（不需要 Minecraft）。

    python tools/selftest.py
"""
from __future__ import annotations

import os
import socket
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from mc_puppet import Bot  # noqa: E402
from mc_puppet.llm import parse_command, prompt_for, state_to_text  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
failures = []


def check(name, cond, extra=""):
    print(("  ok   " if cond else "  FAIL ") + name + (f"  {extra}" if extra else ""))
    if not cond:
        failures.append(name)


def port_open(port=25576) -> bool:
    with socket.socket() as s:
        s.settimeout(0.5)
        return s.connect_ex(("127.0.0.1", port)) == 0


def main() -> int:
    if port_open():
        print("25576 已被占用（可能是真游戏已经在跑），跳过假桥测试。")
        return 0
    proc = subprocess.Popen([sys.executable, os.path.join(HERE, "fake_bridge.py")],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        for _ in range(50):
            if port_open():
                break
            time.sleep(0.1)
        check("假桥监听", port_open())

        bot = Bot()
        check("握手拿到玩家", bot.player == "Steve", repr(bot.player))

        r = bot.say("砍 5 棵树")
        check("自然语言指令", r.get("ok") is True, r.get("text", ""))
        time.sleep(0.3)
        check("任务状态更新", bot.task == "chop", bot.task)

        r = bot.goto(10, 64, 10)
        check("goto 成功", r.get("ok") is True, r.get("text", ""))
        time.sleep(0.8)
        check("位置变化", abs(bot.pos[0] - 10) < 0.5 and abs(bot.pos[2] - 10) < 0.5, str(bot.pos))

        r = bot.mine("diamond", 3)
        check("mine 成功", r.get("ok") is True, r.get("text", ""))

        ev = bot.wait_events("pickup", timeout=2.0, contains="铁矿石")
        check("收到事件", ev is not None, str(ev))

        snap = bot.snapshot()
        check("快照字段", {"pos", "yaw", "task", "hotbar", "nearby"} <= set(snap), str(sorted(snap)[:6]))
        check("快捷栏解析", len(bot.state.get("hotbar", [])) == 9)
        check("nearby 解析", isinstance(bot.state.get("nearby"), list))

        r = bot.stop()
        check("stop 成功", r.get("ok") is True)
        time.sleep(0.3)
        check("stop 后 idle", bot.task == "idle", bot.task)

        txt = state_to_text(bot.state)
        check("状态文本", "位置" in txt and "血量" in txt, txt.splitlines()[0])

        p = prompt_for(bot.state, "砍树")
        check("LLM prompt", "【目标】砍树" in p and p.rstrip().endswith("请只输出下一条指令。"))

        check("解析裸指令", parse_command("砍 3 棵\n说明文字") == "砍 3 棵")
        check("解析代码块", parse_command("```\n去 1 2 3\n```") == "去 1 2 3")
        check("解析 JSON", parse_command('{"cmd": "挖 5 个钻石"}') == "挖 5 个钻石")
        check("解析前缀", parse_command("指令：跟着") == "跟着")

        r = bot.say("手动")
        time.sleep(0.3)
        check("手动模式", bot.task == "manual", bot.task)
        r = bot.input(forward=1.0, jump=True)
        check("逐帧输入", r.get("ok") is True)

        bot.close()
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=3)
        except subprocess.TimeoutExpired:
            proc.kill()

    print()
    if failures:
        print(f"{len(failures)} 项失败：{failures}")
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
