package com.dsh.puppet.entity.task;

import com.dsh.puppet.entity.PuppetRuntime;

/** 常用任务基类：管理 running / reason。 */
public abstract class BaseTask implements Task {
    protected final PuppetRuntime rt;
    protected boolean running = true;
    protected String reason;

    protected BaseTask(PuppetRuntime rt) {
        this.rt = rt;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public String reason() {
        return reason;
    }

    public void finish(String why) {
        if (running) {
            this.running = false;
            this.reason = why;
        }
    }

    @Override
    public void stop(String reason) {
        if (this.reason == null) {
            this.reason = reason == null ? "已停止" : reason;
        }
        this.running = false;
    }

    @Override
    public void start() {
    }
}
