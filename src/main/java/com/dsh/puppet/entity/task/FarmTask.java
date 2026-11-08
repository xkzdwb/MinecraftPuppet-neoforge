package com.dsh.puppet.entity.task;

import com.dsh.puppet.entity.PuppetRuntime;

/** 收菜：复用 GatherTask 的 HARVEST 模式，收完补种。 */
public class FarmTask extends GatherTask {
    public FarmTask(PuppetRuntime rt, int count) {
        super(rt, Kind.HARVEST, "", count);
    }

    @Override
    public String name() {
        return "harvest";
    }
}
