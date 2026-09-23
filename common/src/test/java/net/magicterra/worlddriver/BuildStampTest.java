package net.magicterra.worlddriver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The URL shapes a class loader can hand {@link BuildStamp}. The one worth testing here is
 * {@code union:} — it exists only inside NeoForge's modlauncher, so it cannot be reached from a
 * plain JVM, and a stamp that silently failed to parse it would still answer: it would just drop
 * the timestamp on the loader we ship to, which is the only field anyone asks it for.
 */
class BuildStampTest {
    private static final String ENTRY = "net/magicterra/worlddriver/BuildStamp.class";

    @Test
    void aLooseClassFileIsItsOwnStamp(@TempDir Path dir) throws IOException {
        Path cls = writeClass(dir);
        assertEquals(cls, BuildStamp.resolve(cls.toUri().toString()));
    }

    @Test
    void aJarIsStampedByTheArchive(@TempDir Path dir) throws IOException {
        Path jar = writeJar(dir.resolve("worlddriver.jar"));
        assertEquals(jar, BuildStamp.resolve("jar:" + jar.toUri() + "!/" + ENTRY));
    }

    /** modlauncher's shape: the {@code union:} scheme, with a {@code #n} index on the key. */
    @Test
    void aUnionOverAJarIsStampedByTheJar(@TempDir Path dir) throws IOException {
        Path jar = writeJar(dir.resolve("worlddriver.jar"));
        assertEquals(jar, BuildStamp.resolve("union:" + jar + "%2312!/" + ENTRY));
    }

    /**
     * Union joins directories as readily as jars, and a directory's mtime moves whenever any
     * sibling is written into it — dating it would report someone else's rebuild as this one's.
     */
    @Test
    void aUnionOverADirectoryIsStampedByTheClassFile(@TempDir Path dir) throws IOException {
        Path cls = writeClass(dir);
        assertEquals(cls, BuildStamp.resolve("union:" + dir + "%231!/" + ENTRY));
    }

    /** The dev jar carries the mod version, and {@code 0.1.0+1.21.1} is where a plus shows up. */
    @Test
    void aPlusInTheJarNameIsKeptLiterally(@TempDir Path dir) throws IOException {
        Path jar = writeJar(dir.resolve("worlddriver-common-0.1.0+1.21.1-dev.jar"));
        assertEquals(jar, BuildStamp.resolve("jar:" + jar.toUri() + "!/" + ENTRY));
        assertEquals(jar, BuildStamp.resolve("union:" + jar + "%231!/" + ENTRY));
    }

    @Test
    void aContainerThatIsNotThereStampsNothing(@TempDir Path dir) {
        assertNull(BuildStamp.resolve("union:" + dir.resolve("absent.jar") + "%231!/" + ENTRY));
    }

    /** The stamp of this very test run: whatever it names must be a file that exists. */
    @Test
    void theStampNamesSomethingOnDisk() {
        Map<String, Object> stamp = BuildStamp.asMap();
        assertTrue(stamp.containsKey("builtAt"), "no builtAt in " + stamp);
        assertTrue(Files.exists(Path.of((String) stamp.get("loadedFrom"))), "no file for " + stamp);
    }

    private static Path writeClass(Path dir) throws IOException {
        Path cls = dir.resolve(ENTRY);
        Files.createDirectories(cls.getParent());
        Files.writeString(cls, "not really bytecode");
        return cls;
    }

    private static Path writeJar(Path jar) throws IOException {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new ZipEntry(ENTRY));
            out.write(new byte[] { 1 });
            out.closeEntry();
        }
        return jar;
    }
}
