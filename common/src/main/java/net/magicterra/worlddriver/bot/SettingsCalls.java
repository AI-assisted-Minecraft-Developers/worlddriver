package net.magicterra.worlddriver.bot;

import java.util.concurrent.CompletionStage;

import net.magicterra.worlddriver.api.Api;
import net.magicterra.worlddriver.api.CallContext;
import net.magicterra.worlddriver.api.DriverApi;
import net.magicterra.worlddriver.bot.SettingsContract.SettingsOp;
import net.magicterra.worlddriver.bot.SettingsContract.SettingsReply;

/** {@code mc.bot.setting} for Java callers; see {@link SettingsContract} for what each op does. */
public interface SettingsCalls {

    /** A caller that goes through {@code api}'s registry, checked as a request is. */
    static SettingsCalls of(DriverApi api) { return SettingsCallsBinding.calls(api); }

    @Api(SettingsContract.class)
    CompletionStage<SettingsReply> setting(SettingsOp op, CallContext ctx);
}
