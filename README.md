# Puppet Controller — NeoForge 26.2.0.88 / Minecraft 26.2

直接操控**你自己的玩家实体**的 NeoForge 模组 + Python 驱动端。

不是假人、不是假客户端：被操控的就是玩家本人的 `ServerPlayer`，
物品栏、经验、护甲、附魔、掉血、死亡、存档全部照常。

**不模拟键盘鼠标** —— 服务端把每 tick 的意图直接换算成 `player.move(...)` / `jumpFromGround()`，
挖掘走 `gameMode.handleBlockBreakAction()`，交互走 `useItemOn()`，攻击走 `player.attack()`。
全程不碰 `Keyboard` / `Mouse`，所以玩家客户端在后台、失焦、甚至没开，人偶照样干活。

---

## 版本对应关系（重要）

| 名称 | 值 |
| --- | --- |
| **`26.2.0.88`** | **NeoForge 的构建号**（不是 Minecraft 版本号） |
| Minecraft | **26.2**（2026-06-16 发布，protocol 776） |
| NeoForge | `net.neoforged:neoforge:26.2.0.88` |
| neoform | `26.2-2` |
| **Java** | **25**（MC 26.2 的 `javaVersion.majorVersion`，不是 21） |
| ModDevGradle | `2.0.148` |
| Gradle | 9.9 |

> Minecraft 从 2026 年起把版本号改成 `YY.D.H`（年.发布.热修），所以游戏版本就是 `26.2`。

## 安装

把 `build/libs/puppet-neoforge-1.0.0+mc26.2.jar` 丢进 `mods/`，NeoForge 26.2.0.88 + Java 25 启动。
单人存档和服务器都适用；服务端装了就够，客户端装不装都能跑（装了才有 K 面板）。

---

## 玩法

| 操作 | 说明 |
| --- | --- |
| `/puppet help` | 所有指令 |
| `/puppet 挖 10 个钻石` | 自己挖（普通玩家可用，不需要 OP） |
| `/puppet 砍 5 棵树` | 找树、走过去、砍倒 |
| `/puppet 去 100 64 -30` | 3D A* 寻路走过去 |
| `/puppet 跟着我` | 跟随最近的玩家（也可 `跟着 Steve`） |
| `/puppet 保护` | 原地警戒，怪一靠近就上去打 |
| `/puppet 收 16` | 收成熟作物并补种 |
| `/puppet 打僵尸 3` | 主动清怪 |
| `/puppet 种树 4` | 种树循环 |
| `/puppet 停` | 停手 |
| `/puppet 状态` | 汇报位置/血量/任务 |
| `/puppet 手动 1 0 90 0 jump` | 逐帧输入（RL 用） |
| **`K`** | 打开「人偶控制台」面板 |

中英文混用都行，"来点木头"、"弄点铁" 这类整句会走关键词兜底落到正确任务。

`/puppetctl`（需要 OP）：`arm` / `disarm`、`gui`、`bridge`、`bridge-on`、`bridge-off`。

面板只是遥控器 —— 关掉面板（再按 K 或 Esc）任务继续跑。

## Python / LLM

```python
from mc_puppet import Bot

with Bot() as bot:                # 本地 TCP 127.0.0.1:25576
    bot.say("砍 5 棵树")          # 自然语言，和 /puppet 同一解析器
    bot.goto(100, 64, -30)
    bot.wait_task_done()
    print(bot.pos, bot.health, bot.state["nearby"])
```

```bash
cd tools
python selftest.py                # 假桥自检，不需要开游戏
python mc_puppet/cli.py "砍 5 棵树"
python mc_puppet/cli.py --watch
```

| 方法 | 作用 |
| --- | --- |
| `say(text)` / `command(text)` | 任意自然语言指令 |
| `goto(x,y,z)` / `stop()` | 移动 / 停止 |
| `mine(ore,count)` / `chop(n)` / `harvest(n)` / `farm(n)` / `pickup()` | 采集 |
| `follow(t)` / `guard(t)` / `kill(mob,n)` | 跟随 / 守护 / 战斗 |
| `input(forward,strafe,yaw,pitch,jump,sprint)` | 逐帧输入（RL） |
| `state` / `pos` / `yaw` / `task` / `health` | 观测快照（10Hz） |
| `wait_events(kind,timeout)` / `wait_task_done()` | 等事件 / 等任务结束 |

接 LLM：`tools/mc_puppet/llm.py` 已把「观测 → prompt → 一句指令」拼好，模型只要吐一句中文/英文即可。

---

## 构建

需要 **JDK 25**（编译目标）+ **Gradle 8.14+ / 9.x**。

