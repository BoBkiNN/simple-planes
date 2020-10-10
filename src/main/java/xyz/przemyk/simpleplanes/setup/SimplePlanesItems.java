package xyz.przemyk.simpleplanes.setup;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.*;
import net.minecraft.util.ActionResult;
import net.minecraft.util.ActionResultType;
import net.minecraft.util.Hand;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraft.world.World;
import net.minecraftforge.fml.RegistryObject;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.ObjectHolder;
import xyz.przemyk.simpleplanes.SimplePlanesMod;
import xyz.przemyk.simpleplanes.entities.HelicopterEntity;
import xyz.przemyk.simpleplanes.entities.LargePlaneEntity;
import xyz.przemyk.simpleplanes.entities.MegaPlaneEntity;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;
import xyz.przemyk.simpleplanes.items.InformationItem;
import xyz.przemyk.simpleplanes.items.PlaneItem;
import xyz.przemyk.simpleplanes.upgrades.cloud.CloudBlock;

//@ObjectHolder(SimplePlanesMod.MODID)
@SuppressWarnings("unused")
@Mod.EventBusSubscriber(modid = SimplePlanesMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class SimplePlanesItems {

    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, SimplePlanesMod.MODID);


    public static void init() {
        for (String name :
            SimplePlanesMaterials.MATERIALS) {
            ITEMS.register(name + "_plane", () -> new PlaneItem(new Item.Properties().group(SIMPLE_PLANES_ITEM_GROUP), world -> new PlaneEntity(SimplePlanesEntities.PLANE.get(), world, SimplePlanesMaterials.getMaterial(name))));
            ITEMS.register(name + "_large_plane", () -> new PlaneItem(new Item.Properties().group(SIMPLE_PLANES_ITEM_GROUP), world -> new LargePlaneEntity(SimplePlanesEntities.LARGE_PLANE.get(), world, SimplePlanesMaterials.getMaterial(name))));
            ITEMS.register(name + "_helicopter", () -> new PlaneItem(new Item.Properties().group(SIMPLE_PLANES_ITEM_GROUP), world -> new HelicopterEntity(SimplePlanesEntities.HELICOPTER.get(), world, SimplePlanesMaterials.getMaterial(name))));
            ITEMS.register(name + "_mega_plane", () -> new PlaneItem(new Item.Properties().group(SIMPLE_PLANES_ITEM_GROUP), world -> new MegaPlaneEntity(SimplePlanesEntities.MEGA_PLANE.get(), world, SimplePlanesMaterials.getMaterial(name))));

        }

        ITEMS.register(FMLJavaModLoadingContext.get().getModEventBus());
    }

    public static final ItemGroup SIMPLE_PLANES_ITEM_GROUP = new ItemGroup("simpleplanes") {
        @Override
        public ItemStack createIcon() {
            return new ItemStack(ITEMS.getEntries().iterator().next().get());
        }
    };
    @ObjectHolder(SimplePlanesMod.MODID + ":oak_plane")
    public static final Item OAK_PLANE = null;

    public static final RegistryObject<Item> PROPELLER = ITEMS.register("propeller", () -> new Item(new Item.Properties().group(SIMPLE_PLANES_ITEM_GROUP)));
    public static final RegistryObject<Item> FURNACE_ENGINE = ITEMS
        .register("furnace_engine", () -> new Item(new Item.Properties().group(SIMPLE_PLANES_ITEM_GROUP)));

    public static final RegistryObject<Item> SPRAYER = ITEMS
        .register("sprayer", () -> new InformationItem(new TranslationTextComponent("description.simpleplanes.sprayer")));
    public static final RegistryObject<Item> BOOSTER = ITEMS
        .register("booster", () -> new InformationItem(new TranslationTextComponent("description.simpleplanes.booster")));
    public static final RegistryObject<Item> FLOATY_BEDDING = ITEMS
        .register("floaty_bedding", () -> new InformationItem(new TranslationTextComponent("description.simpleplanes.floaty_bedding")));
    public static final RegistryObject<Item> SHOOTER = ITEMS
        .register("shooter", () -> new InformationItem(new TranslationTextComponent("description.simpleplanes.shooter")));
    public static final RegistryObject<Item> FOLDING = ITEMS
        .register("folding", () -> new InformationItem(new TranslationTextComponent("description.simpleplanes.folding")));
    public static final RegistryObject<Item> HEALING = ITEMS
        .register("healing", () -> new InformationItem(new TranslationTextComponent("description.simpleplanes.healing")));
    public static final RegistryObject<Item> CLOUD = ITEMS
        .register("cloud", () -> new InformationItem(new TranslationTextComponent("description.simpleplanes.cloud")){
            @Override
            public ActionResult<ItemStack> onItemRightClick(World worldIn, PlayerEntity playerIn, Hand handIn) {
                ItemStack itemstack = playerIn.getHeldItem(handIn);
                playerIn.setActiveHand(handIn);
                CloudBlock.placeCloud(playerIn.getPosition(),worldIn);
                return ActionResult.resultConsume(itemstack);
            }

            @Override
            public ActionResultType onItemUse(ItemUseContext context) {
                CloudBlock.placeCloud(context.getPos(),context.getWorld());
                return ActionResultType.CONSUME;
            }
        });
    public static final RegistryObject<Item> CHARGER_BLOCK = ITEMS
        .register("charger_block", () -> new BlockItem(SimplePlanesBlocks.CHARGER_BLOCK.get(),(new Item.Properties()).group(SIMPLE_PLANES_ITEM_GROUP)));
    public static final RegistryObject<Item> FUELING_BLOCK = ITEMS
        .register("fueling_block", () -> new BlockItem(SimplePlanesBlocks.FUELING_BLOCK.get(),(new Item.Properties()).group(SIMPLE_PLANES_ITEM_GROUP)));

}
