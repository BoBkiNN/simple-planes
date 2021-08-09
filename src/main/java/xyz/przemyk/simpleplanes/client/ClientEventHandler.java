package xyz.przemyk.simpleplanes.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.color.item.ItemColors;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.CameraType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import com.mojang.math.Quaternion;
import com.mojang.math.Vector3f;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.*;
import net.minecraftforge.event.TickEvent.Phase;
import net.minecraftforge.event.TickEvent.PlayerTickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fmlclient.registry.ClientRegistry;
import org.lwjgl.glfw.GLFW;
import xyz.przemyk.simpleplanes.MathUtil;
import xyz.przemyk.simpleplanes.SimplePlanesMod;
import xyz.przemyk.simpleplanes.client.gui.*;
import xyz.przemyk.simpleplanes.client.render.PlaneItemColors;
import xyz.przemyk.simpleplanes.client.render.PlaneRenderer;
import xyz.przemyk.simpleplanes.client.render.UpgradesModels;
import xyz.przemyk.simpleplanes.client.render.models.*;
import xyz.przemyk.simpleplanes.entities.HelicopterEntity;
import xyz.przemyk.simpleplanes.entities.PlaneEntity;
import xyz.przemyk.simpleplanes.network.BoostPacket;
import xyz.przemyk.simpleplanes.network.OpenEngineInventoryPacket;
import xyz.przemyk.simpleplanes.network.OpenInventoryPacket;
import xyz.przemyk.simpleplanes.network.PlaneNetworking;
import xyz.przemyk.simpleplanes.setup.SimplePlanesContainers;
import xyz.przemyk.simpleplanes.setup.SimplePlanesEntities;
import xyz.przemyk.simpleplanes.setup.SimplePlanesItems;
import xyz.przemyk.simpleplanes.setup.SimplePlanesUpgrades;
import xyz.przemyk.simpleplanes.upgrades.booster.BoosterModel;
import xyz.przemyk.simpleplanes.upgrades.floating.FloatingModel;
import xyz.przemyk.simpleplanes.upgrades.floating.HelicopterFloatingModel;
import xyz.przemyk.simpleplanes.upgrades.floating.LargeFloatingModel;
import xyz.przemyk.simpleplanes.upgrades.shooter.ShooterModel;

@Mod.EventBusSubscriber(Dist.CLIENT)
public class ClientEventHandler {

    @OnlyIn(Dist.CLIENT)
    public static KeyMapping boostKey;
    @OnlyIn(Dist.CLIENT)
    public static KeyMapping openEngineInventoryKey;

    static {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(ClientEventHandler::planeColor);
        FMLJavaModLoadingContext.get().getModEventBus().addListener(ClientEventHandler::reloadTextures);
    }

    public static void clientSetup() {
        boostKey = new KeyMapping("key.plane_boost.desc", GLFW.GLFW_KEY_SPACE, "key.simpleplanes.category");
        openEngineInventoryKey = new KeyMapping("key.plane_engine_open.desc", GLFW.GLFW_KEY_X, "key.simpleplanes.category");
        ClientRegistry.registerKeyBinding(boostKey);
        ClientRegistry.registerKeyBinding(openEngineInventoryKey);

        MenuScreens.register(SimplePlanesContainers.PLANE_WORKBENCH.get(), PlaneWorkbenchScreen::new);
        MenuScreens.register(SimplePlanesContainers.UPGRADES_REMOVAL.get(), RemoveUpgradesScreen::new);
        MenuScreens.register(SimplePlanesContainers.STORAGE.get(), StorageScreen::new);
        MenuScreens.register(SimplePlanesContainers.FURNACE_ENGINE.get(), FurnaceEngineScreen::new);
        MenuScreens.register(SimplePlanesContainers.ELECTRIC_ENGINE.get(), ElectricEngineScreen::new);
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        EntityModelSet entityModelSet = Minecraft.getInstance().getEntityModels();
        event.registerEntityRenderer(SimplePlanesEntities.PLANE.get(), context -> new PlaneRenderer<>(context, new PlaneModel(entityModelSet.bakeLayer(PlanesModelLayers.PLANE_LAYER)), new PropellerModel(entityModelSet.bakeLayer(PlanesModelLayers.PROPELLER_LAYER)), 0.6f));
        event.registerEntityRenderer(SimplePlanesEntities.LARGE_PLANE.get(), context -> new PlaneRenderer<>(context, new PlaneModel(entityModelSet.bakeLayer(PlanesModelLayers.LARGE_PLANE_LAYER)), new PropellerModel(entityModelSet.bakeLayer(PlanesModelLayers.PROPELLER_LAYER)), 1.0f));
        event.registerEntityRenderer(SimplePlanesEntities.HELICOPTER.get(), context -> new PlaneRenderer<>(context, new PlaneModel(entityModelSet.bakeLayer(PlanesModelLayers.HELICOPTER_LAYER)), new HelicopterPropellerModel(entityModelSet.bakeLayer(PlanesModelLayers.HELICOPTER_PROPELLER_LAYER)), 0.6f));

        UpgradesModels.BOOSTER = new BoosterModel(entityModelSet.bakeLayer(PlanesModelLayers.BOOSTER));
        UpgradesModels.SHOOTER = new ShooterModel(entityModelSet.bakeLayer(PlanesModelLayers.SHOOTER));
        UpgradesModels.FLOATING = new FloatingModel(entityModelSet.bakeLayer(PlanesModelLayers.FLOATING));
        UpgradesModels.LARGE_FLOATING = new LargeFloatingModel(entityModelSet.bakeLayer(PlanesModelLayers.LARGE_FLOATING));
        UpgradesModels.HELICOPTER_FLOATING = new HelicopterFloatingModel(entityModelSet.bakeLayer(PlanesModelLayers.HELICOPTER_FLOATING));
    }

