package net.magicterra.worlddriver.testcontent;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import net.magicterra.worlddriver.WorldDriverCommon;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code /worlddriver test} as the testmod registers it, and the claim that keeps two runs apart. */
class ValidationSuiteTest {

    @AfterEach
    void releaseAnyClaim() {
        ValidationSuite.release();
    }

    private static CommandSourceStack source(int permission) {
        return new CommandSourceStack(CommandSource.NULL, Vec3.ZERO, Vec2.ZERO, null, permission,
                "test", Component.literal("test"), null, null);
    }

    @Test
    void theTestmodTreeCarriesTheSuiteForOperatorsOnly() {
        CommandDispatcher<CommandSourceStack> d = new CommandDispatcher<>();
        SceneCommands.register(d);
        CommandNode<CommandSourceStack> test = d.findNode(List.of(WorldDriverCommon.MOD_ID, "test"));
        assertNotNull(test);
        assertNotNull(test.getChild("list"));
        assertNotNull(test.getChild("result"));
        assertFalse(test.canUse(source(0)));
        assertTrue(test.canUse(source(2)));
    }

    /** Two runs would share ScriptTest's one result list, and a second seed would scrub the arena under the first. */
    @Test
    void aSecondRunIsRefusedWhileOneHoldsTheSuite() {
        assertTrue(ValidationSuite.tryClaim());
        assertFalse(ValidationSuite.tryClaim());
        ValidationSuite.release();
        assertTrue(ValidationSuite.tryClaim());
    }

    @Test
    void everyListedScriptIsOnTheTestmodClasspath() throws IOException {
        for (String name : ValidationSuite.SCRIPTS) {
            try (InputStream in = ValidationSuite.class.getResourceAsStream(ValidationSuite.RESOURCE_DIR + name)) {
                assertNotNull(in, name);
            }
        }
    }
}
