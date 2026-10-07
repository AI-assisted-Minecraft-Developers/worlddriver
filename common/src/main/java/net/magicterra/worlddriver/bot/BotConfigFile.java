package net.magicterra.worlddriver.bot;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import static net.magicterra.worlddriver.WorldDriverCommon.LOG;

/**
 * {@link BotConfig} persisted across a restart.
 *
 * <p>Without persistence every relaunch resets the volatiles to their defaults, so
 * survival toggles (autoSwim, autoRetreat, …) would have to be re-applied by hand after
 * each restart — and a default-off autoSwim can drown the bot on reload before it is
 * set. A successful mc.bot.setting apply writes every scalar (+ the
 * hazard-block Set) to a properties file via reflection, and the file is reloaded
 * at startup. Reflection means new tunables persist automatically with no per-field
 * code. Opt-in via -Dworlddriver.persistConfig=true (set by the dev runClient/runServer
 * launch, NOT the headless validation/GameTest tasks) so the suites stay
 * deterministic on pure defaults. avoidZones (a transient per-goto double[][]) is
 * intentionally NOT persisted.
 *
 * <p>Every method locks {@code BotConfig.class}, the same monitor as
 * {@link BotConfig#snapshotAll} / {@link BotConfig#restoreAll}.
 */
public final class BotConfigFile {
    private BotConfigFile() {}

    static Path persistPath() {
        return Path.of("config", "worlddriver_bot.properties");
    }

    /** Persistence is opt-in so headless validation / GameTest never read or write
     *  the file (their setting tests must run on pristine defaults). */
    private static boolean persistEnabled() {
        return Boolean.getBoolean("worlddriver.persistConfig")
                && !Boolean.getBoolean("worlddriver.runValidation");
    }

    /** Suffix of the SHADOW-DEFAULT companion line written next to every persisted
     *  key: {@code <key>.default=<compiled default when the file was saved>}. A '.'
     *  can never collide with a field name (Java identifiers have no dots), so a
     *  {@code .default} line is never mistaken for an unknown field — and legacy
     *  code (which only ever looks up keys by field name) simply ignores it, so a
     *  new-format file stays readable by an older build. */
    private static final String SHADOW_SUFFIX = ".default";

    /** Write every persistable field to disk in SHADOW-DEFAULT format. Called after
     *  a successful {@code mc.bot.setting} apply (and by {@link #load()} to upgrade a
     *  stale/legacy file). For each field two lines are emitted:
     *  <pre>  &lt;key&gt;=&lt;current value&gt;
     *  &lt;key&gt;.default=&lt;compiled default of THIS build&gt;</pre>
     *  On the next {@link #load()} a key whose value equals its shadow default is a
     *  mere snapshot of the-then default and yields to the current compiled default;
     *  a key whose value differs was set by the user and is preserved. Setting a key
     *  explicitly back to its default therefore counts as following the default (its
     *  value will equal the freshly-written shadow), which is the intended semantics:
     *  "I want the default" and "I never touched it" persist identically.
     *  Never throws: returns why nothing was written, or null when it was. */
    public static String save() {
        if (!persistEnabled()) return "persistence is off (-Dworlddriver.persistConfig=true turns it on)";
        synchronized (BotConfig.class) {
            try {
                Properties props = new Properties();
                for (Field f : BotConfig.persistableFields()) {
                    String v = BotConfig.serialize(f);
                    if (v == null) continue;
                    props.setProperty(f.getName(), v);
                    String def = BotConfig.COMPILED_DEFAULTS.get(f.getName());
                    if (def != null) props.setProperty(f.getName() + SHADOW_SUFFIX, def);
                }
                Path path = persistPath();
                if (path.getParent() != null) Files.createDirectories(path.getParent());
                // Written beside the target and moved over it, so a crash mid-write leaves the old file, not half of one.
                Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
                try (var w = Files.newBufferedWriter(tmp)) {
                    props.store(w, "worlddriver bot settings — auto-saved by mc.bot.setting (<key>.default = compiled default"
                            + " at save time; a key equal to its default is a snapshot and follows the current build's default)");
                }
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return null;
            } catch (Exception e) {
                LOG.warn("[config] save failed: {}", e.toString());
                return "save failed: " + e;
            }
        }
    }

