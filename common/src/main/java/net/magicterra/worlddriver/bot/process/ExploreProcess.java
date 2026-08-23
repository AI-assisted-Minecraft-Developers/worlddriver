package net.magicterra.worlddriver.bot.process;

import net.magicterra.worlddriver.bot.BotState;
import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.movement.Avatar;
import net.magicterra.worlddriver.bot.movement.Walker;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;

import java.util.HashSet;
import java.util.Set;

import static net.magicterra.worlddriver.bot.movement.ClutchController.CLUTCH;
import static net.magicterra.worlddriver.bot.util.BotInteract.*;
import static net.magicterra.worlddriver.bot.util.BotUtil.*;

public final class ExploreProcess implements BotProcess {
    private final int centerChunkX, centerChunkZ;
    private final int maxChunks;
    private final Set<Long> visited = new HashSet<>();
    private final Walker walker = new Walker("explore");
    private int visitedCount;
    private BlockPos currentChunkCenter;
    private int currentChunkX, currentChunkZ;

    public ExploreProcess(int centerBlockX, int centerBlockZ, int maxChunks) {
        this.centerChunkX = centerBlockX >> 4;
        this.centerChunkZ = centerBlockZ >> 4;
        this.maxChunks = maxChunks;
    }

    public String kind() { return "explore"; }
    public void attach(BotState st) {
        st.explore.active = true;
        st.explore.goal = "explore around chunk (" + centerChunkX + "," + centerChunkZ + ") max=" + maxChunks;
        st.explore.startedAtMs = System.currentTimeMillis();
        st.explore.lastError = null;
    }

    @Override public boolean tick(Avatar a, WorldView w, BotState st) {
        Player p = a.player();
        // Stamped for the same reason BackfillProcess/BuildProcess stamp theirs: `ProcessSlot
        // .snapshot()` emits lastError only `if (lastError != null)` and `attach` cleared it, so an
        // unstamped exit is not silence — it is the POSITIVE report "finished, no error". Every
        // other exit in this file already stamps; this one was the hole.
        if (p == null) { st.explore.lastError = "player vanished"; st.explore.reset(); return true; }
        if (visitedCount >= maxChunks) {
            st.explore.lastError = "done (visited=" + visitedCount + ")";
            st.explore.reset();
            return true;
        }
        if (currentChunkCenter == null) {
            Long next = pickNextChunk();
            if (next == null) {
                st.explore.lastError = "no more chunks to explore (visited=" + visitedCount + ")";
                st.explore.reset();
                return true;
            }
            currentChunkX = (int) (next >> 32);
            currentChunkZ = (int) (long) next;
            currentChunkCenter = new BlockPos(currentChunkX * 16 + 8, (int) Math.floor(p.getY()), currentChunkZ * 16 + 8);
            walker.setGoal(new Goal.XZ(currentChunkCenter.getX(), currentChunkCenter.getZ()));
            st.explore.target = currentChunkCenter;
        }
        Walker.Step s = walker.tick(a, w);
        st.explore.pathLen = walker.pathLen();
        st.explore.pathStep = walker.pathStep();
        if (s != Walker.Step.WALKING) {
            visited.add(chunkKey(currentChunkX, currentChunkZ));
            visitedCount++;
            currentChunkCenter = null;
        }
        return false;
    }

    /** Spiral search outward from (centerChunkX, centerChunkZ); skip already-visited. */
    private Long pickNextChunk() {
        for (int r = 0; r < 32; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int cx = centerChunkX + dx, cz = centerChunkZ + dz;
                    long key = chunkKey(cx, cz);
                    if (visited.contains(key)) continue;
                    return key;
                }
            }
        }
        return null;
    }

    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }
}
