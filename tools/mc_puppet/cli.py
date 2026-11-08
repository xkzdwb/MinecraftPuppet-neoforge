#!/usr/bin/env python3
"""命令行驱动：验证模组是否打通。

用法：
    python -m tools.mc_puppet.cli                 # 交互式
    python -m tools.mc_puppet.cli "砍 5 棵树"      # 单条指令
    python -m tools.mc_puppet.cli --watch         # 只打印状态
    python -m tools.mc_puppet.cli --player Steve "去 100 64 -30"
"""
from __future__ import annotations

import argparse
import sys
import time

from .bot import Bot, PuppetError, DEFAULT_PORT


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="操控 MC 里自己的玩家（人偶）")
    ap.add_argument("command", nargs="*", help="要执行的指令，省略则进入交互模式")
    ap.add_argument("--player", default=None, help="玩家名（默认第一个在线玩家）")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=DEFAULT_PORT)
    ap.add_argument("--watch", action="store_true", help="只打印状态")
    ap.add_argument("--wait", action="store_true", help="执行后等任务结束")
    args = ap.parse_args(argv)

    try:
        bot = Bot(player=args.player, host=args.host, port=args.port)
    except PuppetError as exc:
        print(exc, file=sys.stderr)
        return 2

    print(f"已连接：player={bot.player}")
    if args.watch:
        try:
            while True:
                s = bot.state
                pos = s.get("pos", [0, 0, 0])
                print(f"\r ({pos[0]:7.1f},{pos[1]:7.1f},{pos[2]:7.1f})  health={s.get('health')}  "
                      f"task={s.get('task')}      ", end="", flush=True)
                time.sleep(0.2)
        except KeyboardInterrupt:
            return 0

    if args.command:
        line = " ".join(args.command)
        r = bot.say(line)
        print(("✔ " if r.get("ok") else "✘ ") + str(r.get("text")))
        if args.wait:
            print("完成任务：", bot.wait_task_done())
        return 0 if r.get("ok") else 1

    print("输入指令（空行退出，/help 查看可用指令）：")
    while True:
        try:
            line = input("puppet> ").strip()
        except (EOFError, KeyboardInterrupt):
            print()
            break
        if not line:
            break
        r = bot.say(line)
        print(("✔ " if r.get("ok") else "✘ ") + str(r.get("text")))
    bot.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
