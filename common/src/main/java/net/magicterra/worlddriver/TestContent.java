package net.magicterra.worlddriver;

/**
 * A construction-time entry for content that ships only with the testmod: registries
 * ({@code DeferredRegister.register()} must run while the mod is being constructed, on both
 * loaders) and any event subscriptions the test content wants. Discovered through
 * {@link java.util.ServiceLoader} by {@link WorldDriverCommon#installTestContent}, which both
 * loader entries call from their constructor.
 *
 * <p>The published jar carries no implementation, so the discovery loop is empty there and
 * nothing happens — the same shape as StageWright's {@code SceneProvider}. Commands are not part
 * of this interface: an implementation subscribes to Architectury's
 * {@code CommandRegistrationEvent} itself and registers its own {@code worlddriver} literal, which
 * Brigadier merges under the driver's root.
 *
 * <p>This interface sits in the driver's root package rather than beside its implementations in
 * {@code testcontent}: on NeoForge the main and testmod outputs are two JPMS modules, and a package
 * present in both is a split package the module layer refuses to build ("Modules generated_… and
 * worlddriver export package … to module rhino"). Every testmod package must therefore be one the
 * main output does not have.
 */
public interface TestContent {
    /** Runs once, during mod construction, before any server or client exists. */
    void register();
}
