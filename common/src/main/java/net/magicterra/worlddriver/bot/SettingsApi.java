package net.magicterra.worlddriver.bot;

import java.util.concurrent.CompletionStage;

import net.magicterra.worlddriver.api.CallContext;
import net.magicterra.worlddriver.api.DriverApi;
import net.magicterra.worlddriver.api.Spec;
import net.magicterra.worlddriver.bot.SettingsContracts.SettingsOp;
import net.magicterra.worlddriver.bot.SettingsContracts.SettingsReply;

/** {@code mc.bot.setting} for Java callers; see {@link SettingsContracts} for what each op does. */
public interface SettingsApi {

    /** A caller that goes through {@code api}'s registry, checked as a request is. */
    static SettingsApi of(DriverApi api) { return SettingsApiBinding.calls(api); }

    @Spec(SettingsContracts.class)
    CompletionStage<SettingsReply> setting(SettingsOp op, CallContext ctx);
}
