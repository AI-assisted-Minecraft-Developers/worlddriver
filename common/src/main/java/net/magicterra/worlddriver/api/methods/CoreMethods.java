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
                ClearAreaContract.SPEC,
                WaitResultContract.SPEC);
    }
}
