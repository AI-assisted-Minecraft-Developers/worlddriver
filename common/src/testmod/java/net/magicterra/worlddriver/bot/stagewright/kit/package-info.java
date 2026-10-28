/**
 * The shared kit for worlddriver's in-game tests on StageWright's test path ({@code net.magicterra.stagewright.test}):
 * what the old scenes' helpers became.
 *
 * <table>
 *   <caption>Old helper → what to use</caption>
 *   <tr><th>old</th><th>new</th></tr>
 *   <tr><td>{@code Harness.pin(ctx)}, {@code pinUntimed}, {@code untimed}, {@code compiledDefaults}</td>
 *       <td>{@link net.magicterra.worlddriver.bot.stagewright.kit.Settings#pin Settings.pin(game)} and its siblings;
 *       the undo is registered for you</td></tr>
 *   <tr><td>{@code Harness.set(k, v)}, {@code Harness.flag(k)}</td>
 *       <td>{@code settings.set(k, v)}, {@code settings.flag(k)} on the handle a pin returned</td></tr>
 *   <tr><td>{@code Harness.pinSelf(ctx)}</td>
 *       <td>{@link net.magicterra.worlddriver.bot.stagewright.kit.Settings#mirrorTo settings.mirrorTo(client)};
 *       {@link net.magicterra.worlddriver.bot.stagewright.kit.ClientRig#adopt ClientRig.adopt} does it</td></tr>
 *   <tr><td>{@code SceneBot.spawn(ctx, x, y, z)}</td>
 *       <td>{@link net.magicterra.worlddriver.bot.stagewright.kit.SceneBot#spawn SceneBot.spawn(game, plot.at(x, y, z))},
 *       or {@code SceneBot.of(game, bot)} over a {@code @Bot.Parked Bot} parameter</td></tr>
 *   <tr><td>{@code bot.tick()} loops, {@code Harness.tick(ref, n)}</td>
 *       <td>the same {@code SceneBot} verbs; a loop that must see no world tick between the bot's ticks goes inside
 *       one {@code game.runOnServer(s -> …)}</td></tr>
 *   <tr><td>{@code Harness.tick()}, {@code Harness.busy()}, {@code Harness.clearBots(ctx)}</td>
 *       <td>nothing: the tick list is shared with other tests. Tick and ask your own bots; spawned bots leave when the
 *       test ends</td></tr>
 *   <tr><td>{@code ClientHelm}, {@code ClientRegistry}, {@code SceneCalls}, {@code Legs}</td>
 *       <td>{@link net.magicterra.worlddriver.bot.stagewright.kit.ClientRig} over the {@code Client} and
 *       {@code Player} parameters; {@code client.api(XxxBlockingApi::of)} for any other client call</td></tr>
 *   <tr><td>{@code SceneCalls.askByName}, {@code SceneJson.route}</td>
 *       <td>{@link net.magicterra.worlddriver.bot.stagewright.kit.Calls#byName Calls.byName(registry, method, map)},
 *       {@link net.magicterra.worlddriver.bot.stagewright.kit.ByName} for the Mob families' readings</td></tr>
 *   <tr><td>{@code Calls.started}, {@code Calls.now}</td>
 *       <td>{@link net.magicterra.worlddriver.bot.stagewright.kit.Calls#started Calls.started}; the generated
 *       {@code XxxBlockingApi} callers answer on the calling thread, so {@code now} has nothing left to do</td></tr>
 *   <tr><td>{@code Targets}, {@code Routes}, {@code ScenePlan}, {@code SceneArena}, {@code NavWalk},
 *       {@code SimProbes}, {@code SceneJson}</td>
 *       <td>the classes of the same names here</td></tr>
 *   <tr><td>{@code WorldDriverProcessScenes.countItem}/{@code entityBox}, {@code WorldDriverMobScenes.fill}/
 *       {@code settled}, {@code WorldDriverNpcScenes.adoptNpc}</td>
 *       <td>{@link net.magicterra.worlddriver.bot.stagewright.kit.Staging}</td></tr>
 * </table>
 *
 * <p>A ported headless test, whole:
 *
 * <pre>{@code
 * @Scene("wd.scenes")
 * class CombatTest {
 *     @Test
 *     void threatScanHurtAttacker(Game game, @Plot.Centered Plot plot) {
 *         Settings.pin(game);
 *         SceneArena.buildFloor(plot, 0, 0, 0);
 *         SceneBot bot = SceneBot.spawn(game, plot.at(0, 1, 0));
 *         ObserveContracts.Threats scan = bot.threats(24);
 *         game.check(scan.threats().isEmpty(), "nothing threatens the bot on an empty floor: " + scan);
 *     }
 * }
 * }</pre>
 */
package net.magicterra.worlddriver.bot.stagewright.kit;
