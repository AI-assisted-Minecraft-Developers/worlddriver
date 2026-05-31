package net.magicterra.agent.api;

import net.magicterra.agent.model.BlockSnapshot;
import java.util.List;

public final class AreaResult {
    public final List<BlockSnapshot> blocks;

    public AreaResult(List<BlockSnapshot> blocks) {
        this.blocks = blocks;
    }
}
