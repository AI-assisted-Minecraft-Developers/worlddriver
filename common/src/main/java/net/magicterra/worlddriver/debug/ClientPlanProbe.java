package net.magicterra.worlddriver.debug;

import net.magicterra.worlddriver.api.game.Positions;
import net.magicterra.worlddriver.api.methods.debug.DebugContracts.PlanParams;
import net.magicterra.worlddriver.api.methods.debug.DebugContracts.PlanReply;
import net.magicterra.worlddriver.api.schema.ApiError;
import net.magicterra.worlddriver.api.schema.ApiException;
import net.magicterra.worlddriver.bot.rules.SearchScope;
import net.magicterra.worlddriver.bot.world.ClientWorldView;
import net.magicterra.worlddriver.game.ui.ClientWorld;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/** {@code mc.debug.plan} for {@code self}: the client's player in the client's world. Client thread only. */
final class ClientPlanProbe {
    private ClientPlanProbe() {}

    static PlanReply plan(PlanParams p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            throw ApiException.of(ApiError.Code.CAPABILITY_UNAVAILABLE, "no world is open on this client");
        }
        BlockPos start = p.from().map(Positions::blockPos).orElse(mc.player.blockPosition());
        ClientWorldView world = new ClientWorldView(ClientWorld::level, ClientWorld::player);
        int selfId = mc.player.getId();
        return PlanProbeTool.run(world, start, PlanProbeTool.goal(p, mc.player), p,
                (s, g, prof) -> SearchScope.gather(mc.level, selfId, s, g, prof));
    }
}
