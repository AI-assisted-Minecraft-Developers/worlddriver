package net.magicterra.worlddriver.application;

import net.magicterra.worlddriver.api.UnloadedAreaException;
import net.magicterra.worlddriver.api.Positions;
import net.magicterra.worlddriver.bot.util.RegistryIds;
import net.magicterra.worlddriver.api.methods.ClientContracts.BlockScan;
import net.magicterra.worlddriver.api.methods.ClientContracts.ChunkRef;
import net.magicterra.worlddriver.api.methods.CommonTypes.Point;
import net.magicterra.worlddriver.api.methods.QueryContract.BlockRow;
import net.magicterra.worlddriver.api.methods.QueryContract.BlockRows;
import net.magicterra.worlddriver.api.methods.QueryContract.Blocks;
import net.magicterra.worlddriver.api.methods.QueryContract.Entities;
import net.magicterra.worlddriver.api.methods.QueryContract.EntityFilter;
import net.magicterra.worlddriver.api.methods.QueryContract.EntityRow;
import net.magicterra.worlddriver.api.methods.QueryContract.EntityRows;
import net.magicterra.worlddriver.api.methods.QueryContract.Query;
import net.magicterra.worlddriver.api.methods.QueryContract.Rows;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.magicterra.worlddriver.client.ClientDriverApi;
import net.minecraft.world.level.ChunkPos;

/**
 * {@code mc.query} on a client JVM with no server attached (connected to a remote dedicated
 * server): scans ClientLevel through the client impl instead of the server level, under the
 * server path's contract. Runs on the client thread, where the client scans run inline.
 */
final class ClientQueryFallback {
    private ClientQueryFallback() {}

    static Rows query(ClientDriverApi c, Query q) {
        return switch (q) {
            case Blocks b -> blocks(c, b);
            case Entities e -> entities(c, e);
        };
    }

    /** The client scan as the server's rows, refusing an unloaded area as the server does: ClientLevel reads
     *  those cells as air. */
    private static Rows blocks(ClientDriverApi c, Blocks b) {
        BlockQuery rules = BlockQuery.of(b);
        Point center = b.center().map(p -> new Point(p.x(), p.y(), p.z())).orElse(null);
        Set<String> ids = b.filter().type().map(Set::of).orElse(null);
        BlockScan scan = c.scanBlocks(rules.radius(), center == null ? null : center.x(),
                center == null ? null : center.y(), center == null ? null : center.z(), ids);
        List<ChunkRef> missing = scan.unloaded().orElse(List.of());
        if (!missing.isEmpty()) {
            List<ChunkPos> chunks = new ArrayList<>();
            for (ChunkRef m : missing) chunks.add(new ChunkPos(m.x(), m.z()));
            throw new UnloadedAreaException(Positions.blockPos(scan.center()), rules.radius(), chunks);
        }
        List<BlockRow> out = new ArrayList<>();
        for (var row : scan.blocks()) out.add(rules.row(row.pos(), row.type(), row.state()));
        return new BlockRows(out);
    }

    /** The client scan as the server's rows, with the filters the scan itself does not take applied here. The
     *  client knows what the server does not (hostile, maxHealth, distance) and is not told other entities' effects. */
    private static Rows entities(ClientDriverApi c, Entities e) {
        EntityFilter f = e.filter();
        Point center = e.center().map(p -> new Point(p.x(), p.y(), p.z())).orElse(null);
        List<EntityRow> scan = c.queryEntities(f.inRadius(), center == null ? null : center.x(),
                center == null ? null : center.y(), center == null ? null : center.z(), f.isHostile().orElse(null));
        String wantType = f.type().map(RegistryIds::qualified).orElse(null);
        List<EntityRow> out = new ArrayList<>();
        for (EntityRow row : scan) {
            if (wantType != null && !row.type().orElseThrow().equals(wantType)) continue;
            // The client scan puts health on exactly the LivingEntity rows.
            if (f.isLiving().isPresent() && f.isLiving().get() != row.health().isPresent()) continue;
            out.add(QueryApi.select(row, e.select()));
        }
        return new EntityRows(out);
    }
}
