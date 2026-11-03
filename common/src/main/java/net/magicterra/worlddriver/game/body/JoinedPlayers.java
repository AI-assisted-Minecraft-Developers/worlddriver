package net.magicterra.worlddriver.game.body;

import com.mojang.authlib.GameProfile;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.magicterra.worlddriver.config.Mod;
import net.magicterra.worlddriver.game.observe.CellText;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;

/**
 * Where bot players come from: each a {@link BotBody} that <b>joins the server</b> instead of pretending to be on it.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Every gap the playthrough ladder found in the headless agent has the same shape: vanilla does the thing in a
 * method this player never runs. Blocks dropped nothing ({@code Level#destroyBlock}'s flag), drops were never picked up
 * (the entity-touch loop in {@code Player.aiStep}), crafting tables would not open ({@code openMenu} returning empty),
 * and no advancement was ever awarded (no listener on {@code inventoryMenu}, and nothing calling
 * {@code broadcastChanges}). Each was fixed by hand-copying one more piece of vanilla into the server-side player's
 * mirror of {@code Player.tick()} — and that list only grew, because it was a re-implementation maintained by
 * discovering what was missing.
 *
 * <p>A {@code FakePlayer} is a {@code ServerPlayer} that was never <i>placed</i>. The join path —
 * {@code PlayerList.placeNewPlayer} — is what attaches the inventory-menu listener that awards advancements, puts the
 * player in {@code ServerLevel.players()} so the level keeps ticking and mob AI can see it, registers it with the
 * {@code ChunkMap} so it loads the chunks it walks into, and fires the loader's login event that modpack mods hook.
 * None of that is reachable by copying methods; it is reachable by joining.
 *
 * <h2>Joined first, then ticked by vanilla</h2>
 *
 * <p>This landed in two halves, so that no gate had to tell "the player is real now" apart from "movement moved". The
 * first joined the player and left its {@code tick()} empty while the driver still integrated locomotion by hand. The
 * second is {@link BotBody#pump}: each step runs vanilla's player tick on the input a client writes. Since 2026-11-03
 * the end of each server tick runs the same update for a bot no driver stepped that tick ({@link #endOfServerTick}).
 *
 * <h2>The only server-side player</h2>
 *
 * {@link #BOTS} hands out these and nothing else. This player was first armed with
 * {@code -Dworlddriver.realPlayerBodies=true} in place of each loader's fake player, which turned the dogfood scenes
 * and the journey ladder into an A/B harness for the two player kinds. Once every gate and every ladder topology ran on
 * this one, the switch and the fake players were deleted.
 *
 * <h2>The trap in the connection</h2>
 *
 * <p>The fake players this replaced swallowed outbound packets by overriding {@code send} on their <i>packet
 * listener</i>. That does not survive here: {@code placeNewPlayer} constructs vanilla's own
 * {@code ServerGamePacketListenerImpl} and installs it, so the listener is not ours to override. The swallow has to
 * move down to the {@link Connection} ({@link SilentConnection}), and it has to also report {@code isConnected() ==
 * true} — a disconnected {@code Connection} does not drop packets, it queues them in {@code pendingActions} forever,
 * which is a leak that looks like nothing at all.
 */
public final class JoinedPlayers {

    /**
     * Where the bots' players come from. Common rather than per loader because joining is vanilla: nothing here needs a
     * loader API, which is also what lets another mod create a driven player the same way on either loader. Until
     * 2026-09-14 each loader installed a fake-player factory in its place (NeoForge's {@code FakePlayerFactory}, and a
     * Fabric copy of it); this was a separate {@code ServerAvatarBodies} holder until the layering rework.
     */
    public static final JoinedPlayers BOTS = new JoinedPlayers();

    /**
     * The joined players by name, whatever level each is in. Not by level: the level a player is asked for is only
     * where a new one starts. The join moves a returning player to the dimension its save names, and a portal or a
     * teleport moves it later without leaving the player list; a map keyed by the asked-for level filed such a player
     * under a dimension it was not in, and the next ask for its real one joined a second player with its name.
     */
    private final Map<String, BotBody> byName = new ConcurrentHashMap<>();

    /** Whether {@code connection} is a bot's, which has no client and throws every packet away. */
    public static boolean isSilent(Connection connection) {
        return connection instanceof SilentConnection;
    }

