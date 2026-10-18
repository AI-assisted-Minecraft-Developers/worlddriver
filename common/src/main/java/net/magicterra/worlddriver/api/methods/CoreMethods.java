package net.magicterra.worlddriver.api.methods;

import java.util.List;

import net.magicterra.worlddriver.api.schema.MethodSpec;
import net.magicterra.worlddriver.api.methods.action.ActionContracts;
import net.magicterra.worlddriver.api.methods.bot.BuildContracts;
import net.magicterra.worlddriver.api.methods.bot.ClearAreaContracts;
import net.magicterra.worlddriver.api.methods.bot.CombatContracts;
import net.magicterra.worlddriver.api.methods.bot.GatherContracts;
import net.magicterra.worlddriver.api.methods.bot.GotoContracts;
import net.magicterra.worlddriver.api.methods.bot.MoveContracts;
import net.magicterra.worlddriver.api.methods.client.ClientContracts;
import net.magicterra.worlddriver.api.methods.control.ControlContracts;
import net.magicterra.worlddriver.api.methods.event.EventContracts;
import net.magicterra.worlddriver.api.methods.interact.InteractContracts;
import net.magicterra.worlddriver.api.methods.observe.ObserveContracts;
import net.magicterra.worlddriver.api.methods.query.QueryContracts;
import net.magicterra.worlddriver.api.methods.recipe.RecipeContracts;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts;
import net.magicterra.worlddriver.api.methods.script.ScriptContracts;
import net.magicterra.worlddriver.api.methods.system.SystemContracts;
import net.magicterra.worlddriver.api.methods.wait.WaitContracts;
import net.magicterra.worlddriver.api.methods.world.WorldContracts;
import net.magicterra.worlddriver.api.methods.setting.SettingsContracts;

/**
 * Every core method's description, in the order they are documented. The artifact generator loads this
 * class on a classpath with no Minecraft, which is what proves the contracts do not reach into the game.
 * The application binds a handler to each of these and registers them as one batch.
 */
public final class CoreMethods {
    private CoreMethods() {}

    public static List<MethodSpec<?, ?>> all() {
        return List.of(
                GotoContracts.SPEC,
                ClearAreaContracts.SPEC,
                GatherContracts.MINE,
                GatherContracts.CRAFT,
                GatherContracts.SMELT,
                GatherContracts.FARM,
                BuildContracts.BUILD,
                BuildContracts.CONSTRUCT,
                BuildContracts.BUNKER,
                BuildContracts.ESCAPE,
                MoveContracts.FOLLOW,
                MoveContracts.EXPLORE,
                MoveContracts.RUN_AWAY,
                MoveContracts.SLEEP,
                MoveContracts.ELYTRA_FLY,
                CombatContracts.SPEC,
                InteractContracts.LOOK_AT,
                InteractContracts.HOLD_ITEM,
                InteractContracts.USE_ITEM,
                InteractContracts.ATTACK_ENTITY,
                InteractContracts.EQUIP,
                ControlContracts.STATUS,
                ControlContracts.CANCEL,
                ControlContracts.WAYPOINT_SPEC,
                SettingsContracts.SPEC,
                WaitContracts.RESULT,
                ScriptContracts.EVAL,
                ScriptContracts.SKILL,
                ScriptContracts.PLAYBOOK,
                EventContracts.CURSOR,
                EventContracts.EVENTS_SINCE,
                EventContracts.EVENTS,
                WaitContracts.EVENT,
                WaitContracts.WORLD_READY,
                WaitContracts.CONDITION,
                SystemContracts.VERSION,
                SystemContracts.TEST_ORIGIN,
                SystemContracts.WAIT_TICKS,
                ObserveContracts.PLAYER,
                ObserveContracts.THREATS,
                ObserveContracts.BOSS_SPEC,
                ObserveContracts.SCENE,
                ObserveContracts.MAP,
                QueryContracts.SPEC,
                WorldContracts.BLOCK,
                WorldContracts.SNAPSHOT,
                WorldContracts.RESTORE,
                ActionContracts.RUN_COMMAND,
                ActionContracts.FILL,
                ActionContracts.PLACE_MANY,
                RecipeContracts.LOOKUP,
                RecipeContracts.RESOLVE,
                RecipeContracts.ACQUIRE,
                ScreenContracts.CONTAINER,
                ScreenContracts.INFO,
                ScreenContracts.TREE,
                ScreenContracts.CLOSE,
                ScreenContracts.CLICK,
                ScreenContracts.MOUSE_MOVE,
                ScreenContracts.SLOT_CLICK,
                ScreenContracts.SET_HOTBAR_SLOT,
                ScreenContracts.TYPE_TEXT,
                ScreenContracts.REPLACE_TEXT,
                ScreenContracts.SLIDER,
                ScreenContracts.KEY,
                ScreenContracts.KEYBIND,
                ClientContracts.PLAYER,
                ClientContracts.SCENE,
                ClientContracts.BLOCKS,
                ClientContracts.CHAT_SEND,
                ClientContracts.CHAT_HISTORY,
                ClientContracts.OVERLAYS,
                ClientContracts.SCREENSHOT);
    }
}
