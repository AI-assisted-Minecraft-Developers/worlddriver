package net.magicterra.worlddriver.api.methods.system;

import static net.magicterra.worlddriver.api.methods.shared.CommonTypes.NO_PARAMS;

import java.util.Optional;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.NoParams;
import net.magicterra.worlddriver.api.schema.ApiType;
import net.magicterra.worlddriver.api.schema.ApiTypes;
import net.magicterra.worlddriver.api.schema.MethodSpec;
import net.magicterra.worlddriver.api.schema.annotation.Doc;
import net.magicterra.worlddriver.api.schema.annotation.Named;
import net.magicterra.worlddriver.api.schema.annotation.OnlyTrue;
import net.magicterra.worlddriver.api.schema.annotation.Range;

/** {@code mc.system.version}, {@code mc.system.testOrigin} and {@code mc.system.waitTicks}. */
public final class SystemContracts {

    public static final ApiType<Version> VERSION_REPLY = ApiTypes.record(Version.class);

    // Pollable: a constant plus a clock read, no world access.
    public static final MethodSpec<NoParams, Version> VERSION = MethodSpec.of(
                    "mc.system.version",
                    NO_PARAMS,
                    VERSION_REPLY,
                    "Probe the driver: call it first to confirm it answers. version is pinned; loadedFrom, builtAt and "
                            + "sizeBytes name the file the running code was loaded from, which is what tells two builds "
                            + "apart.")
            .asPollable();

    // ------------------------------------------------------------------ testOrigin

    // Pollable: a constant.
    public static final MethodSpec<NoParams, BlockPosition> TEST_ORIGIN = MethodSpec.of(
                    "mc.system.testOrigin",
                    NO_PARAMS,
                    CommonTypes.BLOCK_POSITION,
                    "The origin cell of the area the validation scripts are written against. mc.query and mc.observe.scene search around it when given no center.")
            .asPollable();

    public static final ApiType<WaitTicksParams> WAIT_TICKS_PARAMS = ApiTypes.record(WaitTicksParams.class);

    public static final ApiType<Waited> WAITED = ApiTypes.record(Waited.class);

    public static final MethodSpec<WaitTicksParams, Waited> WAIT_TICKS = MethodSpec.of(
                    "mc.system.waitTicks",
                    WAIT_TICKS_PARAMS,
                    WAITED,
                    "Answer after ticks × 50 ms of wall time; server lag is not made up. Refused on a game thread. To "
                            + "wait for something to happen rather than for time to pass, use mc.wait.*. The reply times "
                            + "the server ticks that ran meanwhile (work per tick, not counting the wait for the next).")
            .effects(MethodSpec.Effect.WAIT)
            .execution(MethodSpec.ExecutionClass.WAIT)
            .mayWait();

    private SystemContracts() {}

    // ------------------------------------------------------------------ version

    /**
     * {@code version} is the mod's version and is the same for two builds of one version; {@code loadedFrom} and the
     * file's time and size are measured from the file the classes were loaded from, which is what tells two builds
     * apart.
     */
    @Named("Version")
    public record Version(
            String modid,

            @Doc("The mod's version, as the mod loader lists it, for example 0.1.0-snapshot+1.21.1.")
            String version,

            @Range(min = 0) long uptimeMs,

            @Doc("The jar or class directory the running code came from.")
            String loadedFrom,

            @Doc("jar, classes, or the URL protocol when no file backs it.")
            String loadedKind,

            @Doc("That file's modification time, ISO-8601 UTC.")
            Optional<String> builtAt,

            Optional<Long> builtMs,
            Optional<@Range(min = 0) Long> sizeBytes,
            @Doc("Why the file could not be read.") Optional<String> stampError) {}

    // ------------------------------------------------------------------ waitTicks

    @Named("WaitTicksParams")
    public record WaitTicksParams(
            @Doc("Nominal ticks of 50 ms each.") @Range(min = 0, max = 200)
            int ticks) {}

    /** {@code interrupted}: the call was cancelled or ran out of budget first, so fewer ticks passed. */
    @Named("Waited")
    public record Waited(
            @Range(min = 0, max = 200) int waited,
            Optional<@OnlyTrue Boolean> interrupted,

            @Doc("Present when this JVM runs the server and at least one tick ran during the wait.")
            Optional<TickTimes> server) {}

    /**
     * The server ticks that ran while the call waited, timed as vanilla times them: the work of a tick, not the wait
     * for the next one.
     */
    @Named("TickTimes")
    public record TickTimes(
            @Doc("Server ticks that ran; fewer than asked means the server fell behind.") @Range(min = 0)
            int ticks,

            @Doc("Mean milliseconds of work per tick.") double meanMs,
            double medianMs,
            double maxMs) {}
}
