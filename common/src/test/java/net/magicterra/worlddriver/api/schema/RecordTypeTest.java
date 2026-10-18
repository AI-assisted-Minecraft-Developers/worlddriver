package net.magicterra.worlddriver.api.schema;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/** The order check that keeps a record type's positional decode from swapping two fields of one type. */
class RecordTypeTest {

    record Pair(boolean left, boolean right) {}

    record Renamed(int depth, String breakPolicy) {}

    @Test
    void fieldsListedOutOfTheRecordsOrderAreRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ApiTypes.record(Pair.class, "Pair",
                ApiField.field("right", ApiTypes.bool()).required().forGetter(Pair::right),
                ApiField.field("left", ApiTypes.bool()).required().forGetter(Pair::left),
                Pair::new));
        assertTrue(e.getMessage().contains("'right'"), e.getMessage());
    }

    @Test
    void aWireNameThatDiffersFromItsComponentIsAllowed() {
        assertDoesNotThrow(() -> ApiTypes.record(Renamed.class, "Renamed",
                ApiField.field("depth", ApiTypes.integer(0, 9)).required().forGetter(Renamed::depth),
                ApiField.field("break", ApiTypes.string()).required().forGetter(Renamed::breakPolicy),
                Renamed::new));
    }

    /**
     * Every contract class builds its record types in static initializers, and only the ones the artifact
     * generator happens to reach are built during the build. Initialize all of them so each check runs.
     */
    @Test
    void everyContractClassInitializes() throws IOException, URISyntaxException, ClassNotFoundException {
        Path classes = Path.of(RecordType.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path methods = classes.resolve("net/magicterra/worlddriver/api/methods");
        List<String> names;
        try (Stream<Path> files = Files.walk(methods)) {
            names = files.map(p -> classes.relativize(p).toString())
                    .filter(n -> n.endsWith(".class") && !n.contains("$"))
                    .map(n -> n.substring(0, n.length() - ".class".length()).replace('/', '.').replace('\\', '.'))
                    .toList();
        }
        assertTrue(names.size() > 50, "found only " + names.size() + " contract classes under " + methods);
        for (String name : names) Class.forName(name, true, RecordTypeTest.class.getClassLoader());
    }
}
