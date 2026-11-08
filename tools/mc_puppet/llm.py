"""LLM 接入：把“观测 -> 一句指令”的循环拼起来。

只要用户自己的模型能吐出一句中文/英文指令（或 JSON），就能驱动人偶：

    llm = MyChatModel()          # 任何 callable: prompt -> str
    agent = LLMAgent(bot, llm.say)
    agent.run("先砍 5 棵树，然后跟着我")

`prompt_for()` 会把当前状态整理成一段适合喂给模型的中文描述。
"""
from __future__ import annotations

import json
import time
from typing import Any, Callable, Dict, List, Optional

from .bot import Bot

SYSTEM_PROMPT = """你是一个 Minecraft 玩家的代打 AI，只能通过一句指令来控制玩家的角色。
可用指令（每次只输出一句，不要解释）：
  去 <x> <y> <z>
  挖 <数量> <矿种>     矿种：coal/iron/gold/diamond/copper/redstone/lapis/emerald/debris
  砍 <数量> [树种]
  收 <数量>
  捡
  跟着 [名字]
  保护 [名字]
  打 <怪物> [数量]
  种树 <数量>
  停
观测格式：pos 位置(x,y,z)、yaw/pitch 朝向、health 血量、food 饥饿、held 手持、nearby 附近实体、events 最近事件。"""


def state_to_text(state: Dict[str, Any]) -> str:
    if not state:
        return "（还没有收到游戏状态）"
    pos = state.get("pos", [0, 0, 0])
    lines = [
        f"位置: ({pos[0]:.1f}, {pos[1]:.1f}, {pos[2]:.1f})  维度: {state.get('dim', '?')}",
        f"朝向: yaw={state.get('yaw', 0):.0f} pitch={state.get('pitch', 0):.0f}  "
        f"着地={state.get('on_ground')}",
        f"血量: {state.get('health')}/{state.get('max_health')}  饥饿: {state.get('food')}  等级: {state.get('xp')}",
        f"当前任务: {state.get('task')}  已运行: {state.get('task_time', 0)} tick",
        f"手持: {(state.get('held') or {}).get('name', '空手') if isinstance(state.get('held'), dict) else '空手'}",
    ]
    hotbar = [s.get("name") for s in state.get("hotbar", []) if isinstance(s, dict)]
    if hotbar:
        lines.append("快捷栏: " + " | ".join(hotbar))
    nearby = state.get("nearby") or []
    if nearby:
        desc = [f"{e.get('name')}({e.get('dist')}格{'，敌对' if e.get('hostile') else ''})" for e in nearby[:8]]
        lines.append("附近: " + "，".join(desc))
    events = state.get("events") or []
    if events:
        lines.append("最近事件: " + "；".join(events[-5:]))
    return "\n".join(lines)


def prompt_for(state: Dict[str, Any], goal: str, history: Optional[List[str]] = None) -> str:
    parts = [SYSTEM_PROMPT, "\n【当前观测】\n" + state_to_text(state), f"\n【目标】{goal}"]
    if history:
        parts.append("\n【最近下过的指令】\n" + "\n".join(history[-6:]))
    parts.append("\n请只输出下一条指令。")
    return "\n".join(parts)


def parse_command(reply: str) -> Optional[str]:
    """模型回复 -> 一条指令。支持裸文本、JSON {"cmd": "..."}、带 ``` 代码块。"""
    if not reply:
        return None
    text = reply.strip()
    if text.startswith("```"):
        text = text.strip("`")
        if "\n" in text:
            text = text.split("\n", 1)[1]
    text = text.strip()
    if text.startswith("{"):
        try:
            obj = json.loads(text)
            for key in ("cmd", "command", "action", "text"):
                if isinstance(obj.get(key), str):
                    return obj[key].strip()
        except json.JSONDecodeError:
            pass
    # 只取第一行、去掉常见前缀
    line = text.splitlines()[0].strip()
    for prefix in ("指令：", "指令:", "命令：", "命令:", "cmd:", "Cmd:"):
        if line.startswith(prefix):
            line = line[len(prefix):].strip()
    return line or None


class LLMAgent:
    """观测 -> LLM -> 指令 的循环。

    `llm` 是一个可调用对象：接收 prompt 字符串，返回文本。
    也可以传入 OpenAI/DeepSeek 风格的 chat 函数，自己包一层即可。
    """

    def __init__(self, bot: Bot, llm: Callable[[str], str],
                 interval: float = 3.0, verbose: bool = True) -> None:
        self.bot = bot
        self.llm = llm
        self.interval = interval
        self.verbose = verbose
        self.history: List[str] = []

    def step(self, goal: str) -> Optional[str]:
        prompt = prompt_for(self.bot.state, goal, self.history)
        reply = self.llm(prompt)
        cmd = parse_command(reply)
        if cmd is None:
            return None
        if self.verbose:
            print(f"[llm] {cmd}")
        self.history.append(cmd)
        self.bot.say(cmd)
        return cmd

    def run(self, goal: str, max_steps: int = 100) -> None:
        for _ in range(max_steps):
            if self.bot.task == "idle" and self.history and "停" in self.history[-1]:
                return
            self.step(goal)
            time.sleep(self.interval)


def make_chat_fn(api_key: str, base_url: str = "https://api.deepseek.com", model: str = "deepseek-chat") -> Callable[[str], str]:
    """（可选）用 OpenAI 兼容接口做 LLM。需要 pip install openai。"""
    def _fn(prompt: str) -> str:
        from openai import OpenAI  # 延迟导入，避免没装就报错

        client = OpenAI(api_key=api_key, base_url=base_url)
        resp = client.chat.completions.create(
            model=model,
            messages=[{"role": "user", "content": prompt}],
            temperature=0.3,
        )
        return resp.choices[0].message.content or ""

    return _fn
