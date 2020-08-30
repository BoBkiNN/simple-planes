package xyz.przemyk.simpleplanes.entities;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.*;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.network.IPacket;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.particles.IParticleData;
import net.minecraft.particles.ParticleTypes;
import net.minecraft.util.*;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.vector.Quaternion;
import net.minecraft.util.math.vector.Vector2f;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.util.math.vector.Vector3f;
import net.minecraft.world.GameRules;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.network.NetworkHooks;
import xyz.przemyk.simpleplanes.Config;
import xyz.przemyk.simpleplanes.MathUtil;
import xyz.przemyk.simpleplanes.SimplePlanesMod;
import xyz.przemyk.simpleplanes.handler.PlaneNetworking;
import xyz.przemyk.simpleplanes.setup.SimplePlanesDataSerializers;
import xyz.przemyk.simpleplanes.setup.SimplePlanesRegistries;
import xyz.przemyk.simpleplanes.setup.SimplePlanesSounds;
import xyz.przemyk.simpleplanes.setup.SimplePlanesUpgrades;
import xyz.przemyk.simpleplanes.upgrades.Upgrade;
import xyz.przemyk.simpleplanes.upgrades.UpgradeType;
import xyz.przemyk.simpleplanes.upgrades.rocket.RocketUpgrade;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import static net.minecraft.util.math.MathHelper.wrapDegrees;
import static xyz.przemyk.simpleplanes.MathUtil.*;

public class PlaneEntity extends Entity implements IJumpingMount {
    protected static final DataParameter<Integer> FUEL = EntityDataManager.createKey(PlaneEntity.class, DataSerializers.VARINT);
    public static final EntitySize FLYING_SIZE = EntitySize.flexible(2F, 1.5F);
    public static final EntitySize FLYING_SIZE_EASY = EntitySize.flexible(2F, 2F);

    //negative values mean left
    public static final DataParameter<Integer> MOVEMENT_RIGHT = EntityDataManager.createKey(PlaneEntity.class, DataSerializers.VARINT);
    public static final DataParameter<Float> MAX_SPEED = EntityDataManager.createKey(PlaneEntity.class, DataSerializers.FLOAT);
    public static final DataParameter<Quaternion> QUATERNION = EntityDataManager.createKey(PlaneEntity.class, SimplePlanesDataSerializers.QUATERNION_SERIALIZER);
    public Quaternion Q_Client = new Quaternion(Quaternion.ONE);
    public Quaternion Q_Prev = new Quaternion(Quaternion.ONE);
    public static final DataParameter<CompoundNBT> UPGRADES_NBT = EntityDataManager.createKey(PlaneEntity.class, DataSerializers.COMPOUND_NBT);

    public static final AxisAlignedBB COLLISION_AABB = new AxisAlignedBB(-1, 0, -1, 1, 0.5, 1);
    protected int poweredTicks;

    //count how many ticks since on ground
    private int groundTicks;
    public HashMap<ResourceLocation, Upgrade> upgrades = new HashMap<>();
    public float rotationRoll;
    public float prevRotationRoll;
    private float deltaRotation;
    private float deltaRotationLeft;
    private int deltaRotationTicks;

    //EntityType<? extends PlaneEntity> is always AbstractPlaneEntityType but I cannot change it because minecraft
    public PlaneEntity(EntityType<? extends PlaneEntity> entityTypeIn, World worldIn) {
        super(entityTypeIn, worldIn);
        this.stepHeight = 0.9999f;
        setMaxSpeed(1f);
    }

    public PlaneEntity(EntityType<? extends PlaneEntity> entityTypeIn, World worldIn, double x, double y, double z) {
        this(entityTypeIn, worldIn);
        setPosition(x, y, z);
    }

    @Override
    protected void registerData() {
        dataManager.register(FUEL, 0);
        dataManager.register(MOVEMENT_RIGHT, 0);
        dataManager.register(UPGRADES_NBT, new CompoundNBT());
        dataManager.register(QUATERNION, Quaternion.ONE);
        dataManager.register(MAX_SPEED, 0.25f);
    }

    public void addFuel() {
        addFuel(Config.FLY_TICKS_PER_COAL.get());
    }

    public void addFuel(Integer fuel) {
        dataManager.set(FUEL, Math.max(getFuel(), fuel));
    }

    public int getFuel() {
        return dataManager.get(FUEL);
    }

    public float getMaxSpeed() {
        return dataManager.get(MAX_SPEED);
    }

    public void setMaxSpeed(float max_speed) {
        dataManager.set(MAX_SPEED, max_speed);
    }

