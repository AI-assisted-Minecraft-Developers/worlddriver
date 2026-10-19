package net.magicterra.worlddriver.api.methods.setting;

import java.util.List;
import java.util.Optional;

import com.google.gson.JsonObject;
import net.magicterra.worlddriver.api.schema.ApiTypes;
import net.magicterra.worlddriver.api.schema.annotation.Default;
import net.magicterra.worlddriver.api.schema.annotation.Doc;
import net.magicterra.worlddriver.api.schema.annotation.Length;
import net.magicterra.worlddriver.api.schema.annotation.OneOf;
import net.magicterra.worlddriver.api.schema.annotation.Range;
import net.magicterra.worlddriver.api.schema.annotation.Wire;
import net.magicterra.worlddriver.api.methods.setting.SettingsContracts.EntityTypeId;

/**
 * The bot facade's keys of {@code mc.bot.setting}, one component each. A component is named as the field that
 * holds the key, and {@code @Wire} gives the key where the two differ. {@link #DEFAULTS} holds every
 * default, and each field starts from it; a key with no field of its own is optional and has none.
 */
public record BotSettings(
        @Doc("True halts all bot processes (keys released); false resumes.")
        Optional<Boolean> paused,
        @Doc("Hold useItem on a food item while food≤threshold.")
        @Default("false") boolean autoEat,
        @Doc("Click Respawn on DeathScreen automatically.")
        @Default("false") boolean autoRespawn,
        @Doc("Keep a totem of undying in the offhand.")
        @Default("false") boolean autoTotem,
        @Doc("Raise a shield against melee/projectiles when threatened (needs a shield)")
        @Default("false") boolean autoShield,
        @Doc("Eat/use a healing item when HP below healHpThreshold.")
        @Default("false") boolean autoHeal,
        @Doc("Equip best armor/weapon when a fight starts.")
        @Default("false") boolean autoEquip,
        @Doc("Remaining-durability fraction below which mc.bot.equip reports an item in lowDurability.")
        @Default("0.1") @Range(min = 0, max = 1) double equipDurabilityThreshold,
        @Doc("Hold jump while submerged so the bot rises to the surface.")
        @Default("false") boolean autoSwim,
        @Doc("Swap to best hotbar tool when crosshair on a breakable block.")
        @Default("false") boolean autoTool,
        @Doc("Baritone BackfillProcess analogue; when idle, refills the cells the bot's own digs broke (never air "
                + "it only walked through)")
        @Default("false") boolean autoBackfill,
        @Doc("Side of the travel direction an even-width clearArea tunnel puts its extra column on.")
        @Default("left") @OneOf(name = "TunnelWidthBias", value = {"left", "right"}) String tunnelWidthBias,
        @Doc("Mc.observe.scene radius clamp; requests larger than this are truncated (truncated:true in the "
                + "response)")
        @Default("32") @Range(min = 4, max = 48) int sceneQueryMaxRadius,
        @Doc("Every goto/follow gets a default route.mobs berth (radius/rangedRadius/penalty from the three "
                + "settings below, no cluster) unless its route gives mobs or risk itself. Changes pathing "
                + "noticeably.")
        @Default("false") boolean avoidMobs,
        @Doc("Default route.mobs.radius: how far a melee mob's berth reaches.")
        @Wire("pathfinder.mobAvoidRadius") @Default("6.0") @Range(min = 0, max = 64) double mobAvoidRadius,
        @Doc("Default route.mobs.penalty: peak cost at the mob, ramping to 0 at the berth radius.")
        @Wire("pathfinder.mobAvoidPenalty") @Default("40.0") @Range(min = 0, max = 1000) double mobAvoidPenalty,
        @Doc("Default route.mobs.rangedRadius: the wider berth of a mob that shoots (skeleton/witch), so routes "
                + "keep more distance from it than from melee mobs.")
        @Default("16") @Range(min = 4, max = 48) int rangedAvoidRadius,
        @Doc("Cap on stored A* candidate nodes per search.")
        @Default("4000") @Range(min = 100, max = 200000) int pathDebugMaxNodes,
        @Doc("Cap on stored per-tick trajectory samples (ring buffer)")
        @Default("6000") @Range(min = 100, max = 200000) int pathDebugMaxSamples,
        @Doc("Auto-write a chart PNG on every goto terminal outcome (success and failure)")
        @Default("false") boolean pathChartAutoDump,
        @Doc("Per-tick compute slice, ms, of a route PREVIEW (mc.bot.goto plan:true); thinner than sliceMs so a "
                + "preview beside a walk does not steal the walk's frames.")
        @Wire("pathfinder.previewSliceMs") @Default("3") @Range(min = 1, max = 50) long pathfinderPreviewSliceMs,
        @Doc("Most line-of-sight rays one search may fire for route.sight; past it the search reruns without sight "
                + "(lastPath.sightBudgetExhausted)")
        @Default("4000") @Range(min = 100, max = 100000) int sightRaysPerSearch,
        @Doc("Y plane for goto{axis:true} (GoalAxis)")
        @Wire("pathfinder.axisHeight") @Default("120") @Range(min = -64, max = 320) int axisHeight,
        @Doc("Event types to SUPPRESS from the live push channel (e.g. [\"item.pickup\",\"chat.message\"]). ALL "
                + "events push by default; muted ones still record + are pullable via mc.wait.event. Whole-list "
                + "replace; [] un-mutes everything.")
        @Default("[]") @Length(max = 64) List<@Length(min = 1, max = 64) String> mutedEvents,
        @Doc("Entity type ids the bot stack never takes over: an order naming entity:<uuid> of one, or /worlddriver "
                + "mob on one, is refused. Whole-list replace; [] clears.")
        @Default("[]") @Length(max = 256) List<@EntityTypeId String> undrivableMobs,
        @Default("60") int respawnGraceTicks,
        Optional<Boolean> debugFly) {

    public static final BotSettings DEFAULTS =
            ApiTypes.record(BotSettings.class).decode(new JsonObject());
}
