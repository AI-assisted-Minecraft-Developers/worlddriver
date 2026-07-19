package net.magicterra.agent.bot.movement;

import net.magicterra.agent.bot.BotConfig;
import net.magicterra.agent.bot.Goal;
import net.magicterra.agent.bot.debug.BotLevelHolder;
import net.magicterra.agent.bot.movement.PathSmoothing.SmoothResult;
import net.magicterra.agent.bot.pathfinder.Capability;
import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.pathfinder.PathFinder;
import net.magicterra.agent.bot.pathfinder.PathTrace;
import net.magicterra.agent.bot.pathfinder.PathTraceHolder;
import net.magicterra.agent.bot.pathfinder.SearchProfile;
import net.magicterra.agent.bot.pathfinder.Constraint;
import net.magicterra.agent.bot.pathfinder.constraints.NoBreak;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.magicterra.agent.bot.world.SurvivalMath;
import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static net.magicterra.agent.bot.movement.ClutchController.CLUTCH;
import static net.magicterra.agent.bot.movement.PathSmoothing.*;
import static net.magicterra.agent.bot.util.BotInteract.*;
import static net.magicterra.agent.bot.util.BotUtil.*;
import java.util.Locale;
import static net.magicterra.agent.AgentDriverCommon.LOG;
import static net.magicterra.agent.bot.movement.WalkerConstants.*;
import static net.magicterra.agent.bot.movement.WalkerGeometry.*;


/**
 * Shared per-tick locals of the pre-split {@code Walker#tickInner} body, carried across
 * the WalkerTick* phase classes. A fresh instance is built every tick by the driver in
 * {@link Walker#tickInner}; nothing here survives the tick — cross-tick state stays in
 * {@link Walker} fields. Field names/types are exactly the original method locals.
 */
final class WalkerTickCtx {
    Player p;   // decl at pre-split line 646
    BlockPos foot;   // decl at pre-split line 719
    BlockPos searchFoot;   // decl at pre-split line 729
    double d;   // decl at pre-split line 842
    boolean offPath;   // decl at pre-split line 878
    boolean breakingEdge;   // decl at pre-split line 902
    boolean wedged;   // decl at pre-split line 906
    boolean fellOffPath;   // decl at pre-split line 1044
    boolean fellBelowRoute;   // decl at pre-split line 1068
    Move.Edge edge;   // decl at pre-split line 2349
    BlockPos wp;   // decl at pre-split line 3281
    boolean parkourEdge;   // decl at pre-split line 3283
    boolean bridging;   // decl at pre-split line 3289
    boolean placingEdge;   // decl at pre-split line 3296
    boolean steppingOffFall;   // decl at pre-split line 3481
    boolean steppingOffWaterFall;   // decl at pre-split line 3489
    boolean aimAtWaypoint;   // decl at pre-split line 3520
    boolean reCentre;   // decl at pre-split line 3546
    String aimSrc;   // decl at pre-split line 3576
    float descentNodeYaw;   // decl at pre-split line 3822
    boolean dryDescent;   // decl at pre-split line 3833
    boolean flatWaterTrend;   // decl at pre-split line 3858
    boolean trendCam;   // decl at pre-split line 3861
    float aimYaw;   // decl at pre-split line 3950
    boolean diveUnderCap;   // decl at pre-split line 4052
    boolean diving;   // decl at pre-split line 4072
    double stepColDx;   // decl at pre-split line 4100
    double stepColDz;   // decl at pre-split line 4101
    boolean stepUpFreeze;   // decl at pre-split line 4158
    boolean pivotForStepUp;   // decl at pre-split line 4165
    boolean descendBrake;   // decl at pre-split line 4180
}