    public Quaternion getQ() {
        return new Quaternion(dataManager.get(QUATERNION));
    }

    public void setQ(Quaternion q) {
        dataManager.set(QUATERNION, q);
    }

    public Quaternion getQ_Client() {
        return new Quaternion(Q_Client);
    }

    public void setQ_Client(Quaternion q) {
        Q_Client = q;
    }

    public Quaternion getQ_Prev() {
        return Q_Prev.copy();
    }

    public void setQ_prev(Quaternion q) {
        Q_Prev = q;
    }

    public boolean isPowered() {
        return dataManager.get(FUEL) > 0 || isCreative();
    }

    @Override
    public ActionResultType processInitialInteract(PlayerEntity player, Hand hand) {
        if (player.isSneaking() && player.getHeldItem(hand).isEmpty()) {
            boolean hasplayer = false;
            for (Entity passenger : getPassengers()) {
                if ((passenger instanceof PlayerEntity)) {
                    hasplayer = true;
                    break;
                }
            }
            if (!hasplayer || Config.THIEF.get()) {
                this.removePassengers();
            }
            return ActionResultType.SUCCESS;
        } else if (tryToAddUpgrade(player, player.getHeldItem(hand))) {
            return ActionResultType.SUCCESS;
        }
        return !world.isRemote && player.startRiding(this) ? ActionResultType.SUCCESS : ActionResultType.FAIL;
    }

