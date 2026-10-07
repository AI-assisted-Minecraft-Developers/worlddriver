package net.magicterra.worlddriver.api;

import java.util.Map;
import net.magicterra.worlddriver.protocol.JsonCodec;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WireValuesTest {
    @Test
    void coordinatesInsideAPayloadAreAdapted() {
        BlockPos pos = new BlockPos(2, 70, -3);
        Map<?, ?> decoded = (Map<?, ?>) JsonCodec.decode(WireValues.encode(Map.of("nested", pos)));
        assertEquals(Map.of("x", 2L, "y", 70L, "z", -3L), decoded.get("nested"));
    }
}
