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

    /** Run {@code cmd} (no leading slash) as an operator. */
    public static Outcome run(MinecraftServer s, String cmd) {
        // Anchor the source on a real player when one is connected so @s / @p
        // resolve to them (and the command runs *in their dimension*). Without
        // this, the server-console source has no entity, so @s silently matches
        // 0 selectors and runCommand returns ok:true for a no-op — exactly the
        // footgun that drove this fix. Falls back to the server console on
        // dedicated server / before-anyone-joined.
        ServerPlayer anchor = s.getPlayerList().getPlayers().isEmpty()
                ? null : s.getPlayerList().getPlayers().get(0);
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
        CommandSourceStack src = ((anchor != null)
                ? anchor.createCommandSourceStack()
                : s.createCommandSourceStack())
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
