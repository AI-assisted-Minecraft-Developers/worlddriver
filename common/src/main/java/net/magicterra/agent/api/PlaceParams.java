package net.magicterra.agent.api;

import net.magicterra.agent.model.BlockPos;
import java.util.Map;

public final class PlaceParams {
    public BlockPos pos;
    public String type;

    public static PlaceParams from(Map<String, Object> m) {
        PlaceParams p = new PlaceParams();
        Object x = m.get("pos");
        if (x instanceof BlockPos bp) p.pos = bp;
        else if (x instanceof Map<?, ?> mm) {
            p.pos = new BlockPos(
                ((Number) mm.get("x")).intValue(),
                ((Number) mm.get("y")).intValue(),
                ((Number) mm.get("z")).intValue()
            );
        } else if (x instanceof String s) p.pos = BlockPos.parse(s);
        p.type = (String) m.get("type");
        return p;
    }
}
