package net.magicterra.worlddriver.api.methods;

import java.util.List;

import net.magicterra.worlddriver.api.contract.MethodSpec;

/**
 * Every core method's description, in the order they are documented. Pure: the artifact generator
 * loads this class on a classpath with no Minecraft, which is what proves the contract layer does not
 * reach into the game. The application binds a handler to each of these and registers them as one batch.
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
                WaitContracts.CONDITION);
    }
}
