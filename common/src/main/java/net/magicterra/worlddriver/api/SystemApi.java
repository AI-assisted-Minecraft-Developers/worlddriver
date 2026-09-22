package net.magicterra.worlddriver.api;

import net.magicterra.worlddriver.BuildStamp;
import net.minecraft.core.BlockPos;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code mc.system.*} handlers, extracted from {@code DriverApi}. Holds an
 * {@link DriverApi} back-reference for the shared server handle + start clock;
 * dispatch still flows through {@code DriverApi.route} (single source of truth).
 */
public final class SystemApi {
    private final DriverApi api;
    SystemApi(DriverApi api) { this.api = api; }

    /**
     * {@code {modid, version, uptimeMs}} plus {@link BuildStamp#asMap()} — where the running
     * code was loaded from and when that file was written. The version string is pinned and
     * cannot tell two builds apart; the stamp can, which is what makes this usable as evidence
     * that a fix actually reached the game.
     */
    public Map<String, Object> version() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("modid", "worlddriver");
        out.put("version", BuildStamp.VERSION);
        out.put("uptimeMs", (System.nanoTime() - api.startNanos) / 1_000_000L);
        out.putAll(BuildStamp.asMap());
        return out;
    }

    public BlockPos testOrigin() { return DriverApi.ORIGIN; }

    /**
     * Block the calling thread for {@code ticks * 50ms} (the nominal MC tick period).
     * Approximate — server lag is not compensated. Refuses to run on the server
     * thread itself so it cannot deadlock the game loop. Returns {@code {waited:N}};
     * if interrupted, {@code waited} reflects elapsed ticks and {@code interrupted:true}
     * is set.
     */
    public Map<String, Object> waitTicks(int ticks) {
        if (ticks <= 0) return Map.of("waited", 0);
        ServerThreadGuard.refuseBlocking("waitTicks");
        long t0 = System.nanoTime();
        try {
            Thread.sleep(ticks * 50L);
            return Map.of("waited", ticks);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            int waited = (int)((System.nanoTime() - t0) / 50_000_000L);
            return Map.of("waited", waited, "interrupted", true);
        }
    }
}
