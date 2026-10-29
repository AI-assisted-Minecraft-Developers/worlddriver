package net.magicterra.worlddriver.game.edit;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.commands.CommandResultCallback;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Server commands run through Brigadier with operator-level permission. Server thread only. */
public final class ServerCommands {
    private ServerCommands() {}

    /**
     * What one command did. {@code ok} is whether it dispatched ({@code error} says why not);
     * {@code success}/{@code value} come from the Brigadier result callback ({@code execute if entity} →
     * match count); {@code feedback} collects the chat lines the command would have shown ({@code data get}
     * → the NBT text). {@code ok:true, success:false} means the command dispatched but reported failure
     * (e.g. selector matched nothing).
     */
    public record Outcome(boolean ok, String error, boolean success, int value, List<String> feedback) {}

    /**
     * Run {@code cmd} (no leading slash) as an operator: as the server, at the overworld spawn, or as {@code as}
     * when it is not null.
     *
     * <p>The server is the default, not whichever player happens to be first in the list. That player is often a
     * bot: a permission mod (LuckPerms) then gates every command node by the bot's own permissions, which
     * {@code withPermission(4)} does not lift, and under concurrent tests {@code @s}/{@code @p} resolved to some
     * other test's bot. A caller that needs {@code @s} names the player.
     */
    public static Outcome run(MinecraftServer s, String cmd, ServerPlayer as) {
        // Collect feedback instead of suppressing it: parse errors and command
        // output (e.g. `data get`'s NBT text) land in `feedback` for the caller.
        List<String> feedback = new ArrayList<>();
        CommandSource collector = new CommandSource() {
            @Override
            public void sendSystemMessage(Component message) {
                feedback.add(message.getString());
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };
        // Brigadier reports the command's own outcome through the result
        // callback (1.20.2+ execution rework made performPrefixedCommand void).
        boolean[] cbState = {false, false}; // [fired, success]
        int[] cbValue = {0};
        CommandResultCallback callback = (success, result) -> {
            cbState[0] = true;
            cbState[1] = success;
            cbValue[0] = result;
        };
        // The server's own source carries the server as its CommandSource, which prints to the log rather than
        // collecting; withSource swaps in the collector for either kind.
        CommandSourceStack src = (as != null ? as.createCommandSourceStack() : s.createCommandSourceStack())
                .withSource(collector)
                .withPermission(4)
                .withCallback(callback);
        boolean ok;
        String err = null;
        try {
            s.getCommands().performPrefixedCommand(src, cmd);
            ok = true;
        } catch (Throwable t) {
            ok = false;
            err = t.getMessage() == null ? t.toString() : t.getMessage();
        }
        // A parse failure doesn't throw — Brigadier reports it via the source's
        // failure feedback with no callback. Treat "no callback fired" as failed
        // unless the command genuinely produced no result (rare; still ok:true).
        boolean success = ok && cbState[0] && cbState[1];
        return new Outcome(ok, err, success, cbValue[0], List.copyOf(feedback));
    }
}
