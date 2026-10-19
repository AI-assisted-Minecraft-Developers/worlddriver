package net.magicterra.worlddriver.api.methods;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.magicterra.worlddriver.api.schema.ApiType;
import net.magicterra.worlddriver.api.methods.bot.GotoContracts;
import net.magicterra.worlddriver.api.methods.client.ClientContracts;
import net.magicterra.worlddriver.api.methods.control.ControlContracts;
import net.magicterra.worlddriver.api.methods.event.EventContracts;
import net.magicterra.worlddriver.api.methods.query.QueryContracts;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.setting.SettingsContracts;
import net.magicterra.worlddriver.protocol.JsonCodec;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The parameter factories Java callers build requests with spell out the contract's defaults by hand.
 * Each must equal what the contract decodes from the shortest JSON a caller could send for the same
 * request, so a default changed in a contract cannot leave its factory sending the old one.
 */
class ParamFactoriesTest {

    private static final BlockPosition POS = new BlockPosition(1, 64, -2);
    private static final String POS_JSON = "{\"x\":1,\"y\":64,\"z\":-2}";

    private static <T> void same(ApiType<T> type, String json, T built) {
        assertEquals(type.decode(JsonCodec.decode(json)), built, json);
    }

    @Test
    void gotoFactoriesMatchTheContractsDefaults() {
        same(GotoContracts.PARAMS, "{\"target\":{\"kind\":\"position\",\"pos\":" + POS_JSON + "}}",
                GotoContracts.Params.to(POS));
        same(GotoContracts.PARAMS, "{\"target\":{\"kind\":\"level\",\"y\":12}}",
                GotoContracts.Params.toTarget(new GotoContracts.Level(12)));
        same(GotoContracts.PARAMS, "{\"planId\":\"p-1\"}", GotoContracts.Params.walkPlan("p-1"));
        same(GotoContracts.PARAMS, "{\"target\":{\"kind\":\"position\",\"pos\":" + POS_JSON + "},\"awaitMs\":500}",
                GotoContracts.Params.to(POS).awaiting(500));
    }

    @Test
    void controlFactoriesMatchTheContractsDefaults() {
        same(ControlContracts.CANCEL.input(), "{\"actionId\":\"a-1\"}", ControlContracts.CancelParams.action("a-1"));
        same(ControlContracts.CANCEL.input(), "{\"process\":\"sleep\"}", ControlContracts.CancelParams.process(ControlContracts.ProcessName.SLEEP));
        same(ControlContracts.CANCEL.input(), "{}", ControlContracts.CancelParams.all());
        same(ControlContracts.STATUS.input(), "{}", ControlContracts.StatusParams.self());
    }

    @Test
    void queryFactoriesMatchTheContractsDefaults() {
        same(QueryContracts.PARAMS, "{\"q\":\"blocks\",\"center\":" + POS_JSON + ",\"filter\":{\"in_radius\":3}}",
                QueryContracts.Blocks.around(POS, 3));
        same(QueryContracts.PARAMS, "{\"q\":\"blocks\",\"center\":" + POS_JSON
                        + ",\"filter\":{\"in_radius\":3,\"type\":\"minecraft:stone\"}}",
                QueryContracts.Blocks.around(POS, 3).ofType("minecraft:stone"));
        same(ClientContracts.BLOCKS_PARAMS, "{\"radius\":2}", ClientContracts.BlocksParams.aroundPlayer(2));
        same(ClientContracts.BLOCKS_PARAMS, "{\"center\":" + POS_JSON + ",\"radius\":2}",
                ClientContracts.BlocksParams.around(POS, 2));
    }

    @Test
    void eventFactoriesMatchTheContractsDefaults() {
        same(EventContracts.EVENTS_SINCE.input(), "{\"cursor\":\"7\",\"types\":[\"player.hurt\"]}",
                EventContracts.SinceParams.after(7, "player.hurt"));
        same(EventContracts.EVENTS_SINCE.input(), "{\"cursor\":\"7\"}", EventContracts.SinceParams.after(7));
    }

    @Test
    void settingsFactoriesMatchTheContractsDefaults() {
        same(SettingsContracts.PARAMS, "{\"op\":\"read\",\"keys\":[\"autoEat\",\"allowBreak\"]}",
                SettingsContracts.Read.of("autoEat", "allowBreak"));
        same(SettingsContracts.PARAMS, "{\"op\":\"read\"}", SettingsContracts.Read.all());
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("autoEat", true);
        values.put("allowBreak", false);
        same(SettingsContracts.PARAMS, "{\"op\":\"patch\",\"values\":{\"autoEat\":true,\"allowBreak\":false}}",
                SettingsContracts.Patch.of(values));
    }
}
