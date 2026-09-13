package net.magicterra.worlddriver.bot.stagewright.scene;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import net.magicterra.stagewright.contract.SceneFailure;
import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.testcontent.FixtureIO;
import net.magicterra.worlddriver.testcontent.FixtureRunner;
import net.magicterra.worlddriver.testcontent.SceneFixture;

/**
 * The hand-built scenes as suite members. Two sources:
 *
 * <ul>
 *   <li>the testmod's own {@code scenes/index.txt} — one fixture name per line, each with its
 *       {@code .json} and {@code .nbt} beside it in the jar. An index rather than a directory
 *       scan: Java cannot portably list a classpath directory, and {@code JsScenes} reads the run
 *       directory's file system, not resources. A committed fixture is an ordinary scene, judged
 *       with the {@code wd.*} ones, and belongs in both {@code expected-scenes-*.txt} in the same
 *       commit — the manifest is part of the judge.</li>
 *   <li>the local {@code config/worlddriver/scenes/} directory, only under a hold
 *       ({@code -Dstagewright.hold}) or {@code -Dworlddriver.localScenes=true}. Not on a gate run:
 *       the judge's UNDECLARED check makes every prefix seen in the manifest a checked namespace,
 *       and a registered {@code human.*} scene that is not in the manifest turns the verdict red —
 *       which a tester's uncommitted file would do. The hold flag is read directly rather than
 *       through {@code StageWrightCommon.holding()}: that class is in {@code stagewright-common},
 *       and the testmod deliberately depends on {@code stagewright-api} alone (a version loop,
 *       refused in {@code common/build.gradle}); this is the same one-line read, copied.</li>
 * </ul>
 */
public final class HumanScenes implements SceneProvider {

    public static final String PREFIX = "human.";
    public static final String INDEX = "/scenes/index.txt";

    @Override
    public List<Scene> scenes() {
        List<Scene> out = new ArrayList<>();
        for (String name : indexNames()) out.add(fromResource(name));
        if (Boolean.getBoolean("stagewright.hold") || Boolean.getBoolean("worlddriver.localScenes")) {
            out.addAll(fromLocalDir());
        }
        return out;
    }

    /** The names in the index, comments and blanks dropped; empty when the jar has no index. */
    static List<String> indexNames() {
        List<String> names = new ArrayList<>();
        try (InputStream in = HumanScenes.class.getResourceAsStream(INDEX)) {
            if (in == null) return names;
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String t = line.strip();
                if (t.isEmpty() || t.startsWith("#")) continue;
                if (!t.startsWith(PREFIX)) throw new IllegalStateException(INDEX + ": '" + t + "' must start with " + PREFIX);
                names.add(t);
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + INDEX, e);
        }
        return names;
    }

    private static Scene fromResource(String name) {
        SceneFixture fixture;
        try (InputStream in = HumanScenes.class.getResourceAsStream("/scenes/" + name + ".json")) {
            if (in == null) throw new IllegalStateException("scenes/index.txt names '" + name + "' but scenes/" + name + ".json is not in the jar");
            fixture = SceneFixture.fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read scenes/" + name + ".json", e);
        }
        if (!name.equals(fixture.name())) {
            throw new IllegalStateException("scenes/" + name + ".json calls itself '" + fixture.name() + "'");
        }
        String nbt = "/scenes/" + name + ".nbt";
        return scene(fixture, ctx -> {
            try {
                FixtureRunner.runAuto(ctx, fixture, FixtureRunner.templateFromResource(ctx.level(), nbt));
            } catch (IOException e) {
                throw new SceneFailure("fixture terrain " + nbt + ": " + e.getMessage());
            }
        });
    }

    private static List<Scene> fromLocalDir() {
        List<Scene> out = new ArrayList<>();
        Path dir = FixtureIO.scenesDir();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path json : files.filter(f -> f.getFileName().toString().endsWith(".json")).sorted().toList()) {
                SceneFixture fixture;
                try {
                    fixture = FixtureIO.loadJson(json);
                } catch (IOException | RuntimeException e) {
                    WorldDriverCommon.LOG.warn("[human scenes] skipping {}: {}", json.getFileName(), e.getMessage());
                    continue;
                }
                Path nbt = FixtureIO.nbtPath(fixture.name());
                if (!fixture.name().startsWith(PREFIX) || !Files.isRegularFile(nbt)) continue;
                out.add(scene(fixture, ctx -> {
                    try {
                        FixtureRunner.runAuto(ctx, fixture, FixtureIO.loadNbt(ctx.level(), nbt));
                    } catch (IOException e) {
                        throw new SceneFailure("fixture terrain " + nbt + ": " + e.getMessage());
                    }
                }));
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot list " + dir, e);
        }
        if (!out.isEmpty()) WorldDriverCommon.LOG.info("[human scenes] {} local fixture(s) from {}", out.size(), dir);
        return out;
    }

    /** The scene's tick budget is its legs' budgets plus room for staging and the final checks. */
    static int budgetTicks(SceneFixture fixture) {
        int sum = 0;
        for (SceneFixture.Leg leg : fixture.legs()) sum += Math.max(1, leg.budget());
        return sum + 200;
    }

    private static Scene scene(SceneFixture fixture, java.util.function.Consumer<SceneContext> body) {
        return Scene.of(fixture.name(), budgetTicks(fixture), body).withChunkRadius(Math.max(1, fixture.chunkRadius()));
    }
}
