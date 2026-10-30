package net.magicterra.worlddriver.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import net.magicterra.worlddriver.api.game.Positions;
import net.magicterra.worlddriver.api.game.UnloadedAreaException;
import net.magicterra.worlddriver.api.methods.query.QueryContracts;
import net.magicterra.worlddriver.api.methods.query.QueryContracts.BlockField;
import net.magicterra.worlddriver.api.methods.query.QueryContracts.BlockRow;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.game.observe.BlockMatch;
import net.magicterra.worlddriver.game.observe.LoadedCube;
import net.magicterra.worlddriver.game.observe.RegistryIds;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * {@code mc.query q=blocks}: every non-air cell of a cube, as rows.
 *
 * <p>The whole cube is read in one server-thread task, so the radius is held to the cell budget {@code mc.action.fill}
 * and {@code mc.world.snapshot} use, and only chunks that are already loaded are read. A read-only query that loaded or
 * generated chunks would stall the tick it runs in; instead an area that touches an unloaded chunk is refused with
 * {@link UnloadedAreaException}.
 */
final class BlockQuery {
    /** The largest radius whose {@code (2r+1)^3} cube fits the cell budget: 31^3 = 29,791. */
    static final int MAX_RADIUS = QueryContracts.MAX_BLOCK_RADIUS;

    private final int radius;
    private final Predicate<BlockState> match;
    private final Optional<List<BlockField>> select;

    private BlockQuery(int radius, Predicate<BlockState> match, Optional<List<BlockField>> select) {
        this.radius = radius;
        this.match = match;
        this.select = select;
    }

    /** The contract has bounded the radius and the select keys; a {@code #tag} filter is resolved here. */
    static BlockQuery of(QueryContracts.Blocks p) {
        return new BlockQuery(
                p.filter().inRadius(), p.filter().type().map(BlockMatch::of).orElse(null), p.select());
    }

    int radius() {
        return radius;
    }

    /** One row with only the selected fields, so the client fallback projects like the server. */
    BlockRow row(BlockPosition pos, String type, Optional<Map<String, String>> state) {
        return new BlockRow(
                keep(BlockField.POS, pos), keep(BlockField.TYPE, type), state.filter(s -> wants(BlockField.STATE)));
    }

    private <T> Optional<T> keep(BlockField f, T value) {
        return wants(f) ? Optional.of(value) : Optional.empty();
    }

    private boolean wants(BlockField f) {
        return select.isEmpty() || select.get().isEmpty() || select.get().contains(f);
    }

    /** Runs on the server thread, reading loaded chunks only; see {@link LoadedCube}. */
    List<BlockRow> scan(ServerLevel level, BlockPos center) {
        List<BlockRow> out = new ArrayList<>();
        List<ChunkPos> unloaded = LoadedCube.nonAir(level, center, radius, (bp, st) -> {
            if (match == null || match.test(st)) {
                out.add(row(Positions.position(bp), RegistryIds.blockId(st), RegistryIds.stateProperties(st)));
            }
        });
        if (!unloaded.isEmpty()) {
            throw new UnloadedAreaException(center, radius, unloaded);
        }
        return out;
    }
}