    public boolean tryToAddUpgrade(PlayerEntity player, ItemStack itemStack) {
        for (UpgradeType upgradeType : SimplePlanesRegistries.UPGRADE_TYPES.getValues()) {
            if (upgradeType.IsThisItem(itemStack) && canAddUpgrade(upgradeType)) {
                Upgrade upgrade = upgradeType.instanceSupplier.apply(this);
                upgrade.onApply(itemStack, player);
                if (!player.isCreative()) {
                    itemStack.shrink(1);
                }
                upgrades.put(upgradeType.getRegistryName(), upgrade);
                upgradeChanged();
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("deprecation")
    @Override
    public boolean attackEntityFrom(DamageSource source, float amount) {
        if (this.isInvulnerableTo(source)) {
            return false;
        }
        if (!(source.getTrueSource() instanceof PlayerEntity && ((PlayerEntity) source.getTrueSource()).abilities.isCreativeMode)
                && world.getGameRules().getBoolean(GameRules.DO_ENTITY_DROPS) && !this.removed) {
            dropItem();
        }
        if (!this.world.isRemote && !this.removed) {
            remove();
            return true;
        }
        return false;
    }

    @SuppressWarnings("rawtypes")
    protected void dropItem() {
        ItemStack itemStack = new ItemStack(((AbstractPlaneEntityType) getType()).dropItem);
        if (upgrades.containsKey(SimplePlanesUpgrades.FOLDING.getId())) {
            itemStack.setTagInfo("EntityTag", serializeNBT());
        } else {
            for (Upgrade upgrade : upgrades.values()) {
                final ItemStack item = upgrade.getItem();
                if (item != null) {
                    entityDropItem(item);
                }
            }
        }
        entityDropItem(itemStack);
    }

    public Vector2f getHorizontalFrontPos() {
        return new Vector2f(-MathHelper.sin(rotationYaw * ((float) Math.PI / 180F)), MathHelper.cos(rotationYaw * ((float) Math.PI / 180F)));
    }

    @Override
    public EntitySize getSize(Pose poseIn) {
        if (this.getControllingPassenger() instanceof PlayerEntity) {
            return Config.EASY_FLIGHT.get() ? FLYING_SIZE_EASY : FLYING_SIZE;
        }
        return super.getSize(poseIn);
    }

    @Override
    public void tick() {
        super.tick();

        if (Double.isNaN(getMotion().length())) {
            setMotion(Vector3d.ZERO);
        }
        prevRotationYaw = rotationYaw;
        prevRotationPitch = rotationPitch;
        prevRotationRoll = rotationRoll;
        if (isPowered()) {
            if (poweredTicks % 50 == 0) {
                playSound(SimplePlanesSounds.PLANE_LOOP.get(), 0.05F, 1.0F);
            }
            ++poweredTicks;
        } else {
            poweredTicks = 0;
        }

        if (world.isRemote && !canPassengerSteer()) {

            tickLerp();
            this.setMotion(Vector3d.ZERO);
            EulerAngles eulerAngles1 = toEulerAngles(getQ_Client());
            rotationPitch = (float) eulerAngles1.pitch;
            rotationYaw = (float) eulerAngles1.yaw;
            rotationRoll = (float) eulerAngles1.roll;

            float d = (float) wrapSubtractDegrees(prevRotationYaw, this.rotationYaw);
            if (rotationRoll >= 90 && prevRotationRoll <= 90) {
                d = 0;
            }
            deltaRotationLeft += d;
            deltaRotationLeft = wrapDegrees(deltaRotationLeft);
            int diff = 5;
            deltaRotation = Math.min(Math.abs(deltaRotationLeft), diff) * Math.signum(deltaRotationLeft);
            deltaRotationLeft -= deltaRotation;

            return;
        }
        double max_speed = 3;
        double max_push_speed = getMaxSpeed() * 10;
        double take_off_speed = 0.3;
        float max_lift = 2;

        double lift_factor = 10;

        double gravity = -0.03;

        final double drag = 0.002;
        double drag_mul = 0.0005;
        double drag_quad = 0.001;

        float push = 0.06f;
        float ground_push = 0.01f;
        float passive_engine_push = 0.025f;

        float motion_to_rotation = 0.05f;
        float pitch_to_motion = 0.2f;

        if (this.hasNoGravity()) {
            gravity = 0;
            max_lift = 0;
            push = 0.00f;

            passive_engine_push = 0;
        }

        LivingEntity controllingPassenger = (LivingEntity) getControllingPassenger();
        float moveForward = controllingPassenger instanceof PlayerEntity ? controllingPassenger.moveForward : 0;
        double turn_threshold = Config.TURN_THRESHOLD.get() / 100d;
        if (Math.abs(moveForward) < turn_threshold) {
            moveForward = 0;
        }
        float moveStrafing = controllingPassenger instanceof PlayerEntity ? controllingPassenger.moveStrafing : 0;
        if (Math.abs(moveStrafing) < turn_threshold) {
            moveStrafing = 0;
        }
        boolean passengerSprinting = controllingPassenger != null && controllingPassenger.isSprinting();
        Boolean easy = Config.EASY_FLIGHT.get();

        Quaternion q;
        if (world.isRemote) {
            q = getQ_Client();
        } else {
            q = getQ();
        }

        EulerAngles eulerAnglesOld = toEulerAngles(q).copy();

        Vector3d oldMotion = getMotion();
        recalculateSize();
        int fuel = dataManager.get(FUEL);
        if (fuel > 0)
        {
            fuel -= passengerSprinting ? 4 : 1;
            dataManager.set(FUEL, fuel);
        }
        Vector3d motion = getMotion();

        //motion and rotation interpolation + lift.
        if (motion.length() > 0.05) {
            float yaw = MathUtil.getYaw(motion);
            float pitch = MathUtil.getPitch(motion);
            if (degreesDifferenceAbs(yaw, rotationYaw) > 5 && (getOnGround() || isAboveWater())) {
                setMotion(motion.scale(0.98));
            }

            float d = (float) degreesDifferenceAbs(pitch, rotationPitch);
            if (d > 180) {
                d = d - 180;
            }
            //            d/=3600;
            d /= 60;
            d = Math.min(1, d);
            d *= d;
            d = 1 - d;
            //            speed = getMotion().length()*(d);
            double speed = getMotion().length();
            double lift = Math.min(speed * lift_factor, max_lift) * d;
            double cos_roll = (1 + 4 * Math.max(Math.cos(Math.toRadians(degreesDifferenceAbs(rotationRoll, 0))), 0)) / 5;
            lift *= cos_roll;
            d *= cos_roll;

            setMotion(rotationToVector(lerpAngle180(0.1f, yaw, rotationYaw),
                    lerpAngle180(pitch_to_motion * d, pitch, rotationPitch) + lift,
                    speed));
            if (!getOnGround() && !isAboveWater() && motion.length() > 0.1) {

                if (degreesDifferenceAbs(pitch, rotationPitch) > 90) {
                    pitch = wrapDegrees(pitch + 180);
                }
                if (Math.abs(rotationPitch) < 85) {

                    yaw = MathUtil.getYaw(getMotion());
                    if (degreesDifferenceAbs(yaw, rotationYaw) > 90) {
                        yaw = yaw - 180;
                    }
                    Quaternion q1 = toQuaternion(yaw, pitch, rotationRoll);
                    q = lerpQ(motion_to_rotation, q, q1);
                }

            }
        }
        boolean b = true;
        //pitch + movement speed
        if ((getOnGround() || isAboveWater())) {
            if (groundTicks < 0) {
                groundTicks = 5;
            } else {
                groundTicks--;
            }
            float pitch = isLarge() ? 10 : 15;
            if ((isPowered() && moveForward > 0.0F) || isAboveWater()) {
                pitch = 0;
            } else if (getMotion().length() > take_off_speed) {
                pitch /= 2;
            }
            rotationPitch = lerpAngle(0.1f, rotationPitch, pitch);

            if (degreesDifferenceAbs(rotationPitch, 0) > 1 && getMotion().length() < 0.1) {
                push = 0;
            }
            if (getMotion().length() < take_off_speed) {
                //                rotationPitch = lerpAngle(0.2f, rotationPitch, pitch);
                b = false;
                //                push = 0;
            }
            if (moveForward < 0) {
                push = -ground_push;
            }
            if (!isPowered() || moveForward == 0) {
                push = 0;
            }
            float f;
            BlockPos pos = new BlockPos(this.getPosX(), this.getPosY() - 1.0D, this.getPosZ());
            f = this.world.getBlockState(pos).getSlipperiness(this.world, pos, this);
            drag_mul *= 20 * (3 - f);

        } else if (!passengerSprinting) {
            push = passive_engine_push;
        }

        {
            groundTicks--;
            float pitch = 0f;
            if (moveForward > 0.0F) {
                pitch = passengerSprinting ? 2 : 1f;
            } else {
                if (moveForward < 0.0F) {
                    pitch = passengerSprinting ? -2 : -1;
                }
            }
            if (!isPowered()) {
                push = 0;
            }
            if (b) {
                rotationPitch += pitch;
            }
        }
        motion = this.getMotion();
        double speed = motion.length();
        speed -= speed * speed * drag_quad + speed * drag_mul + drag;
        speed = Math.max(speed, 0);
        if (speed > max_speed) {
            speed = MathHelper.lerp(0.2, speed, max_speed);
        }

        if (push != 0) {
            push *= Math.max(1 - speed / (max_push_speed * (push + 0.05)), 0);
        }

        //        if (speed > max_speed)
        //        {
        //            //            double i = (speed / max_speed);
        //            //            speed = MathHelper.lerp(drag_quad * i, speed, max_speed);
        //        }
        if (speed == 0) {
            motion = Vector3d.ZERO;
        }
        if (motion.length() > 0)
            motion = motion.scale(speed / motion.length());

        Vector3f v = transformPos(new Vector3f(0, 0, push));
        motion = motion.add(v.getX(), v.getY(), v.getZ());
        //        v = transformPos(new Vector3f(0, Math.min((float) (speed_x * lift_factor), max_lift), 0));
        //        motion = motion.add(0, v.getY(), 0);

        motion = motion.add(0, gravity, 0);

        this.setMotion(motion);

        //rotating (roll + yaw)
        //########
        float f1 = 1f;
        double turn = 0;

        if (getOnGround() || isAboveWater() || !passengerSprinting || easy) {
            int yawdiff = 2;
            float roll = rotationRoll;
            if (degreesDifferenceAbs(rotationPitch, 0) < 45) {
                for (int i = 0; i < 360; i += 180) {
                    if (MathHelper.degreesDifferenceAbs(rotationRoll, i) < 80) {
                        roll = lerpAngle(0.1f * f1, rotationRoll, i);
                        break;
                    }
                }
            }
            int r = 15;

            if (getOnGround() || isAboveWater()) {
                turn = moveStrafing > 0 ? yawdiff : moveStrafing == 0 ? 0 : -yawdiff;
                rotationRoll = roll;

            } else if (degreesDifferenceAbs(rotationRoll, 0) > 30) {
                turn = moveStrafing > 0 ? -yawdiff : moveStrafing == 0 ? 0 : yawdiff;
                rotationRoll = roll;
            } else {
                if (moveStrafing == 0) {
                    rotationRoll = lerpAngle180(0.2f, rotationRoll, 0);
                } else if (moveStrafing > 0) {
                    rotationRoll = MathHelper.clamp(rotationRoll + f1, 0, r);
                } else if (moveStrafing < 0) {
                    rotationRoll = MathHelper.clamp(rotationRoll - f1, -r, 0);
                }
                final double roll_old = toEulerAngles(getQ()).roll;
                if (degreesDifferenceAbs(roll_old, 0) < 90) {
                    turn = MathHelper.clamp(roll_old / 5.0f, -yawdiff, yawdiff);
                } else {
                    turn = MathHelper.clamp((180 - roll_old) / 5.0f, -yawdiff, yawdiff);
                }
                if (moveStrafing == 0) {
                    turn = 0;
                }
            }

        } else if (moveStrafing == 0) {
            for (int i = 0; i < 360; i += 180) {
                if (MathHelper.degreesDifferenceAbs(rotationRoll, i) < 80) {
                    rotationRoll = lerpAngle(0.01f * f1, rotationRoll, i);
                    break;
                }
            }

        } else if (moveStrafing > 0) {
            rotationRoll += f1;
        } else if (moveStrafing < 0) {
            rotationRoll -= f1;
        }

        rotationYaw -= turn;

        //upgrades
        HashSet<Upgrade> upgradesToRemove = new HashSet<>();
        for (Upgrade upgrade : upgrades.values()) {
            if (upgrade.tick()) {
                upgradesToRemove.add(upgrade);
            }
        }

        for (Upgrade upgrade : upgradesToRemove) {
            upgrades.remove(upgrade.getType().getRegistryName());
        }

        //do not move when slow
        double l = 0.002;
        if (oldMotion.length() < l && getMotion().length() < l) {
            this.setMotion(Vector3d.ZERO);
        }
        // ths code is for motion to work correctly, copied from ItemEntity, maybe there is some better solution but idk
        recalculateSize();
        recenterBoundingBox();
        if (!this.onGround || horizontalMag(this.getMotion()) > (double) 1.0E-5F || (this.ticksExisted + this.getEntityId()) % 4 == 0) {
            double speed_before = Math.sqrt(horizontalMag(this.getMotion()));
            boolean onGroundOld = this.onGround;
            if (getMotion().length() > 0.5 || moveForward != 0) {
                onGround = true;
            }
            this.move(MoverType.SELF, this.getMotion());
            onGround = ((motion.getY()) == 0.0) ? onGroundOld : onGround;
            if (this.collidedHorizontally && !this.world.isRemote && Config.PLANE_CRUSH.get() && groundTicks <= 0) {
                double speed_after = Math.sqrt(horizontalMag(this.getMotion()));
                double speed_diff = speed_before - speed_after;
                float f2 = (float) (speed_diff * 10.0D - 5.0D);
                if (f2 > 5.0F) {
                    crush(f2);
                }
            }
        }
        if (isPowered() && rand.nextInt(4) == 0 && !world.isRemote) {
            spawnSmokeParticles(fuel);
        }

        //back to q
        q.multiply(new Quaternion(Vector3f.ZP, ((float) (rotationRoll - eulerAnglesOld.roll)), true));
        q.multiply(new Quaternion(Vector3f.XN, ((float) (rotationPitch - eulerAnglesOld.pitch)), true));
        q.multiply(new Quaternion(Vector3f.YP, ((float) (rotationYaw - eulerAnglesOld.yaw)), true));

        q = normalizeQuaternion(q);

        setQ_prev(getQ_Client());
        setQ(q);
        EulerAngles eulerAngles1 = toEulerAngles(q);
        rotationPitch = (float) eulerAngles1.pitch;
        rotationYaw = (float) eulerAngles1.yaw;
        rotationRoll = (float) eulerAngles1.roll;

        float d = (float) wrapSubtractDegrees(prevRotationYaw, this.rotationYaw);
        if (rotationRoll >= 90 && prevRotationRoll <= 90) {
            d = 0;
        }
        int diff = 3;

        deltaRotationTicks = Math.min(10, Math.max((int) Math.abs(deltaRotationLeft) * 5, deltaRotationTicks));
        deltaRotationLeft *= 0.7;
        deltaRotationLeft += d;
        deltaRotationLeft = wrapDegrees(deltaRotationLeft);
        deltaRotation = Math.min(MathHelper.abs(deltaRotationLeft), diff) * Math.signum(deltaRotationLeft);
        deltaRotationLeft -= deltaRotation;
        if (!(deltaRotation > 0)) {
            deltaRotationTicks--;
        }

        if (world.isRemote && canPassengerSteer()) {
            setQ_Client(q);

            PlaneNetworking.INSTANCE.sendToServer(getQ());
        }

        this.tickLerp();

    }

    protected void spawnSmokeParticles(int fuel) {
        spawnParticle(ParticleTypes.LARGE_SMOKE, new Vector3f(0, 0.8f, -1), 0);
        if ((fuel > 4 && fuel < 100)) {
            spawnParticle(ParticleTypes.LARGE_SMOKE, new Vector3f(0, 0.8f, -1), 5);
        }
    }

    public void spawnParticle(IParticleData particleData, Vector3f relPos, int particleCount) {
        relPos = new Vector3f(relPos.getX(), relPos.getY() - 0.3f, relPos.getZ());
        relPos = transformPos(relPos);
        relPos = new Vector3f(relPos.getX(), relPos.getY() + 0.9f, relPos.getZ());
        ((ServerWorld) world).spawnParticle(particleData,
                getPosX() + relPos.getX(),
                getPosY() + relPos.getY(),
                getPosZ() + relPos.getZ(),
                particleCount, 0, 0, 0, 0.0);
    }

    public Vector3f transformPos(Vector3f relPos) {
        EulerAngles eulerAngles = MathUtil.toEulerAngles(getQ_Client());
        eulerAngles.yaw = -eulerAngles.yaw;
        eulerAngles.roll = -eulerAngles.roll;
        relPos.transform(MathUtil.toQuaternion(eulerAngles.yaw, eulerAngles.pitch, eulerAngles.roll));
        return relPos;
    }

    @Nullable
    public Entity getControllingPassenger() {
        List<Entity> list = this.getPassengers();
        return list.isEmpty() ? null : list.get(0);
    }

    @Override
    public void readAdditional(CompoundNBT compound) {
        dataManager.set(FUEL, compound.getInt("Fuel"));
        CompoundNBT upgradesNBT = compound.getCompound("upgrades");
        dataManager.set(UPGRADES_NBT, upgradesNBT);
        deserializeUpgrades(upgradesNBT);
    }

    private void deserializeUpgrades(CompoundNBT upgradesNBT) {
        for (String key : upgradesNBT.keySet()) {
            ResourceLocation resourceLocation = new ResourceLocation(key);
            UpgradeType upgradeType = SimplePlanesRegistries.UPGRADE_TYPES.getValue(resourceLocation);
            if (upgradeType != null)
            {
                Upgrade upgrade = upgradeType.instanceSupplier.apply(this);
                upgrade.deserializeNBT(upgradesNBT.getCompound(key));
                upgrades.put(resourceLocation, upgrade);
            }
        }
    }

    @Override
    public void writeAdditional(CompoundNBT compound) {
        compound.putInt("Fuel", dataManager.get(FUEL));
        compound.put("upgrades", getUpgradesNBT());
    }

    @SuppressWarnings("ConstantConditions")
    private CompoundNBT getUpgradesNBT() {
        CompoundNBT upgradesNBT = new CompoundNBT();
        for (Upgrade upgrade : upgrades.values()) {
            upgradesNBT.put(upgrade.getType().getRegistryName().toString(), upgrade.serializeNBT());
        }
        return upgradesNBT;
    }

    @Override
    protected boolean canBeRidden(Entity entityIn) {
        return true;
    }

    @Override
    public boolean canBeRiddenInWater(Entity rider) {
        return upgrades.containsKey(SimplePlanesUpgrades.FLOATING.getId());
    }

    @Override
    public boolean canBeCollidedWith() {
        return true;
    }

    @Override
    protected AxisAlignedBB getBoundingBox(Pose pose) {
        return COLLISION_AABB.offset(getPositionVec());
    }

    @Override
    public IPacket<?> createSpawnPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }

    @Override
    public void notifyDataManagerChange(DataParameter<?> key) {
        super.notifyDataManagerChange(key);
        if (UPGRADES_NBT.equals(key) && world.isRemote()) {
            deserializeUpgrades(dataManager.get(UPGRADES_NBT));
        }
        if (QUATERNION.equals(key) && world.isRemote() && !canPassengerSteer()) {
            if (firstUpdate) {
                lerpStepsQ = 0;
                setQ_Client(getQ());
                setQ_prev(getQ());
            } else {
                lerpStepsQ = 10;
            }
        }
    }

    @Override
    public double getMountedYOffset() {
        return 0.375;
    }

    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        if (source.getTrueSource() != null && source.getTrueSource().isRidingSameEntity(this)) {
            return true;
        }
        return super.isInvulnerableTo(source);
    }

    @Override
    protected void updateFallState(double y, boolean onGroundIn, BlockState state, BlockPos pos) {
        if ((onGroundIn || isAboveWater()) && Config.PLANE_CRUSH.get()) {
            //        if (onGroundIn||isAboveWater()) {
            final double y1 = transformPos(new Vector3f(0, 1, 0)).getY();
            if (y1 < 0.867) {
                crush((float) (getMotion().length() * 5));
            }

            this.fallDistance = 0.0F;
        }

        //        this.lastYd = this.getMotion().y;
    }

    @SuppressWarnings("deprecation")
    private void crush(float damage) {
        if (!this.world.isRemote && !this.removed) {
            for (Entity entity : getPassengers()) {
                entity.attackEntityFrom(SimplePlanesMod.DAMAGE_SOURCE_PLANE_CRASH, damage);
            }
            if (world.getGameRules().getBoolean(GameRules.DO_ENTITY_DROPS)) {
                dropItem();
            }
            this.remove();
        }
    }

    public boolean isCreative() {
        return getControllingPassenger() instanceof PlayerEntity && ((PlayerEntity) getControllingPassenger()).isCreative();
    }

    public boolean getOnGround() {
        return onGround || groundTicks > 1;
    }

    public boolean isAboveWater() {
        return this.world.getBlockState(new BlockPos(this.getPositionVec().add(0, 0.4, 0))).getBlock() == Blocks.WATER;
    }

    public boolean canAddUpgrade(UpgradeType upgradeType) {
        return !upgrades.containsKey(upgradeType.getRegistryName()) && !upgradeType.occupyBackSeat && upgradeType.isPlaneApplicable.test(this);
    }

    public boolean isLarge() {
        return false;
    }

    public void updatePassenger(Entity passenger) {
        super.updatePassenger(passenger);
        boolean b = (passenger instanceof PlayerEntity) && ((PlayerEntity) passenger).isUser();

        if (this.isPassenger(passenger) && !b) {
            this.applyYawToEntity(passenger);
        }
    }

    /**
     * Applies this boat's yaw to the given entity. Used to update the orientation of its passenger.
     */
    public void applyYawToEntity(Entity entityToUpdate) {
        entityToUpdate.setRotationYawHead(entityToUpdate.getRotationYawHead() + this.deltaRotation);

        entityToUpdate.rotationYaw += this.deltaRotation;

        entityToUpdate.setRenderYawOffset(this.rotationYaw);

        float f = MathHelper.wrapDegrees(entityToUpdate.rotationYaw - this.rotationYaw);
        float f1 = MathHelper.clamp(f, -105.0F, 105.0F);

        float perc = deltaRotationTicks > 0 ? 1f / deltaRotationTicks : 1f;
        float diff = (f1 - f) * perc;

        entityToUpdate.prevRotationYaw += diff;
        entityToUpdate.rotationYaw += diff;

        entityToUpdate.setRotationYawHead(entityToUpdate.rotationYaw);
    }

    // copied from boat entity and edited a little
    public Vector3d func_230268_c_(LivingEntity livingEntity) {
        if (upgrades.containsKey(SimplePlanesUpgrades.FOLDING.getId())) {
            if (livingEntity instanceof PlayerEntity) {
                final PlayerEntity playerEntity = (PlayerEntity) livingEntity;

                if (!playerEntity.isCreative() && this.getPassengers().size() == 0 && this.isAlive()) {
                    ItemStack itemStack = getItemStack();

                    playerEntity.addItemStackToInventory(itemStack);
                    this.remove();
                    return super.func_230268_c_(livingEntity);
                }
            }
        }

        setPositionAndUpdate(this.getPosX(), this.getPosY(), this.getPosZ());

        Vector3d vector3d = func_233559_a_(this.getWidth() * MathHelper.SQRT_2, livingEntity.getWidth(), this.rotationYaw);
        double d0 = this.getPosX() + vector3d.x;
        double d1 = this.getPosZ() + vector3d.z;
        BlockPos blockpos = new BlockPos(d0, this.getBoundingBox().maxY, d1);
        BlockPos blockpos1 = blockpos.down();
        if (!this.world.hasWater(blockpos1)) {
            for (Pose pose : livingEntity.func_230297_ef_()) {
                AxisAlignedBB axisalignedbb = livingEntity.func_233648_f_(pose);
                double d2 = this.world.func_234936_m_(blockpos);
                if (TransportationHelper.func_234630_a_(d2)) {
                    Vector3d vector3d1 = new Vector3d(d0, (double) blockpos.getY() + d2, d1);
                    if (TransportationHelper.func_234631_a_(this.world, livingEntity, axisalignedbb.offset(vector3d1))) {
                        livingEntity.setPose(pose);
                        return vector3d1;
                    }
                }

                double d3 = this.world.func_234936_m_(blockpos1);
                if (TransportationHelper.func_234630_a_(d3)) {
                    Vector3d vector3d2 = new Vector3d(d0, (double) blockpos1.getY() + d3, d1);
                    if (TransportationHelper.func_234631_a_(this.world, livingEntity, axisalignedbb.offset(vector3d2))) {
                        livingEntity.setPose(pose);
                        return vector3d2;
                    }
                }
            }
        }

        return super.func_230268_c_(livingEntity);
    }

    @SuppressWarnings("rawtypes")
    private ItemStack getItemStack() {
        ItemStack itemStack = new ItemStack(((AbstractPlaneEntityType) getType()).dropItem);
        if (upgrades.containsKey(SimplePlanesUpgrades.FOLDING.getId())) {
            itemStack.setTagInfo("EntityTag", serializeNBT());
            itemStack.addEnchantment(Enchantments.MENDING, 1);
        }
        return itemStack;
    }

    private int lerpSteps;
    private int lerpStepsQ;

    private double lerpX;
    private double lerpY;
    private double lerpZ;

    private void tickLerp() {
        if (this.canPassengerSteer()) {
            this.lerpSteps = 0;
            this.lerpStepsQ = 0;
            this.setPacketCoordinates(this.getPosX(), this.getPosY(), this.getPosZ());
            return;
        }

        if (this.lerpSteps > 0) {
            double d0 = this.getPosX() + (this.lerpX - this.getPosX()) / (double) this.lerpSteps;
            double d1 = this.getPosY() + (this.lerpY - this.getPosY()) / (double) this.lerpSteps;
            double d2 = this.getPosZ() + (this.lerpZ - this.getPosZ()) / (double) this.lerpSteps;
            --this.lerpSteps;
            this.setPosition(d0, d1, d2);
        }
        if (this.lerpStepsQ > 0) {
            setQ_prev(getQ_Client());
            setQ_Client(lerpQ(1f / lerpStepsQ, getQ_Client(), getQ()));
            --this.lerpStepsQ;
        } else if (this.lerpStepsQ == 0) {
            setQ_prev(getQ_Client());
            setQ_Client(getQ());
            --this.lerpStepsQ;
        }
    }

    @OnlyIn(Dist.CLIENT)
    public void setPositionAndRotationDirect(double x, double y, double z, float yaw, float pitch, int posRotationIncrements, boolean teleport) {
        if (x == getPosX() && y == getPosY() && z == getPosZ()) {
            return;
        }
        this.lerpX = x;
        this.lerpY = y;
        this.lerpZ = z;
        this.lerpSteps = 10;
    }

    @Override
    public void setPositionAndRotation(double x, double y, double z, float yaw, float pitch) {
        double clampedX = MathHelper.clamp(x, -3.0E7D, 3.0E7D);
        double clampedZ = MathHelper.clamp(z, -3.0E7D, 3.0E7D);
        this.prevPosX = clampedX;
        this.prevPosY = y;
        this.prevPosZ = clampedZ;
        this.setPosition(clampedX, y, clampedZ);
        this.rotationYaw = yaw % 360.0F;
        this.rotationPitch = pitch % 360.0F;

        this.prevRotationYaw = this.rotationYaw;
        this.prevRotationPitch = this.rotationPitch;
    }

    @Override
    protected void addPassenger(Entity passenger) {
        super.addPassenger(passenger);
        if (this.canPassengerSteer() && this.lerpSteps > 0) {
            this.lerpSteps = 0;
            this.setPositionAndRotation(this.lerpX, this.lerpY, this.lerpZ, this.rotationYaw, this.rotationPitch);
        }
    }

    public PlayerEntity getControllingPlayer() {
        Entity entity = getControllingPassenger();
        if (entity instanceof PlayerEntity) {
            return (PlayerEntity) entity;
        }
        return null;
    }

    public void upgradeChanged() {
        this.dataManager.set(UPGRADES_NBT, getUpgradesNBT());
    }

    @Override
    public void setJumpPower(int jumpPowerIn) {}

    @Override
    public boolean canJump() {
        return upgrades.containsKey(SimplePlanesUpgrades.BOOSTER.getId());
    }

    @Override
    public void handleStartJump(int perc) {
        int cost = 10;
        int fuel = getFuel();
        if (fuel > cost) {
            dataManager.set(FUEL, fuel - cost);
            if (perc > 80) {
                RocketUpgrade upgrade = (RocketUpgrade) upgrades.get(SimplePlanesUpgrades.BOOSTER.getId());
                upgrade.fuel = 20;
            }
        }
    }

    @Override
    public void handleStopJump() {}
}
