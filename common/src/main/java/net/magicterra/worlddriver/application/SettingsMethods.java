package net.magicterra.worlddriver.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

import net.magicterra.worlddriver.api.ApiMethod;
import net.magicterra.worlddriver.api.CallContext;
import net.magicterra.worlddriver.api.ServerThreadHop;
import net.magicterra.worlddriver.api.contract.ApiError;
import net.magicterra.worlddriver.api.contract.ApiException;
import net.magicterra.worlddriver.api.contract.ApiIssue;
import net.magicterra.worlddriver.api.contract.KeyedValueType.Entry;
import net.magicterra.worlddriver.bot.BotApi;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.BotHooks;
import net.magicterra.worlddriver.bot.SettingsCommand;
import net.magicterra.worlddriver.bot.SettingsContract;
import net.magicterra.worlddriver.bot.SettingsContract.Applied;
import net.magicterra.worlddriver.bot.SettingsContract.Apply;
import net.magicterra.worlddriver.bot.SettingsContract.Patch;
import net.magicterra.worlddriver.bot.SettingsContract.Patched;
import net.magicterra.worlddriver.bot.SettingsContract.Read;
import net.magicterra.worlddriver.bot.SettingsContract.SettingsOp;
import net.magicterra.worlddriver.bot.SettingsContract.SettingsReply;
import net.magicterra.worlddriver.bot.SettingsContract.Values;

/**
 * {@code mc.bot.setting}. The settings are read and written on the thread of the bot that reads them
 * most: this client's when there is one, else the server's, where the named bots run. The controls act
 * on this client's player, so they need a client.
 */
final class SettingsMethods {
    private final DriverApplication app;

    SettingsMethods(DriverApplication app) { this.app = app; }

    List<ApiMethod<?, ?>> methods() {
        return List.of(new ApiMethod<>(SettingsContract.SPEC, this::handle));
    }

    private CompletionStage<SettingsReply> handle(SettingsOp op, CallContext ctx) {
        BotApi bot = BotHooks.impl();
        return switch (op) {
            case Read r -> owner(bot).<SettingsReply>submit(() -> read(bot, r), ctx);
            case Patch p -> owner(bot).<SettingsReply>submit(() -> patch(bot, p), ctx);
            case Apply a -> {
                if (bot == null) {
                    throw ApiException.of(ApiError.Code.CAPABILITY_UNAVAILABLE,
                            a.control() + " acts on this client's player, and there is no client here");
                }
                yield bot.clientHop().<SettingsReply>submit(() -> apply(bot, a), ctx);
            }
        };
    }

    /** The thread the settings are read and written on. */
    private ServerThreadHop owner(BotApi bot) {
        if (bot != null) return bot.clientHop();
        ServerThreadHop hop = app.serverHop();
        if (hop == null) throw ApiException.of(ApiError.Code.CAPABILITY_UNAVAILABLE, "no game is running to hold the settings");
        return hop;
    }

    private static SettingsReply read(BotApi bot, Read r) {
        List<String> keys = r.keys().orElseGet(() -> readableKeys(bot));
        List<Entry> values = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            String k = keys.get(i);
            if (bot == null && k.equals("paused")) {
                throw ApiException.invalidParam("/keys/" + i, ApiIssue.Code.INVALID_VALUE,
                        "paused is this client's bot's, and there is no client here");
            }
            values.add(new Entry(k, SettingsCommand.read(bot, k)));
        }
        return new Values(values);
    }

    /** Every readable key; without a client, less paused, which only a client's bot has. */
    private static List<String> readableKeys(BotApi bot) {
        List<String> out = new ArrayList<>();
        for (String k : SettingsContract.TYPES.keySet()) {
            if (SettingsContract.readable(k) && (bot != null || !k.equals("paused"))) out.add(k);
        }
        return out;
    }

    private static SettingsReply patch(BotApi bot, Patch p) {
        List<ApiIssue> issues = SettingsCommand.check(p.values(), "/values");
        if (!issues.isEmpty()) throw ApiException.invalidParams(issues);
        SettingsCommand.commit(bot, p.values());
        String saveError = BotConfig.save();
        List<Entry> values = new ArrayList<>();
        for (Entry u : p.values()) values.add(new Entry(u.key(), SettingsCommand.read(bot, u.key())));
        return new Patched(values, saveError == null, Optional.ofNullable(saveError));
    }

    private static SettingsReply apply(BotApi bot, Apply a) {
        switch (a.control()) {
            case "paused" -> bot.setPaused(a.value());
            case "debugFly" -> bot.debugFly(a.value());
            default -> throw new IllegalArgumentException(a.control() + " is no control");
        }
        return new Applied(a.control(), a.value());
    }
}
