package net.magicterra.worlddriver.game.observe;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import net.magicterra.worlddriver.api.methods.bot.GatherContracts;
import org.junit.jupiter.api.Test;

/** The game layer may not import the api, so the farm verb's {@code Crop} enum and the crop table are kept equal here. */
class CropsTest {

    @Test
    void theFarmVerbsCropsAreTheCropTablesBlocks() {
        Set<String> declared = Arrays.stream(GatherContracts.Crop.values()).map(GatherContracts.Crop::wireName)
                .collect(Collectors.toSet());
        Set<String> known = Crops.ALL.stream().map(Crops.Crop::block).collect(Collectors.toSet());
        assertEquals(declared, known);
    }
}
