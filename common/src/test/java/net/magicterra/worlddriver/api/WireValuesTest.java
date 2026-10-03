package net.magicterra.worlddriver.api;

import java.util.List;
import java.util.Map;
import net.magicterra.worlddriver.model.DriverEvent;
import net.magicterra.worlddriver.protocol.JsonCodec;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WireValuesTest {
    @Test
    void coordinatesInsideEventsAndPayloadsUseTheSameAdapter() {
        BlockPos pos = new BlockPos(2, 70, -3);
        DriverEvent event = new DriverEvent(11, "test", pos, Map.of("target", pos));
        List<?> decoded = (List<?>) JsonCodec.decode(WireValues.encode(List.of(event, Map.of("nested", pos))));
        Map<?, ?> e = (Map<?, ?>) decoded.get(0);
        assertEquals(Map.of("x", 2L, "y", 70L, "z", -3L), e.get("pos"));
        assertEquals(e.get("pos"), ((Map<?, ?>) e.get("data")).get("target"));
        assertEquals(e.get("pos"), ((Map<?, ?>) decoded.get(1)).get("nested"));
    }
}
