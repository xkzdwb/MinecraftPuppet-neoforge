"""示例：接 LLM（DeepSeek / 任意 OpenAI 兼容接口）。

    set DEEPSEEK_API_KEY=sk-...
    python tools/llm_example.py "先砍三棵树，再跟着我"
"""
from __future__ import annotations

import os
import sys

from mc_puppet import Bot
from mc_puppet.llm import LLMAgent, make_chat_fn


def main() -> int:
    goal = " ".join(sys.argv[1:]) or "砍三棵树，然后跟着我"
    key = os.environ.get("DEEPSEEK_API_KEY")
    if not key:
        print("请先设置 DEEPSEEK_API_KEY；或者把 llm= 换成本地模型包装函数。")
        return 2
    chat = make_chat_fn(key)
    with Bot() as bot:
        LLMAgent(bot, chat, interval=4.0).run(goal, max_steps=40)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