```bash
./gradlew build
```

产物：`build/libs/puppet-neoforge-1.0.0+mc26.2.jar`。

JDK 25 不在默认位置时，用 `-P` 告诉 Gradle：

```bash
./gradlew build "-Porg.gradle.java.installations.paths=/path/to/jdk-25"
```

Windows 上也可以直接跑封装脚本（它会自动找 JDK 25 / Gradle / node）：

```powershell
powershell -ExecutionPolicy Bypass -File gradle\build.ps1
```

### 其他常用任务

| 命令 | 作用 |
| --- | --- |
| `./gradlew runClient` | 启动带模组的客户端 |
| `./gradlew runServer` | 启动专用服务器 |
| `./gradlew verifyMixins` | **反射校验所有 mixin 注入点在当前 MC 版本中真实存在** |
| `./gradlew createMinecraftArtifacts` | 重新生成反编译的 Minecraft 源码 |

`verifyMixins` 是给换版本用的：Mixin 的 `@Inject` 用字符串方法名，编译器不查，
版本升级后最容易静默失败。它已挂到 `check` 上，`build` 会自动跑。

### 可选：受限网络下的修补

如果你所在网络**连不上 `maven.neoforged.net`**（表现为 `createMinecraftArtifacts` 超时），
本仓库带了两个可选工具，默认不启用，不影响正常环境：

| 工具 | 作用 |
| --- | --- |
| `tools/neoforge-mirror-proxy.mjs` | 本地终止 TLS，把 `maven.neoforged.net` 转发到 `maven.creeperhost.net` |
| `tools/gen-mc-deps.mjs` | 从 Mojang 官方版本清单还原 `net.neoforged:minecraft-dependencies` |

启用方式：生成证书放到 `.build-tls/`，然后把 `maven.neoforged.net` 指向 `127.0.0.1`，
再跑 `build.ps1`（它检测到 `.build-tls/truststore.p12` 会自己接管），或加 `-Offline`。

以下是这台机器上实测遇到的四个问题，记录在此供参考：

| 问题 | 现象 | 处理 |
| --- | --- | --- |
| `maven.neoforged.net` 不可达 | DNS 解析到 202.160.x.x，TCP 443 超时 | 本地代理 + hosts 指向 127.0.0.1 + 自签证书进 trustStore |
| `neoforged:minecraft-dependencies` 缺失 | creeperhost 镜像里没有该构件 | `gen-mc-deps.mjs` 从 Mojang 清单还原（含 natives classifier 与 OS 过滤） |
| NeoForm 锁文件被拒 | `AccessDeniedException: .../caches/neoformruntime/*.lock` | `GRADLE_USER_HOME` 指到工程内 `.gradle-home` |
| **JDK 25 装在中文路径** | **所有文件写入 `AccessDenied`**（连 `Files.writeString` 都挂） | JDK 25 必须放纯 ASCII 路径；`build.ps1` 会警告 |

最后一条最隐蔽：同一个 JDK 25，放在 `P:\mcwork\jdk25b` 正常，放在含中文的路径下就拒绝一切写入。
JDK 21 / 26 没有这个问题。

---

## 26.2 相对 1.21.4 的 API 变化（移植时实际踩到的）

这个模组原本是 Fabric / Yarn 1.21.4，这次整体移植到 NeoForge / **Mojang 官方映射** 26.2。改动较大的地方：

| 类别 | 1.21.4 | 26.2 |
| --- | --- | --- |
| 类名 | `ResourceLocation` | **`Identifier`**（`Identifier.parse(...)`） |
| 类名 | `ClientPlayerEntity` | `LocalPlayer` |
| 类名 | `ServerPlayerEntity` | `ServerPlayer` |
| 类名 | `ServerCommandSource` | `CommandSourceStack` |
| 类名 | `PlayerInventory.selectedSlot` | `getSelectedSlot()` / `setSelectedSlot()` |
| 类名 | `InteractionManager` | `ServerPlayerGameMode` |
| GUI | `GuiGraphics` | **`GuiGraphicsExtractor`**，绘制用 `text(font,...)` / `fill(...)` / `outline(...)` |
| GUI | `Screen#render` | **`Screen#extractRenderState`** |
| GUI | `Minecraft.setScreen` | **`Minecraft.gui.setScreen` / `gui.screen()`** |
| GUI | `keyPressed(int,int,int)` | **`keyPressed(KeyEvent)`**；`KeyMapping.matches(KeyEvent)` |
| 权限 | `hasPermissionLevel(2)` | **`PermissionSet` 体系**：`permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)` |
| 消息 | `player.sendMessage(text, overlay)` | `player.sendSystemMessage(component, overlay)` |
| 进食 | `HungerManager#eat(FoodComponent)` | `FoodData#eat(FoodProperties)`（不接受 `ItemStack`） |
| 注册 | `Registry.get(id)` | `Registry.getValue(id)` |
| 事件 | `@EventBusSubscriber(bus=...)` | 无 `Bus` 枚举，改用 `@Mod(dist=...)` + 显式 `bus.register(...)` |
| Network | Fabric Networking | NeoForge `CustomPacketPayload` + `RegisterPayloadHandlersEvent`（`.optional()` 注册） |
| Jump | `player.jump()` | `jumpFromGround()` |
| Move | `player.move(MovementType.SELF, …)` | `player.move(MoverType.SELF, …)` |

