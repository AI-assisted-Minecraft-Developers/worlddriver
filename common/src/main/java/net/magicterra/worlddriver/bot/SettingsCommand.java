package net.magicterra.worlddriver.bot;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.magicterra.worlddriver.api.schema.ApiIssue;
import net.magicterra.worlddriver.api.schema.KeyedValueType.Entry;
import net.magicterra.worlddriver.api.methods.setting.SettingDefs.AvoidPoint;
import net.magicterra.worlddriver.api.methods.setting.SettingsContracts;
import net.minecraft.core.registries.BuiltInRegistries;
import net.magicterra.worlddriver.game.observe.RegistryIds;
import net.magicterra.worlddriver.config.BotConfig;
import net.magicterra.worlddriver.config.GameConfig;
import net.magicterra.worlddriver.config.WorldConfig;
import net.magicterra.worlddriver.config.SettingsRegistry;

/**
 * Reads and writes the {@code mc.bot.setting} keys in {@link BotConfig}. Types and ranges are the
 * contract's ({@link SettingsContracts}), so a value that reaches {@link #commit} is already the right
 * type and in range; what is left here is the check the contract cannot make without the game (that a
 * block id names a registered block) and the writes themselves.
 *
 * <p>Server-safe: it names no client class. {@code paused} and {@code debugFly}, the two controls, are
 * this client's bot's and are applied through {@link BotApi}, not here.
 *
 * <p><b>What "together" means.</b> {@link #commit} writes every update in one run on the thread that owns
 * the settings, so the bot that thread advances never sees half a patch. The settings are static and
 * read by every bot in the JVM, so a bot advanced by the other game thread can still read between two
 * writes of one commit.
 */
public final class SettingsCommand {

    private SettingsCommand() {}

    /** {@code key}'s current value, as its contract type holds it; {@code bot} answers {@code paused}. */
    public static Object read(BotApi bot, String key) {
        return switch (SettingsRegistry.readOf(key)) {
            case BOT_PAUSED -> bot != null && bot.paused();
            case HAZARD_LIST -> List.copyOf(GameConfig.extraHazardBlocks);
            case WHITELIST_LIST -> List.copyOf(GameConfig.buildBlockWhitelist);
            case MUTED_LIST -> List.copyOf(BotConfig.mutedEvents);
            case UNDRIVABLE_LIST -> List.copyOf(BotConfig.undrivableMobs);
            case AVOID_POINTS -> {
                List<AvoidPoint> out = new ArrayList<>();
                for (double[] z : WorldConfig.avoidZones) {
                    if (z.length >= 4) out.add(new AvoidPoint(z[0], z[1], z[2], z[3]));
                }
                yield out;
            }
            case CONFIG_FIELD -> fieldValue(key);
        };
    }

    // Its own method, not a switch arm: a yield inside a try kills the code generation of the sources
    // jar's remapper (Mercury's bundled JDT), which then leaves most of the jar out.
    private static Object fieldValue(String key) {
        Field f = SettingsRegistry.backingField(key);
        if (f == null) throw new IllegalArgumentException(key + " has no value to read");
        try {
            Object v = f.get(null);
            return v instanceof Float fl ? (Object) fl.doubleValue() : v;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("cannot read BotConfig." + f.getName(), e);
        }
    }

    /**
     * The block ids among {@code updates} that name no registered block, as issues at
     * {@code <path>/<key>[/<j>]}. Empty when the patch can be committed.
     */
    public static List<ApiIssue> check(List<Entry> updates, String path) {
        List<ApiIssue> issues = new ArrayList<>();
        for (Entry u : updates) {
            String at = path + "/" + u.key();
            switch (u.key()) {
                case "autoBackfillBlock" -> {
                    if (!isBlock((String) u.value())) issues.add(notABlock(at, (String) u.value()));
                }
                case "blocksToAvoid", "buildBlockWhitelist" -> {
                    List<?> ids = (List<?>) u.value();
                    for (int j = 0; j < ids.size(); j++) {
                        if (!isBlock((String) ids.get(j))) issues.add(notABlock(at + "/" + j, (String) ids.get(j)));
                    }
                }
                case "undrivableMobs" -> {
                    List<?> ids = (List<?>) u.value();
                    for (int j = 0; j < ids.size(); j++) {
                        String id = (String) ids.get(j);
                        if (!RegistryIds.registered(BuiltInRegistries.ENTITY_TYPE, id)) {
                            issues.add(new ApiIssue(at + "/" + j, ApiIssue.Code.INVALID_VALUE, "no entity type is registered as " + id));
                        }
                    }
                }
                default -> { }
            }
        }
        return issues;
    }

    private static boolean isBlock(String id) {
        return RegistryIds.registered(BuiltInRegistries.BLOCK, id);
    }

    private static ApiIssue notABlock(String path, String id) {
        return new ApiIssue(path, ApiIssue.Code.INVALID_VALUE, "no block is registered as " + id);
    }

    /**
     * Write every update; {@link #check} has passed. Turning {@code autoBackfill} off also drops the
     * queue of cells {@code bot} was going to refill, so a later on does not refill stale ones.
     */
    @SuppressWarnings("unchecked")
    public static void commit(BotApi bot, List<Entry> updates) {
        for (Entry u : updates) {
            Object v = u.value();
            switch (SettingsRegistry.readOf(u.key())) {
                case HAZARD_LIST -> GameConfig.extraHazardBlocks = Set.copyOf((List<String>) v);
                case WHITELIST_LIST -> GameConfig.buildBlockWhitelist = Set.copyOf((List<String>) v);
                case MUTED_LIST -> BotConfig.mutedEvents = Set.copyOf(new LinkedHashSet<>((List<String>) v));
                case UNDRIVABLE_LIST -> BotConfig.undrivableMobs = Set.copyOf((List<String>) v);
                case AVOID_POINTS -> {
                    List<AvoidPoint> points = (List<AvoidPoint>) v;
                    double[][] zones = new double[points.size()][];
                    for (int i = 0; i < zones.length; i++) {
                        AvoidPoint p = points.get(i);
                        zones[i] = new double[]{ p.x(), p.y(), p.z(), p.radius() };
                    }
                    WorldConfig.avoidZones = zones;
                }
                case CONFIG_FIELD -> writeField(u.key(), v);
                case BOT_PAUSED -> throw new IllegalArgumentException("paused is a control, applied through the bot");
            }
        }
        if (bot != null && updates.stream().anyMatch(u -> u.key().equals("autoBackfill") && Boolean.FALSE.equals(u.value()))) {
            bot.forgetBackfill();
        }
    }

    private static void writeField(String key, Object v) {
        Field f = SettingsRegistry.backingField(key);
        if (f == null) throw new IllegalArgumentException(key + " is not a configuration field");
        try {
            Class<?> t = f.getType();
            if (t == boolean.class) f.setBoolean(null, (Boolean) v);
            else if (t == int.class) f.setInt(null, (Integer) v);
            else if (t == long.class) f.setLong(null, (Long) v);
            else if (t == double.class) f.setDouble(null, (Double) v);
            else if (t == float.class) f.setFloat(null, ((Double) v).floatValue());
            else f.set(null, v);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("cannot write BotConfig." + f.getName(), e);
        }
    }
}