    /**
     * The player for {@code profile} on {@code level}'s server: the one already joined under that name, in whatever
     * dimension it now is, or a new one joined starting in {@code level}. A caller that needs it in {@code level} moves
     * it there.
     */
    public ServerPlayer unique(ServerLevel level, GameProfile profile) {
        return body(level, profile);
    }

    /**
     * The cached player for this profile, re-joining when the last one left.
     *
     * <p>Not {@code computeIfAbsent}: a player that has left the player list is gone, and handing it back would drive a
     * corpse. Sweeping the departed here, rather than from a removal callback, keeps the cache self-healing — players
     * are only ever created on the server thread, so the sweep-then-put is not racing anything. Departed, not removed:
     * a player twenty ticks dead has been removed from its level but is still on the list, waiting to respawn, and a
     * join under its name would put a second player with its UUID beside it.
     *
     * <p>The sweep is not optional. Scenes create players under names unique within the process, so a stale entry is
     * never overwritten by a re-join; it just stays, and the map was the last thing holding each departed
     * {@code ServerPlayer} with its advancements, stats and inventory. Measured on the dedicated Fabric suite: 255
     * players retained after 300 scenes, and the 2 GB server heap ran out around scene 290 in two of three runs.
     */
    private BotBody body(ServerLevel level, GameProfile profile) {
        // A player of a server that has stopped (an integrated server's earlier world) is off its list with it; the
        // server check is for one whose removal never ran.
        byName.values()
                .removeIf(b -> b.getServer() != level.getServer()
                        || b.getServer().getPlayerList().getPlayer(b.getUUID()) != b);
        BotBody cached = byName.get(profile.getName());
        if (cached != null) {
            return cached;
        }
        BotBody fresh = join(level, profile);
        byName.put(profile.getName(), fresh);
        // A respawn replaces the player; the name now means the one that replaced it.
        fresh.onReplaced(next -> byName.put(profile.getName(), next));
        return fresh;
    }

    /**
     * The end of a server tick, after the drivers have stepped theirs: the update of each bot player that none stepped
     * this tick ({@link BotBody#endOfServerTick}). From the player list, not from this cache, so a player some other
     * mod joined as a bot is updated too.
     */
    public static void endOfServerTick(MinecraftServer server) {
        for (ServerPlayer p : List.copyOf(server.getPlayerList().getPlayers())) {
            if (p instanceof BotBody bot) {
                // As BotTicker guards each driver: one bot's update that throws must not take the tick down.
                try {
                    bot.endOfServerTick();
                } catch (RuntimeException e) {
                    Mod.LOG.error(
                            "[realbody] {}'s update failed",
                            bot.getGameProfile().getName(),
                            e);
                }
            }
        }
    }

    private static BotBody join(ServerLevel level, GameProfile profile) {
        BotBody body = new BotBody(level, profile, ClientInformation.createDefault());
        // The whole point of the class. Everything a fake player is missing is installed here:
        // the inventory-menu listener (advancements), player-list membership (the level ticks,
        // mobs can see it), ChunkMap registration (it loads what it walks into), and the loader's
        // login event.
        try {
            level.getServer()
                    .getPlayerList()
                    .placeNewPlayer(new SilentConnection(), body, CommonListenerCookie.createInitial(profile, false));
        } catch (RuntimeException e) {
            // The join path runs a lot of code that assumes a socket. Log the frame that wanted
            // one — the harness only surfaces an exception's message, and "channel is null" names
            // netty rather than the caller that reached for it.
            Mod.LOG.error("[realbody] placeNewPlayer failed for {}", profile.getName(), e);
            throw e;
        }
        // `players=` is half of a matched pair — see BotBody.remove() for the other half and for
        // why the vanilla log cannot answer this question. Stamping the list size on both edges is
        // what makes residue readable at every moment of a run rather than only where a scene
        // happened to ask: peak = max over the joins, leak = the last value at shutdown.
        // body.level(), not level: placeNewPlayer reads the player's save and moves a returning player to the
        // dimension it was saved in, so the level asked for is only where a new player starts.
        Mod.LOG.info(
                "[realbody] {} joined {} at {} (players={})",
                profile.getName(),
                body.level().dimension().location(),
                CellText.of(body.blockPosition()),
                level.getServer().getPlayerList().getPlayerCount());
        return body;
    }
}
