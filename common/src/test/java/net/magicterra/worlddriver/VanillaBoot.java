package net.magicterra.worlddriver;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.TagLoader;

/**
 * Vanilla's registries AND its tags, for a unit test that reads block states the way a running game does.
 *
 * <p>{@link Bootstrap#bootStrap()} alone fills the registries but leaves every tag empty — a server binds tags from its
 * data packs when it starts, and a unit test has no server. So without this, water is not {@code FluidTags.WATER}, a
 * vine is not climbable, a log is not a log and a pickaxe mines stone at the bare-handed rate, and a planner test over
 * real blocks plans over a different world while looking green. This loads the block, item and fluid tags out of the
 * vanilla data pack and binds them, once per JVM.
 */
public final class VanillaBoot {
    private VanillaBoot() {}

    private static boolean done;

    public static synchronized void boot() {
        if (done) {
            return;
        }
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        try (CloseableResourceManager rm = new MultiPackResourceManager(
                PackType.SERVER_DATA, List.of(ServerPacksSource.createVanillaPackSource()))) {
            bind(BuiltInRegistries.BLOCK, Registries.BLOCK, rm);
            bind(BuiltInRegistries.ITEM, Registries.ITEM, rm);
            bind(BuiltInRegistries.FLUID, Registries.FLUID, rm);
        }
        if (!net.minecraft.world.level.material.Fluids.WATER.defaultFluidState().is(net.minecraft.tags.FluidTags.WATER)
                || !net.minecraft.world.level.block.Blocks.STONE
                        .defaultBlockState()
                        .is(net.minecraft.tags.BlockTags.MINEABLE_WITH_PICKAXE)) {
            throw new IllegalStateException("vanilla tags did not bind: every tag test below would read an empty tag");
        }
        done = true;
    }

    private static <T> void bind(Registry<T> registry, ResourceKey<? extends Registry<T>> key, ResourceManager rm) {
        TagLoader<Holder<T>> loader = new TagLoader<>(id -> registry.getHolder(id), Registries.tagsDirPath(key));
        Map<TagKey<T>, List<Holder<T>>> tags = new HashMap<>();
        for (Map.Entry<ResourceLocation, Collection<Holder<T>>> e :
                loader.loadAndBuild(rm).entrySet()) {
            tags.put(TagKey.create(key, e.getKey()), List.copyOf(e.getValue()));
        }
        registry.bindTags(tags);
    }
}
