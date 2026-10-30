package net.magicterra.worlddriver.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BotConfigFileTest {

    @Test
    void aRenamedSettingIsReportedAndItsShadowIsNot() {
        Properties props = new Properties();
        props.setProperty("autoEat", "true");
        props.setProperty("autoEat.default", "false");
        props.setProperty("walkerDebug", "true");
        props.setProperty("walkerDebug.default", "false");
        assertEquals(Set.of("walkerDebug", "walkerDebug.default"), BotConfigFile.unknownKeys(props));
    }
}