    private static boolean playerRotationNeedToPop = false;

    public static void planeColor(ColorHandlerEvent.Item event) {
        ItemColors itemColors = event.getItemColors();
        SimplePlanesItems.getPlaneItems().forEach(item -> itemColors.register(PlaneItemColors::getColor, item));
    }

    public static void reloadTextures(TextureStitchEvent.Post event) {
        PlaneItemColors.clearCache();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRenderPre(RenderLivingEvent.Pre<LivingEntity, ?> event) {
        LivingEntity livingEntity = event.getEntity();
        Entity entity = livingEntity.getRootVehicle();
        if (entity instanceof PlaneEntity planeEntity) {
            PoseStack matrixStack = event.getMatrixStack();
            matrixStack.pushPose();
            playerRotationNeedToPop = true;
            double firstPersonYOffset = 0.7D;
            boolean isPlayerRidingInFirstPersonView = Minecraft.getInstance().player != null && planeEntity.hasPassenger(Minecraft.getInstance().player)
                && (Minecraft.getInstance()).options.cameraType == CameraType.FIRST_PERSON;
            if (isPlayerRidingInFirstPersonView) {
                matrixStack.translate(0.0D, firstPersonYOffset, 0.0D);
            }

            matrixStack.translate(0, 0.7, 0);
            Quaternion quaternion = MathUtil.lerpQ(event.getPartialRenderTick(), planeEntity.getQ_Prev(), planeEntity.getQ_Client());
            quaternion.set(quaternion.i(), -quaternion.j(), -quaternion.k(), quaternion.r());
            matrixStack.mulPose(quaternion);
            float rotationYaw = MathUtil.lerpAngle(event.getPartialRenderTick(), entity.yRotO, entity.getYRot());

            matrixStack.mulPose(Vector3f.YP.rotationDegrees(rotationYaw));
            matrixStack.translate(0, -0.7, 0);
            if (isPlayerRidingInFirstPersonView) {
                matrixStack.translate(0.0D, -firstPersonYOffset, 0.0D);
            }
            if (MathUtil.degreesDifferenceAbs(planeEntity.rotationRoll, 0) > 90) {
                livingEntity.yHeadRot = planeEntity.getYRot() * 2 - livingEntity.yHeadRot;
            }
            if (MathUtil.degreesDifferenceAbs(planeEntity.prevRotationRoll, 0) > 90) {
                livingEntity.yHeadRotO = planeEntity.yRotO * 2 - livingEntity.yHeadRotO;
            }
        }
    }

    @SuppressWarnings("rawtypes")
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRenderPost(RenderLivingEvent.Post event) {
        if (playerRotationNeedToPop) {
            playerRotationNeedToPop = false;
            event.getMatrixStack().popPose();
            Entity entity = event.getEntity().getRootVehicle();
            PlaneEntity planeEntity = (PlaneEntity) entity;

            if (MathUtil.degreesDifferenceAbs(planeEntity.rotationRoll, 0) > 90) {
                event.getEntity().yHeadRot = planeEntity.getYRot() * 2 - event.getEntity().yHeadRot;
            }
            if (MathUtil.degreesDifferenceAbs(planeEntity.prevRotationRoll, 0) > 90) {
                event.getEntity().yHeadRotO = planeEntity.yRotO * 2 - event.getEntity().yHeadRotO;
            }
        }
    }

    private static boolean oldBoostState = false;

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onClientPlayerTick(PlayerTickEvent event) {
        final Player player = event.player;
        if ((event.phase == Phase.END) && (player instanceof LocalPlayer)) {
            if (player.getVehicle() instanceof PlaneEntity planeEntity) {
                Minecraft mc = Minecraft.getInstance();
                if (mc.options.cameraType == CameraType.FIRST_PERSON) {
                    float yawDiff = planeEntity.getYRot() - planeEntity.yRotO;
                    player.setYRot(player.getYRot() + yawDiff);
                    float relativePlayerYaw = Mth.wrapDegrees(player.getYRot() - planeEntity.getYRot());
                    float clampedRelativePlayerYaw = Mth.clamp(relativePlayerYaw, -105.0F, 105.0F);

                    float diff = (clampedRelativePlayerYaw - relativePlayerYaw);
                    player.yRotO += diff;
                    player.setYRot(player.getYRot() + diff);
                    player.setYHeadRot(player.getYRot());

                    relativePlayerYaw = Mth.wrapDegrees(player.getXRot() - 0);
                    clampedRelativePlayerYaw = Mth.clamp(relativePlayerYaw, -50, 50);
                    float perc = (clampedRelativePlayerYaw - relativePlayerYaw) * 0.5f;
                    player.xRotO += perc;
                    player.setXRot(player.getXRot() + perc);
                } else {
                    planeEntity.applyYawToEntity(player);
                }

                if (planeEntity.engineUpgrade != null && mc.screen == null && mc.getOverlay() == null && openEngineInventoryKey.consumeClick() && planeEntity.engineUpgrade.canOpenGui()) {
                    PlaneNetworking.INSTANCE.sendToServer(new OpenEngineInventoryPacket());
                }

                boolean isBoosting = boostKey.isDown();
                if (isBoosting != oldBoostState || Math.random() < 0.1) {
                    PlaneNetworking.INSTANCE.sendToServer(new BoostPacket(isBoosting));
                }
                oldBoostState = isBoosting;
            } else {
                oldBoostState = false;
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onCameraSetup(EntityViewRenderEvent.CameraSetup event) {
        Camera renderInfo = event.getInfo();
        Entity entity = renderInfo.getEntity();
        if (entity instanceof LocalPlayer playerEntity && entity.getVehicle() instanceof PlaneEntity planeEntity) {
            if (renderInfo.isDetached()) {
                renderInfo.move(-renderInfo.getMaxZoom(4.0D * (planeEntity.getCameraDistanceMultiplayer() - 1.0)), 0.0D, 0.0D);
            } else {
                double partialTicks = event.getRenderPartialTicks();

                Quaternion q_prev = planeEntity.getQ_Prev();
                int max = 105;
                float diff = (float) Mth.clamp(MathUtil.wrapSubtractDegrees(planeEntity.yRotO, playerEntity.yRotO), -max, max);
                float pitch = Mth.clamp(event.getPitch(), -45, 45);
                q_prev.mul(Vector3f.YP.rotationDegrees(diff));
                q_prev.mul(Vector3f.XP.rotationDegrees(pitch));
                MathUtil.EulerAngles angles_prev = MathUtil.toEulerAngles(q_prev);

                Quaternion q_client = planeEntity.getQ_Client();
                diff = (float) Mth.clamp(MathUtil.wrapSubtractDegrees(planeEntity.getYRot(), playerEntity.getYRot()), -max, max);
                q_client.mul(Vector3f.YP.rotationDegrees(diff));
                q_client.mul(Vector3f.XP.rotationDegrees(pitch));
                MathUtil.EulerAngles angles = MathUtil.toEulerAngles(q_client);

                event.setPitch(-(float) MathUtil.lerpAngle180(partialTicks, angles_prev.pitch, angles.pitch));
                event.setYaw((float) MathUtil.lerpAngle(partialTicks, angles_prev.yaw, angles.yaw));
                event.setRoll(-(float) MathUtil.lerpAngle(partialTicks, angles_prev.roll, angles.roll));
            }
        }
    }

    public static final ResourceLocation HUD_TEXTURE = new ResourceLocation(SimplePlanesMod.MODID, "textures/gui/plane_hud.png");

    @SubscribeEvent()
    public static void renderGameOverlayPre(RenderGameOverlayEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        int scaledWidth = mc.getWindow().getGuiScaledWidth();
        int scaledHeight = mc.getWindow().getGuiScaledHeight();
        PoseStack matrixStack = event.getMatrixStack();

        if (mc.player.getVehicle() instanceof PlaneEntity planeEntity) {
            if (event.getType() == RenderGameOverlayEvent.ElementType.ALL) {
                mc.getTextureManager().bindForSetup(HUD_TEXTURE); //TODO: ???
                int left_align = scaledWidth / 2 + 91;

                int health = (int) Math.ceil(planeEntity.getHealth());
                float healthMax = planeEntity.getMaxHealth();
                int hearts = (int) (healthMax);

                if (hearts > 10) hearts = 10;

                final int FULL = 0;
                final int EMPTY = 16;
                final int GOLD = 32;
                int right_height = 39;
                int max_row_size = 5;

                for (int heart = 0; hearts > 0; heart += max_row_size) {
                    int top = scaledHeight - right_height;

                    int rowCount = Math.min(hearts, max_row_size);
                    hearts -= rowCount;

                    for (int i = 0; i < rowCount; ++i) {
                        int x = left_align - i * 16 - 16;
                        int vOffset = 35;
                        if (i + heart + 10 < health)
                            blit(matrixStack, 0, x, top, GOLD, vOffset, 16, 9);
                        else if (i + heart < health)
                            blit(matrixStack, 0, x, top, FULL, vOffset, 16, 9);
                        else
                            blit(matrixStack, 0, x, top, EMPTY, vOffset, 16, 9);
                    }
                    right_height += 10;
                }

                if (planeEntity.engineUpgrade != null) {
                    ItemStack offhandStack = mc.player.getOffhandItem();
                    HumanoidArm primaryHand = mc.player.getMainArm();
                    planeEntity.engineUpgrade.renderPowerHUD(matrixStack, (primaryHand == HumanoidArm.LEFT || offhandStack.isEmpty()) ? HumanoidArm.LEFT : HumanoidArm.RIGHT, scaledWidth, scaledHeight, event.getPartialTicks());
                }

                if (planeEntity.mountMessage) {
                    planeEntity.mountMessage = false;
                    if (planeEntity instanceof HelicopterEntity) {
                        mc.gui.setOverlayMessage(new TranslatableComponent("helicopter.onboard", mc.options.keyShift.getTranslatedKeyMessage(),
                            boostKey.getTranslatedKeyMessage()), false);
                    } else {
                        mc.gui.setOverlayMessage(new TranslatableComponent("plane.onboard", mc.options.keyShift.getTranslatedKeyMessage(),
                            boostKey.getTranslatedKeyMessage()), false);
                    }

                }
            } else if (event.getType() == RenderGameOverlayEvent.ElementType.FOOD) {
                event.setCanceled(true);
            }
        }
    }

    public static void renderHotbarItem(PoseStack matrixStack, int x, int y, float partialTicks, ItemStack stack, Minecraft mc) {
        ItemRenderer itemRenderer = mc.getItemRenderer();
        if (!stack.isEmpty()) {
            float f = (float) stack.getUseDuration() - partialTicks;
            if (f > 0.0F) {
                matrixStack.pushPose();
                float f1 = 1.0F + f / 5.0F;
                matrixStack.translate((float) (x + 8), (float) (y + 12), 0.0F);
                matrixStack.scale(1.0F / f1, (f1 + 1.0F) / 2.0F, 1.0F);
                matrixStack.translate((float) (-(x + 8)), (float) (-(y + 12)), 0.0F);
            }

            itemRenderer.renderAndDecorateItem(stack, x, y);
            if (f > 0.0F) {
                matrixStack.popPose();
            }

            itemRenderer.renderGuiItemDecorations(mc.font, stack, x, y);
        }
    }

    public static void blit(PoseStack matrixStack, int blitOffset, int x, int y, int uOffset, int vOffset, int uWidth, int vHeight) {
        GuiComponent.blit(matrixStack, x, y, blitOffset, (float) uOffset, (float) vOffset, uWidth, vHeight, 256, 256);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void planeInventory(GuiOpenEvent event) {
        final LocalPlayer player = Minecraft.getInstance().player;
        if (event.getGui() instanceof InventoryScreen && player.getVehicle() instanceof PlaneEntity plane) {
            if (plane.upgrades.containsKey(SimplePlanesUpgrades.CHEST.getId())) {
                event.setCanceled(true);
                PlaneNetworking.INSTANCE.sendToServer(new OpenInventoryPacket());
            }
        }
    }
}
