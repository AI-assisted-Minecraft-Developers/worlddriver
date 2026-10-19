package net.magicterra.worlddriver.game.observe;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import net.magicterra.worlddriver.config.GameConfig;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The foothold a floating bot may place to climb a low bank: a falling block would drop out from
 * under it, and mud, though not a full cube, has a top it stands on.
 */
class BuildBlocksTest {
    private Set<String> whitelistBefore;

    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void keep() { whitelistBefore = GameConfig.buildBlockWhitelist; GameConfig.buildBlockWhitelist = Set.of(); }

    @AfterEach
    void restore() { GameConfig.buildBlockWhitelist = whitelistBefore; }

    @Test
    void fallingBlocksAreNoFoothold() {
        assertFalse(BuildBlocks.isUsableBuildBlock(Blocks.SAND), "sand falls");
        assertFalse(BuildBlocks.isUsableBuildBlock(Blocks.GRAVEL), "gravel falls");
    }

    @Test
    void mudIsAFoothold() {
        assertTrue(BuildBlocks.isUsableBuildBlock(Blocks.MUD), "mud is standable and does not fall");
    }
}
