"""mc_puppet —— 用 Python（或 LLM）操控 Minecraft 里“你自己的玩家”。

模组端（src/main/java）在服务端监听 127.0.0.1:25576，一行一条 JSON。
本包把这条通道包装成对象：
    from mc_puppet import Bot
    bot = Bot()
    bot.say("砍 5 棵树")        # 自然语言，和游戏里 /puppet 后面写的一样
    bot.goto(100, 64, -30)      # 结构化
    print(bot.state)            # 观测快照
"""
from .bot import Bot, PuppetError, DEFAULT_PORT

__all__ = ["Bot", "PuppetError", "DEFAULT_PORT"]
