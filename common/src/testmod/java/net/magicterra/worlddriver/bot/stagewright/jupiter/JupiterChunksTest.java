package net.magicterra.worlddriver.bot.stagewright.jupiter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.UUID;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Plot;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.stagewright.test.methods.ChunksContracts;
import net.magicterra.stagewright.test.methods.TestQueriesBlockingApi;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.animal.Pig;
import org.junit.jupiter.api.Test;

/** mc.test.chunks: an entity goes out with its chunk and comes back with it, the same entity. */
@Scene("wd.jupiterSmoke")
class JupiterChunksTest {

    @Test
    void anEntityOutlivesItsChunkLeavingMemory(Game game, Plot plot) {
        BlockPos at = plot.at(4, 1, 4);
        UUID pig = game.onServer(server -> {
            Pig p = EntityType.PIG.spawn(server.overworld(), at, MobSpawnType.COMMAND);
            p.setNoAi(true);
            p.setNoGravity(true);
            p.setPersistenceRequired();
            p.setCustomName(Component.literal("wilbur"));
            return p.getUUID();
        });
        BlockPosition pos = new BlockPosition(at.getX(), at.getY(), at.getZ());
        TestQueriesBlockingApi queries = game.api(TestQueriesBlockingApi::of);

        var unloaded = assertInstanceOf(ChunksContracts.Unloaded.class,
                queries.chunks(new ChunksContracts.Unload(pos, Optional.empty(), 400)));
        assertTrue(unloaded.done(), "unloaded: " + unloaded.heldBy().orElse(""));
        assertNull(game.onServer(server -> server.overworld().getEntity(pig)), "the pig left memory with its chunk");

        var reloaded = assertInstanceOf(ChunksContracts.Reloaded.class,
                queries.chunks(new ChunksContracts.Reload(pos, Optional.empty(), 400)));
        assertTrue(reloaded.done(), "reloaded");
        assertTrue(reloaded.entities() >= 1, reloaded.entities() + " entities in the chunk");
        String name = game.onServer(server -> {
            Entity back = server.overworld().getEntity(pig);
            return back == null ? null : back.getCustomName().getString();
        });
        assertEquals("wilbur", name, "the same pig came back");
    }
}
