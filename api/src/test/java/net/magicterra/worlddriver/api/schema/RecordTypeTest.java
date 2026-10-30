package net.magicterra.worlddriver.api.schema;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Every contract class's types build: a bad annotation only throws when its type is first built. */
class RecordTypeTest {

    /**
     * Every contract class builds its record types in static initializers, and only the ones the artifact generator
     * happens to reach are built during the build. Initialize all of them so each check runs.
     */
    @Test
    void everyContractClassInitializes() throws IOException, URISyntaxException, ClassNotFoundException {
        Path classes = Path.of(RecordType.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
        Path methods = classes.resolve("net/magicterra/worlddriver/api/methods");
        List<String> names;
        try (Stream<Path> files = Files.walk(methods)) {
            names = files.map(p -> classes.relativize(p).toString())
                    .filter(n -> n.endsWith(".class") && !n.contains("$"))
                    .map(n -> n.substring(0, n.length() - ".class".length())
                            .replace('/', '.')
                            .replace('\\', '.'))
                    .toList();
        }
        assertTrue(names.size() > 50, "found only " + names.size() + " contract classes under " + methods);
        for (String name : names) {
            Class.forName(name, true, RecordTypeTest.class.getClassLoader());
        }
    }
}
