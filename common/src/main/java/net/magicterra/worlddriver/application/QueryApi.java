package net.magicterra.worlddriver.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

import net.magicterra.worlddriver.api.ApiMethod;
import net.magicterra.worlddriver.api.CallContext;
import net.magicterra.worlddriver.api.ServerThreadHop;
import net.magicterra.worlddriver.api.methods.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.CommonTypes.Point;
import net.magicterra.worlddriver.api.methods.QueryContract;
import net.magicterra.worlddriver.api.methods.QueryContract.BlockRows;
import net.magicterra.worlddriver.api.methods.QueryContract.Blocks;
import net.magicterra.worlddriver.api.methods.QueryContract.Entities;
import net.magicterra.worlddriver.api.methods.QueryContract.EntityField;
import net.magicterra.worlddriver.api.methods.QueryContract.EntityFilter;
import net.magicterra.worlddriver.api.methods.QueryContract.EntityRow;
import net.magicterra.worlddriver.api.methods.QueryContract.EntityRows;
import net.magicterra.worlddriver.api.methods.QueryContract.Query;
import net.magicterra.worlddriver.api.methods.QueryContract.Rows;
import net.magicterra.worlddriver.bot.BotApi;
import net.magicterra.worlddriver.bot.BotHooks;
import net.magicterra.worlddriver.client.ClientDriverApi;
import net.magicterra.worlddriver.client.ClientHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;

/**
 * {@code mc.query}. The server scans its overworld on the server thread; a client JVM with no server attached
 * (joined to a remote server) scans its own world through {@link ClientQueryFallback}, on the client thread.
 */
final class QueryApi {
    private final DriverApplication app;

    QueryApi(DriverApplication app) { this.app = app; }

    ApiMethod<Query, Rows> method() {
        return new ApiMethod<>(QueryContract.SPEC, this::query);
    }

    private CompletionStage<Rows> query(Query q, CallContext ctx) {
        if (app.server == null) {
            BotApi bot = BotHooks.impl();
            ClientDriverApi c = ClientHooks.impl();
            if (bot != null && c != null) return bot.clientHop().submit(() -> ClientQueryFallback.query(c, q), ctx);
        }
        ServerLevel level = app.level();
        ServerThreadHop hop = app.serverHop();
        if (hop == null) throw new IllegalStateException("DriverApplication not attached to a server");
        return switch (q) {
            case Blocks b -> {
                BlockQuery blocks = BlockQuery.of(b);
                BlockPos center = b.center().map(ObserveApi::pos).orElse(DriverApplication.ORIGIN);
                yield hop.<Rows>submit(() -> new BlockRows(blocks.scan(level, center)), ctx);
            }
            case Entities e -> {
                BlockPos center = e.center().map(ObserveApi::pos).orElse(DriverApplication.ORIGIN);
                yield hop.<Rows>submit(() -> new EntityRows(entities(level, center, e)), ctx);
            }
        };
    }

    /** Server thread: every entity overlapping the cube of cells around the center cell. */
    private static List<EntityRow> entities(ServerLevel level, BlockPos center, Entities q) {
        EntityFilter f = q.filter();
        String wantType = f.type().map(QueryApi::namespaced).orElse(null);
        List<EntityRow> out = new ArrayList<>();
        for (Entity e : level.getEntities((Entity) null, new AABB(center).inflate(f.inRadius()))) {
            if (f.isHostile().isPresent() && f.isHostile().get() != (e instanceof Enemy)) continue;
            // is_living keeps health-delta assertions free of dropped items and XP orbs.
            if (f.isLiving().isPresent() && f.isLiving().get() != (e instanceof LivingEntity)) continue;
            String type = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
            if (wantType != null && !wantType.equals(type)) continue;
            LivingEntity le = e instanceof LivingEntity l ? l : null;
            BlockPos cell = e.blockPosition();
            out.add(select(new EntityRow(Optional.of(new Point(e.getX(), e.getY(), e.getZ())),
                    Optional.of(new BlockPosition(cell.getX(), cell.getY(), cell.getZ())), Optional.of(type),
                    Optional.of(e.getUUID().toString()), Optional.of(e.getId()),
                    le == null ? Optional.empty() : Optional.of((double) le.getHealth()),
                    le == null ? Optional.empty() : Optional.of(PlayerSnapshots.effects(le)),
                    Optional.empty(), Optional.empty(), Optional.empty()), q.select()));
        }
        return out;
    }

    /** An entity id as the filter compares it: a bare path is in the minecraft namespace. */
    static String namespaced(String id) {
        return id.contains(":") ? id : "minecraft:" + id;
    }

    /** {@code row} with only the selected fields; no selection keeps them all. */
    static EntityRow select(EntityRow r, Optional<List<EntityField>> select) {
        if (select.isEmpty() || select.get().isEmpty()) return r;
        List<EntityField> s = select.get();
        return new EntityRow(keep(s, EntityField.POS, r.pos()), keep(s, EntityField.BLOCK_POS, r.blockPos()),
                keep(s, EntityField.TYPE, r.type()), keep(s, EntityField.UUID, r.uuid()), keep(s, EntityField.ID, r.id()),
                keep(s, EntityField.HEALTH, r.health()), keep(s, EntityField.EFFECTS, r.effects()),
                keep(s, EntityField.HOSTILE, r.hostile()), keep(s, EntityField.MAX_HEALTH, r.maxHealth()),
                keep(s, EntityField.DISTANCE, r.distance()));
    }

    private static <T> Optional<T> keep(List<EntityField> select, EntityField f, Optional<T> v) {
        return select.contains(f) ? v : Optional.empty();
    }
}
