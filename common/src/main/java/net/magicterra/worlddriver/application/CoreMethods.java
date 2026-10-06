package net.magicterra.worlddriver.application;

import java.util.List;

import net.magicterra.worlddriver.api.contract.MethodSpec;
import net.magicterra.worlddriver.api.methods.BuildContracts;
import net.magicterra.worlddriver.api.methods.ClearAreaContract;
import net.magicterra.worlddriver.api.methods.ClientContracts;
import net.magicterra.worlddriver.api.methods.CombatContract;
import net.magicterra.worlddriver.api.methods.ControlContracts;
import net.magicterra.worlddriver.api.methods.EventContracts;
import net.magicterra.worlddriver.api.methods.GatherContracts;
import net.magicterra.worlddriver.api.methods.GotoContract;
import net.magicterra.worlddriver.api.methods.HandContracts;
import net.magicterra.worlddriver.api.methods.MoveContracts;
import net.magicterra.worlddriver.api.methods.ObserveContracts;
import net.magicterra.worlddriver.api.methods.PlayerContract;
import net.magicterra.worlddriver.api.methods.QueryContract;
import net.magicterra.worlddriver.api.methods.RecipeContracts;
import net.magicterra.worlddriver.api.methods.ScreenContracts;
import net.magicterra.worlddriver.api.methods.ScriptContracts;
import net.magicterra.worlddriver.api.methods.SystemContracts;
import net.magicterra.worlddriver.api.methods.WaitContracts;
import net.magicterra.worlddriver.api.methods.WaitResultContract;
import net.magicterra.worlddriver.api.methods.WorldContracts;
import net.magicterra.worlddriver.bot.SettingsContract;

/**
 * Every core method's description, in the order they are documented. The artifact generator loads this
 * class on a classpath with no Minecraft, which is what proves the contracts do not reach into the game.
 * The application binds a handler to each of these and registers them as one batch. It sits here, not in
 * {@code api/methods}, because {@code mc.bot.setting}'s contract is built from the bot's settings table.
 */
public final class CoreMethods {
    private CoreMethods() {}

    public static List<MethodSpec<?, ?>> all() {
        return List.of(
                GotoContract.SPEC,
                ClearAreaContract.SPEC,
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
                CombatContract.SPEC,
                HandContracts.LOOK_AT,
                HandContracts.HOLD_ITEM,
                HandContracts.USE_ITEM,
                HandContracts.ATTACK_ENTITY,
                HandContracts.EQUIP,
                ControlContracts.STATUS,
                ControlContracts.CANCEL,
                ControlContracts.WAYPOINT_SPEC,
                SettingsContract.SPEC,
                WaitResultContract.SPEC,
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
                PlayerContract.SPEC,
                ObserveContracts.THREATS,
                ObserveContracts.BOSS_SPEC,
                ObserveContracts.SCENE,
                ObserveContracts.MAP,
                QueryContract.SPEC,
                WorldContracts.BLOCK,
                WorldContracts.SNAPSHOT,
                WorldContracts.RESTORE,
                WorldContracts.RUN_COMMAND,
                WorldContracts.FILL,
                WorldContracts.PLACE_MANY,
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
