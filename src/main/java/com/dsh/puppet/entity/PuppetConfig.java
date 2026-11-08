package com.dsh.puppet.entity;

/** 人偶的可调参数。 */
public final class PuppetConfig {
    /** 采集时扫描威胁的半径。 */
    public double dangerScanRadius = 12.0;
    /** 单个任务的最长运行时间（tick），防止脚本忘了停。 */
    public long maxTaskTicks = 20L * 60L * 10L;
    /** 自动拾取掉落物。 */
    public boolean autoPickup = true;
    /** 不主动攻击村民 / 友好生物。 */
    public boolean protectVillagers = true;
    /** 攻击距离。 */
    public double attackRange = 3.2;
    /** 挖掘距离。 */
    public double mineReach = 4.5;
    /** 每 tick 移动速度倍率。 */
    public double moveScale = 1.0;
    /** 视角每 tick 最大转动角度（便于观感自然）。 */
    public float maxTurnDegrees = 30.0f;
}
