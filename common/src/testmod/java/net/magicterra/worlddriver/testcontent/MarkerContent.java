package net.magicterra.worlddriver.testcontent;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

import dev.architectury.registry.CreativeTabRegistry;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import dev.architectury.utils.Env;
import dev.architectury.utils.EnvExecutor;
import net.magicterra.worlddriver.TestContent;
import net.magicterra.worlddriver.WorldDriverCommon;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;

/**
 * The testmod's registry content: the marker block, its block entity, one item per role, and a
 * creative tab holding them. Registered through {@code DeferredRegister} from
 * {@link #register()}, which {@code WorldDriverCommon.installTestContent()} calls during mod
 * construction — the only window both loaders accept registrations in. The ids live under the
 * driver's own mod id because the testmod is folded into the driver's mod on both loaders.
 */
public final class MarkerContent implements TestContent {
    private static final String MOD_ID = WorldDriverCommon.MOD_ID;

    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(MOD_ID, Registries.BLOCK);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(MOD_ID, Registries.ITEM);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(MOD_ID, Registries.BLOCK_ENTITY_TYPE);
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(MOD_ID, Registries.CREATIVE_MODE_TAB);

    /** {@code worlddriver:marker}. No collision, no occlusion, replaceable, breaks instantly; glows like lava when it holds lava. */
    public static final RegistrySupplier<MarkerBlock> MARKER = BLOCKS.register("marker", () -> new MarkerBlock(
            BlockBehaviour.Properties.of().noCollission().noOcclusion().replaceable().instabreak()
                    .pushReaction(PushReaction.DESTROY)
                    .lightLevel(state -> state.getValue(MarkerBlock.FLUID) == MarkerFluid.LAVA ? 15 : 0)));

    /** {@code worlddriver:marker_<role>}, one per {@link MarkerRole}. */
    public static final Map<MarkerRole, RegistrySupplier<MarkerItem>> ITEMS_BY_ROLE = items();

    public static final RegistrySupplier<BlockEntityType<MarkerBlockEntity>> MARKER_ENTITY =
            BLOCK_ENTITIES.register("marker", () ->
                    BlockEntityType.Builder.of(MarkerBlockEntity::new, MARKER.get()).build(null));

    /** The creative tab a tester takes markers from. */
    public static final RegistrySupplier<CreativeModeTab> TAB = TABS.register("markers", () ->
            CreativeTabRegistry.create(builder -> builder
                    .title(Component.translatable("itemGroup." + MOD_ID + ".markers"))
                    .icon(() -> new ItemStack(item(MarkerRole.START).get()))
                    .displayItems((parameters, output) -> {
                        for (MarkerRole role : MarkerRole.values()) output.accept(item(role).get());
                    })));

    public static RegistrySupplier<MarkerItem> item(MarkerRole role) {
        return ITEMS_BY_ROLE.get(role);
    }

    private static Map<MarkerRole, RegistrySupplier<MarkerItem>> items() {
        Map<MarkerRole, RegistrySupplier<MarkerItem>> out = new EnumMap<>(MarkerRole.class);
        for (MarkerRole role : MarkerRole.values()) {
            out.put(role, ITEMS.register("marker_" + role.getSerializedName(), () ->
                    new MarkerItem(role, MARKER.get(), new Item.Properties())));
        }
        return Collections.unmodifiableMap(out);
    }

    @Override
    public void register() {
        BLOCKS.register();
        ITEMS.register();
        BLOCK_ENTITIES.register();
        TABS.register();
        // The client half draws the anchors' boxes and opens their screen. A method reference to
        // a separate class, so nothing here names a client-only type: the dedicated server never
        // links MarkerContentClient.
        EnvExecutor.runInEnv(Env.CLIENT, () -> MarkerContentClient::init);
        SceneVerbs.install();
    }
}
