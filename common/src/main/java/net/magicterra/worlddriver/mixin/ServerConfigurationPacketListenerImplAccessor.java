package net.magicterra.worlddriver.mixin;

import java.util.Queue;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * The configuration listener's task queue, for a bot's configuration ({@code game/body/BotConfiguration}): it runs the
 * mods' tasks through vanilla's own queue, so a task finishes the way it does for a client, by the listener's
 * {@code finishCurrentTask} starting the next.
 */
@Mixin(ServerConfigurationPacketListenerImpl.class)
public interface ServerConfigurationPacketListenerImplAccessor {

    /** The tasks not yet started, in order. */
    @Accessor("configurationTasks")
    Queue<ConfigurationTask> worlddriver$tasks();

    /** The task started and not yet finished, or null. */
    @Accessor("currentTask")
    @Nullable
    ConfigurationTask worlddriver$currentTask();

    /** Set to null to give up on the current task, which the next start would otherwise refuse. */
    @Accessor("currentTask")
    void worlddriver$setCurrentTask(@Nullable ConfigurationTask task);

    /** Start the next task in the queue, if any. */
    @Invoker("startNextTask")
    void worlddriver$startNextTask();
}