### 注入点自检

Mixin 的 `@Inject` 用字符串方法名，编译器不检查，换版本时最容易静默失败。
`gradle verifyMixins` 会反射校验**每一个**注入点和关键原版入口真实存在：

```
MIXIN SELF-CHECK OK: all injection targets present
```

已覆盖：`Entity#setYRot/setXRot`、`ServerPlayer#hurtServer(ServerLevel,DamageSource,float)`、
`ServerPlayer#die`、`ItemEntity#playerTouch(Player)`、`ServerPlayerGameMode#destroyBlock(BlockPos)` +
protected `player` 字段、`handleBlockBreakAction(...)`、`useItemOn(...)`、`LivingEntity#jumpFromGround`。

---

## 实现结构

```
src/main/java/com/dsh/puppet/
  PuppetMod.java            模组入口（tick 分发、快照推送、客户端消息路由）
  PuppetCommands.java       /puppet、/puppetctl
  Protocol.java             协议常量
  MixinSelfCheck.java       注入点反射自检
  entity/
    PuppetRuntime.java      每个玩家一份：任务、输入、观测、事件
    InputInjector.java      唯一碰实体运动的地方
    PuppetActions.java      move / 挖 / 交互 / 攻击 / 视线
    PuppetRotation.java     服务端权威朝向接口
    SimulatedInput.java     一 tick 的意图
    Commands.java           中英文自然语言 → 任务
    ExternalBridge.java     127.0.0.1:25576 TCP 桥
    path/Pathfinder.java    3D A*
    task/                   Goto/Follow/Guard/Kill/Gather/Pickup/Farm/ChopLoop/Manual/Walk
  network/                  NeoForge CustomPayload
  mixin/                    运行时挂载、朝向记忆、受伤/死亡、拾取、方块破坏
  client/                   K 面板、状态缓存、按键
tools/                      Python 驱动（Bot/LLM/cli）+ 假桥自检 + 本机构建工具
```

### 关于「服务端朝向」

玩家实体在服务端的 yaw/pitch 是客户端上报的，服务端自己并不能"转头"。
所以人偶把想要的朝向存在 `PuppetRotation`（mixin 挂在 `Entity` 上），
挖掘/攻击的视线计算全用这一份；客户端面板与第三人称渲染也读它。

---

## 行为边界

* 采集只挖 `getDestroySpeed >= 0` 的方块，不碰基岩、屏障、箱子、命令方块。
* 默认任务上限 10 分钟（`PuppetConfig.maxTaskTicks`），防止脚本忘了停。
* 手动模式 10 秒没有新输入会自动退出。
* 桥接只监听回环地址，所有改世界的操作都 `server.execute()` 回主线程。

## 验证状态

* `gradle build` 通过，产出 jar。
* `gradle verifyMixins` 通过 —— 所有注入点对 26.2 真实存在。
* 从**干净的仓库副本**（只有本仓库会提交的文件）完整构建通过 —— 确认 clone 后即可构建。
* `python tools/selftest.py` 通过 —— Python 端整条链路（假桥）。
* **没有在真实客户端里跑过**（本机没装 Minecraft）。第一局建议先在小世界试 `/puppet 砍 3 棵树`。

## 目录说明

会被提交到仓库的部分：

```
src/            Java 源码（模组主体）
tools/          Python 驱动（Bot / LLM / cli / 假桥自检）+ 可选的网络修补脚本
gradle/         build.ps1（Windows 一键构建）
gradlew, gradlew.bat, gradle/wrapper/   Gradle Wrapper
build.gradle, settings.gradle, gradle.properties
README.md, LICENSE, .gitignore
```

不会提交（见 `.gitignore`）：`build/`、`.gradle/`、`.gradle-home/`、`.build-mcdeps/`、`.build-tls/`、
`.build-neoform/`、`__pycache__/`、IDE 目录。

MIT License.
