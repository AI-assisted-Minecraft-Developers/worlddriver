package net.magicterra.worlddriver.bot.stagewright.kit;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.magicterra.stagewright.test.Plot;
import net.minecraft.core.BlockPos;

/**
 * The terrain of a built benchmark course: the course as {@code bench.py setup} built it in the Lab world, saved as a
 * structure file under {@code stagewright/courses/<course>.nbt} on the test class path, air included, so that placing
 * it in a plot gives the course cell for cell. It is the terrain the human ran on, so the markers {@code bench.py
 * markers} placed (the beacons under the checkpoints and the glass that tints their beams) are in it. The test placing
 * it names where the file's minimum corner stood in the Lab world, the corner of the course file's {@code terrain.box};
 * {@link #place} answers how far that moves the course, for {@link RouteCourse#shifted}.
 */
public final class CourseTerrain {

    private CourseTerrain() {}

    /**
     * Place {@code course}'s terrain in {@code plot} with its minimum corner at relative {@code (x, y, z)}, and answer
     * how far that moves the course from where the bench ran it: {@code corner}, the export's minimum corner in the Lab
     * world, to the plot's cell.
     */
    public static BlockPos place(Plot plot, String course, BlockPos corner, int x, int y, int z) {
        plot.structure(file(course), x, y, z);
        return plot.at(x, y, z).subtract(corner);
    }

    /**
     * The structure file of {@code course}, copied out of the class path into the game's working directory: a plot
     * reads a structure from a file, and in a packaged testmod the resource is not one.
     */
    private static Path file(String course) {
        String resource = "/stagewright/courses/" + course + ".nbt";
        Path out = Path.of("courses", course + ".nbt");
        try (InputStream in = CourseTerrain.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("the testmod has no course terrain " + resource);
            }
            Files.createDirectories(out.getParent());
            Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot copy " + resource + " to " + out.toAbsolutePath(), e);
        }
        return out;
    }
}
