AgentTest.run("01_handshake: system.version returns expected fields", function(t) {
    var info = Agent.system.version();
    t.assertEqual(info.modid, "agent_driver", "modid mismatch");
    t.assertEqual(info.version, "0.1.0-dev", "version mismatch");
    t.assertTrue(info.uptimeMs >= 0, "uptimeMs should be non-negative");
});
