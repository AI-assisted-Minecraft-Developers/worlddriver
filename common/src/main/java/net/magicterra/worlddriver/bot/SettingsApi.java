package net.magicterra.worlddriver.bot;

import java.util.concurrent.CompletionStage;

import net.magicterra.worlddriver.api.call.CallContext;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.api.registry.Spec;
import net.magicterra.worlddriver.bot.SettingsContracts.SettingsOp;
import net.magicterra.worlddriver.bot.SettingsContracts.SettingsReply;

/** {@code mc.bot.setting} for Java callers; see {@link SettingsContracts} for what each op does. */
public interface SettingsApi {

    /** A caller that goes through {@code registry}, checked as a request is. */
    static SettingsStubApi of(ApiRegistry registry) { return SettingsApiBinding.calls(registry); }

    @Spec(SettingsContracts.class)
    CompletionStage<SettingsReply> setting(SettingsOp op, CallContext ctx);
}