    /** Reload persisted settings at startup, before any tick reads them, applying
     *  SHADOW-DEFAULT semantics per key. Missing file → defaults stand; a malformed
     *  individual key is skipped (a partial/old file still applies what it can).
     *  <ul>
     *    <li><b>shadow present, value == shadow</b> → SKIP: the persisted value was
     *        just a snapshot of the default when the file was saved, so the current
     *        compiled default wins (this is the fix for the config-persistence trap —
     *        a stale snapshot no longer crushes a later default flip).</li>
     *    <li><b>shadow present, value != shadow</b> → APPLY: the user changed it;
     *        preserve their value.</li>
     *    <li><b>no shadow (legacy file)</b> → APPLY conservatively, then emit ONE
     *        WARN listing the keys that differ from the current compiled default, and
     *        upgrade-re-save the file in shadow format.</li>
     *  </ul>
     *  {@code .default} lines are looked up deliberately as each key's companion; the
     *  loop iterates the field set (never the raw property keys), so a shadow line is
     *  never treated as an unknown field. A stale snapshot (SKIP where the stored
     *  value no longer equals the current compiled default) also triggers an upgrade
     *  re-save so the file reflects the winning default. Any re-save is best-effort —
     *  an IO failure is logged, never fatal to startup. */
    public static void load() {
        if (!persistEnabled()) return;
        synchronized (BotConfig.class) {
            try {
                Path path = persistPath();
                if (!Files.exists(path)) return;
                Properties props = new Properties();
                try (var r = Files.newBufferedReader(path)) { props.load(r); }
                int applied = 0, skipped = 0;
                boolean needsUpgrade = false;          // any legacy key, or any stale snapshot
                List<String> legacyDrift = new ArrayList<>();
                for (Field f : BotConfig.persistableFields()) {
                    String key = f.getName();
                    String s = props.getProperty(key);
                    if (s == null) continue;
                    String shadow = props.getProperty(key + SHADOW_SUFFIX);
                    String curDefault = BotConfig.COMPILED_DEFAULTS.get(key);
                    if (shadow == null) {
                        // Legacy line (no shadow): keep the user's value conservatively.
                        try {
                            BotConfig.assign(f, s); applied++;
                            if (curDefault != null && !s.trim().equals(curDefault.trim())) legacyDrift.add(key);
                        } catch (Exception ex) { LOG.warn("[config] skip {}: {}", key, ex.toString()); }
                        needsUpgrade = true;
                    } else if (s.trim().equals(shadow.trim())) {
                        // Snapshot of the-then default → the current compiled default wins.
                        skipped++;
                        if (curDefault != null && !s.trim().equals(curDefault.trim())) needsUpgrade = true;
                    } else {
                        // User set it away from the saved default → preserve.
                        try { BotConfig.assign(f, s); applied++; }
                        catch (Exception ex) { LOG.warn("[config] skip {}: {}", key, ex.toString()); }
                    }
                }
                LOG.info("[config] loaded {} persisted bot setting(s) ({} snapshot key(s) followed current default) from {}",
                        applied, skipped, path.toAbsolutePath());
                if (!legacyDrift.isEmpty()) {
                    // NOTE the permanence: the upgrade re-save stamps each legacy drift key with
                    // shadow = CURRENT compiled default while keeping its stale value, so from then
                    // on value != shadow and the key is treated as user-set FOREVER. Only files
                    // written by shadow-aware code get true snapshot-follows-default semantics;
                    // this WARN list is the operator's one chance to spot and hand-fix stale keys.
                    LOG.warn("[config] legacy config file (no shadow defaults) — applied as-is;"
                            + " {} key(s) differ from this build's compiled default and were preserved"
                            + " (they will remain user-set after the upgrade): {}."
                            + " Upgrading file to shadow-default format.", legacyDrift.size(), legacyDrift);
                }
                if (needsUpgrade) save();   // best-effort upgrade re-save (IO failure logged, not fatal)
            } catch (Exception e) {
                LOG.warn("[config] load failed: {}", e.toString());
            }
        }
    }
}
