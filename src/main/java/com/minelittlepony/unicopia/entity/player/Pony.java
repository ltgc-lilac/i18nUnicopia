package com.minelittlepony.unicopia.entity.player;

import java.util.*;
import java.util.stream.Stream;

import org.jetbrains.annotations.Nullable;

import com.minelittlepony.unicopia.client.render.PlayerPoser.Animation;
import com.minelittlepony.unicopia.*;
import com.minelittlepony.unicopia.ability.*;
import com.minelittlepony.unicopia.ability.magic.*;
import com.minelittlepony.unicopia.ability.magic.spell.AbstractDisguiseSpell;
import com.minelittlepony.unicopia.ability.magic.spell.Spell;
import com.minelittlepony.unicopia.ability.magic.spell.effect.SpellType;
import com.minelittlepony.unicopia.ability.magic.spell.trait.TraitDiscovery;
import com.minelittlepony.unicopia.advancement.UCriteria;
import com.minelittlepony.unicopia.entity.*;
import com.minelittlepony.unicopia.entity.behaviour.EntityAppearance;
import com.minelittlepony.unicopia.entity.duck.LivingEntityDuck;
import com.minelittlepony.unicopia.entity.effect.SunBlindnessStatusEffect;
import com.minelittlepony.unicopia.entity.effect.UEffects;
import com.minelittlepony.unicopia.item.FriendshipBraceletItem;
import com.minelittlepony.unicopia.item.UItems;
import com.minelittlepony.unicopia.util.*;
import com.minelittlepony.unicopia.network.*;
import com.minelittlepony.unicopia.network.datasync.EffectSync.UpdateCallback;
import com.minelittlepony.unicopia.server.world.UGameRules;
import com.minelittlepony.unicopia.trinkets.TrinketsDelegate;
import com.minelittlepony.common.util.animation.LinearInterpolator;
import com.google.common.collect.Streams;
import com.minelittlepony.common.util.animation.Interpolator;
import com.mojang.authlib.GameProfile;

import net.minecraft.block.BlockState;
import net.minecraft.entity.*;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.EntityDamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.*;
import net.minecraft.world.GameMode;

public class Pony extends Living<PlayerEntity> implements Copyable<Pony>, UpdateCallback {
    private static final TrackedData<String> RACE = DataTracker.registerData(PlayerEntity.class, TrackedDataHandlerRegistry.STRING);

    static final TrackedData<Float> ENERGY = DataTracker.registerData(PlayerEntity.class, TrackedDataHandlerRegistry.FLOAT);
    static final TrackedData<Float> EXHAUSTION = DataTracker.registerData(PlayerEntity.class, TrackedDataHandlerRegistry.FLOAT);
    static final TrackedData<Float> EXERTION = DataTracker.registerData(PlayerEntity.class, TrackedDataHandlerRegistry.FLOAT);
    static final TrackedData<Optional<BlockPos>> HANGING_POSITION = DataTracker.registerData(PlayerEntity.class, TrackedDataHandlerRegistry.OPTIONAL_BLOCK_POS);
    static final TrackedData<Float> MANA = DataTracker.registerData(PlayerEntity.class, TrackedDataHandlerRegistry.FLOAT);
    static final TrackedData<Float> XP = DataTracker.registerData(PlayerEntity.class, TrackedDataHandlerRegistry.FLOAT);
    static final TrackedData<Integer> LEVEL = DataTracker.registerData(PlayerEntity.class, TrackedDataHandlerRegistry.INTEGER);
    static final TrackedData<Integer> CORRUPTION = DataTracker.registerData(PlayerEntity.class, TrackedDataHandlerRegistry.INTEGER);

    static final int INITIAL_SUN_IMMUNITY = 20;

    private static final TrackedData<NbtCompound> EFFECT = DataTracker.registerData(PlayerEntity.class, TrackedDataHandlerRegistry.NBT_COMPOUND);

    private final AbilityDispatcher powers = new AbilityDispatcher(this);
    private final PlayerPhysics gravity = addTicker(new PlayerPhysics(this));
    private final PlayerCharmTracker charms = new PlayerCharmTracker(this);
    private final PlayerCamera camera = new PlayerCamera(this);
    private final TraitDiscovery discoveries = new TraitDiscovery(this);

    private final Map<String, Integer> advancementProgress = new HashMap<>();

    private final ManaContainer mana;
    private final PlayerLevelStore levels;
    private final PlayerLevelStore corruption;

    private final Interpolator interpolator = new LinearInterpolator();

    private Race respawnRace = Race.UNSET;

    private boolean dirty;

    private int ticksHanging;

    private float magicExhaustion = 0;

    private int ticksInSun;
    private boolean hasShades;
    private int ticksSunImmunity = INITIAL_SUN_IMMUNITY;

    private Animation animation = Animation.NONE;
    private int animationMaxDuration;
    private int animationDuration;

    public Pony(PlayerEntity player) {
        super(player, EFFECT);
        this.mana = addTicker(new ManaContainer(this));
        this.levels = new PlayerLevelStore(this, LEVEL, true, SoundEvents.ENTITY_PLAYER_LEVELUP);
        this.corruption = new PlayerLevelStore(this, CORRUPTION, false, SoundEvents.PARTICLE_SOUL_ESCAPE);

        player.getDataTracker().startTracking(RACE, Race.DEFAULT_ID);
        player.getDataTracker().startTracking(HANGING_POSITION, Optional.empty());

        addTicker(this::updateAnimations);
        addTicker(this::updateBatPonyAbilities);
        addTicker(this::updateCorruptionDecay);
        addTicker(new PlayerAttributes(this));
    }

    public static void registerAttributes(DefaultAttributeContainer.Builder builder) {
        builder.add(UEntityAttributes.EXTRA_MINING_SPEED);
        builder.add(UEntityAttributes.ENTITY_GRAVTY_MODIFIER);
    }

    public void setAnimation(Animation animation) {
        setAnimation(animation, animation.getDuration());
    }

    public void setAnimation(Animation animation, int duration) {
        if (animation != this.animation && duration != animationDuration) {
            this.animation = animation;
            this.animationDuration = animation == Animation.NONE ? 0 : Math.max(0, duration);
            this.animationMaxDuration = animationDuration;

            if (!isClient()) {
                Channel.SERVER_PLAYER_ANIMATION_CHANGE.sendToAllPlayers(new MsgPlayerAnimationChange(this, animation, animationDuration), asWorld());
            }

            animation.getSound().ifPresent(sound -> {
                playSound(sound, sound == USounds.ENTITY_PLAYER_WOLOLO ? 0.1F : 0.9F, 1);
            });
        }
    }

    public Animation getAnimation() {
        return animation;
    }

    public float getAnimationProgress(float delta) {
        if (animation == Animation.NONE) {
            return 0;
        }
        return 1 - (((float)animationDuration) / animationMaxDuration);
    }

    public Map<String, Integer> getAdvancementProgress() {
        return advancementProgress;
    }

    public void setRespawnRace(Race race) {
        respawnRace = race;
    }

    @Override
    public Race getSpecies() {
        if (AmuletSelectors.ALICORN_AMULET.test(entity)) {
            return Race.ALICORN;
        }

        return getObservedSpecies();
    }

    public Race getObservedSpecies() {
        return getSpellSlot()
                .get(SpellPredicate.IS_MIMIC, true)
                .map(AbstractDisguiseSpell::getDisguise)
                .map(EntityAppearance::getAppearance)
                .flatMap(Pony::of)
                .map(Pony::getActualSpecies)
                .orElse(getActualSpecies());
    }

    public Race.Composite getCompositeRace() {
        Race observed = getObservedSpecies();
        return new Race.Composite(observed, AmuletSelectors.ALICORN_AMULET.test(entity) ? Race.ALICORN : observed);
    }

    public Race getActualSpecies() {
        return Race.fromName(entity.getDataTracker().get(RACE), Race.HUMAN);
    }

    @Override
    public void setSpecies(Race race) {
        race = race.validate(entity);
        ticksInSun = 0;
        entity.getDataTracker().set(RACE, Race.REGISTRY.getId(race).toString());

        gravity.updateFlightState();
        entity.sendAbilitiesUpdate();

        UCriteria.PLAYER_CHANGE_RACE.trigger(entity);
    }

    public TraitDiscovery getDiscoveries() {
        return discoveries;
    }

    public MagicReserves getMagicalReserves() {
        return mana;
    }

    public PlayerCharmTracker getCharms() {
        return charms;
    }

    @Override
    public LevelStore getLevel() {
        return levels;
    }

    @Override
    public LevelStore getCorruption() {
        return corruption;
    }

    public boolean isSunImmune() {
        return ticksSunImmunity > 0;
    }

    @Override
    public Affinity getAffinity() {
        return getSpecies().getAffinity();
    }

    @Override
    public void setDirty() {
        dirty = true;
    }

    private void sendCapabilities() {
        if (!dirty) {
            return;
        }
        dirty = false;

        if (entity instanceof ServerPlayerEntity) {
            MsgOtherPlayerCapabilities packet = new MsgOtherPlayerCapabilities(this);
            Channel.SERVER_PLAYER_CAPABILITIES.sendToPlayer(packet, (ServerPlayerEntity)entity);
            Channel.SERVER_OTHER_PLAYER_CAPABILITIES.sendToSurroundingPlayers(packet, entity);
        }
    }

    public AbilityDispatcher getAbilities() {
        return powers;
    }

    @Override
    public PlayerPhysics getPhysics() {
        return gravity;
    }

    public float getBlockBreakingSpeed() {
        return (float)entity.getAttributeInstance(UEntityAttributes.EXTRA_MINING_SPEED).getValue();
    }

    public Motion getMotion() {
        return gravity;
    }

    public PlayerCamera getCamera() {
        return camera;
    }

    public Interpolator getInterpolator() {
        return interpolator;
    }

    @Override
    public final LivingEntity getMaster() {
        return asEntity();
    }

    public void onSpawn() {
        if (entity.world instanceof ServerWorld sw
                && getObservedSpecies() == Race.BAT
                && sw.getServer().getSaveProperties().getGameMode() != GameMode.ADVENTURE
                && SunBlindnessStatusEffect.isPositionExposedToSun(sw, getOrigin())) {
            SpawnLocator.selectSpawnPosition(sw, entity);
        }
        ticksSunImmunity = INITIAL_SUN_IMMUNITY;
    }

    @Override
    public boolean beforeUpdate() {
        if (isClient()) {
            if (entity.hasVehicle() && entity.isSneaking()) {

                Entity vehicle = entity.getVehicle();

                if (vehicle instanceof Trap) {
                    if (((Trap)vehicle).attemptDismount(entity)) {
                        setCarrier((UUID)null);
                        entity.stopRiding();
                        entity.refreshPositionAfterTeleport(vehicle.getPos());
                        Living.transmitPassengers(vehicle);
                    } else {
                        entity.setSneaking(false);
                    }
                } else {
                    setCarrier((UUID)null);
                    entity.stopRiding();
                    entity.refreshPositionAfterTeleport(vehicle.getPos());
                    Living.transmitPassengers(vehicle);
                }
            }
        }

        magicExhaustion = ManaConsumptionUtil.burnFood(entity, magicExhaustion);

        powers.tick();

        return false;
    }

    public Optional<BlockPos> getHangingPosition() {
        return entity.getDataTracker().get(HANGING_POSITION);
    }

    public boolean isHanging() {
        return getHangingPosition().isPresent();
    }

    public void stopHanging() {
        entity.getDataTracker().set(HANGING_POSITION, Optional.empty());
        entity.calculateDimensions();
        ticksHanging = 0;
    }

    public void startHanging(BlockPos pos) {
        entity.getDataTracker().set(HANGING_POSITION, Optional.of(pos));
        entity.teleport(pos.getX() + 0.5, pos.getY() - 1, pos.getZ() + 0.5);
        entity.setVelocity(Vec3d.ZERO);
        entity.setSneaking(false);
        entity.stopFallFlying();
        getPhysics().cancelFlight(true);
    }

    public boolean canHangAt(BlockPos pos) {
        if (!asWorld().isAir(pos) || !asWorld().isAir(pos.down())) {
            return false;
        }

        pos = pos.up();
        BlockState state = asWorld().getBlockState(pos);

        return state.isSolidSurface(asWorld(), pos, entity, Direction.DOWN);
    }

    private void updateAnimations() {
        if (animationDuration >= 0 && --animationDuration <= 0) {
            setAnimation(Animation.NONE);
        }
    }

    private void updateBatPonyAbilities() {
        if (ticksSunImmunity > 0) {
            ticksSunImmunity--;
        }

        if (isHanging()) {
            ((LivingEntityDuck)entity).setLeaningPitch(0);
            if (!isClient() && (getObservedSpecies() != Race.BAT || (ticksHanging++ > 2 && getHangingPosition().filter(getOrigin().down()::equals).filter(this::canHangAt).isEmpty()))) {
                stopHanging();
            }
        } else {
            ticksHanging = 0;
        }

        if (getObservedSpecies() == Race.BAT && !entity.hasPortalCooldown()) {
            boolean hasShades = TrinketsDelegate.getInstance().getEquipped(entity, TrinketsDelegate.FACE).anyMatch(s -> s.isIn(UTags.SHADES));
            if (!this.hasShades && hasShades && getObservedSpecies() == Race.BAT) {
                UCriteria.WEAR_SHADES.trigger(entity);
            }
            this.hasShades = hasShades;

            if (!hasShades && ticksSunImmunity <= 0 && MeteorlogicalUtil.isLookingIntoSun(asWorld(), entity)) {
                if (!isClient()) {
                    entity.addStatusEffect(new StatusEffectInstance(UEffects.SUN_BLINDNESS, SunBlindnessStatusEffect.MAX_DURATION, 2, true, false));
                    UCriteria.LOOK_INTO_SUN.trigger(entity);
                } else if (isClientPlayer()) {
                    InteractionManager.instance().playLoopingSound(entity, InteractionManager.SOUND_EARS_RINGING, entity.getId());
                }
            }

            if (SunBlindnessStatusEffect.hasSunExposure(entity)) {
                if (ticksInSun < 200) {
                    ticksInSun++;
                }

                if (ticksInSun == 1) {
                    if (!isClient()) {
                        entity.addStatusEffect(new StatusEffectInstance(UEffects.SUN_BLINDNESS, SunBlindnessStatusEffect.MAX_DURATION, 1, true, false));
                    } else if (isClientPlayer()) {
                        InteractionManager.instance().playLoopingSound(entity, InteractionManager.SOUND_EARS_RINGING, entity.getId());
                    }
                }
            } else if (ticksInSun > 0) {
                ticksInSun--;
            }
        }
    }

    private void updateCorruptionDecay() {
        if (!isClient() && !UItems.ALICORN_AMULET.isApplicable(entity)) {
            if (entity.age % (10 * ItemTracker.SECONDS) == 0) {
                if (entity.world.random.nextInt(100) == 0) {
                    corruption.add(-1);
                    setDirty();
                }

                if (entity.getHealth() >= entity.getMaxHealth() - 1 && !entity.getHungerManager().isNotFull()) {
                    corruption.add(-entity.world.random.nextInt(4));
                    setDirty();
                }
            }

            if (entity.hurtTime == 1 && getSpecies().canCast()) {
                corruption.add(1);
                setDirty();
            }
        }
    }

    @Override
    public void tick() {
        super.tick();
        sendCapabilities();
    }

    public Optional<Living<?>> getEntityInArms() {
        return Living.getOrEmpty(entity.getFirstPassenger()).filter(Living::isBeingCarried);
    }

    @Override
    public boolean onUpdatePassengerPosition(Entity passender, Entity.PositionUpdater positionUpdater) {
        Entity passenger = entity.getFirstPassenger();
        if (Living.getOrEmpty(passenger).filter(Living::isBeingCarried).isPresent()) {

            Vec3d carryPosition = new Vec3d(0, 0, entity.getWidth());

            float leanAmount = ((LivingEntityDuck)entity).getLeaningPitch();
            carryPosition = carryPosition.rotateX(-leanAmount * MathHelper.PI / 4F)
                    .add(new Vec3d(0, -0.5F, 0).multiply(leanAmount));

            carryPosition = carryPosition.rotateY(-entity.getBodyYaw() * MathHelper.RADIANS_PER_DEGREE);

            carryPosition = entity.getPos().add(carryPosition);
            positionUpdater.accept(passenger, carryPosition.x, carryPosition.y, carryPosition.z);
            return true;
        }
        return false;
    }

    public Optional<Float> modifyDamage(DamageSource cause, float amount) {

        if (!cause.isUnblockable() && !cause.isMagic() && !cause.isFire() && !cause.isOutOfWorld()
                && !(cause instanceof EntityDamageSource && ((EntityDamageSource)cause).isThorns())
                && cause != DamageSource.FREEZE) {

            if (getSpecies().canUseEarth() && entity.isSneaking()) {
                amount /= (cause.isProjectile() ? 3 : 2) * (entity.getHealth() < 5 ? 3 : 1);

                return Optional.of(amount);
            }
        }
        return Optional.empty();
    }

    public void onDropItem(ItemEntity itemDropped) {
        Equine.of(itemDropped).ifPresent(eq -> {
            eq.setSpecies(getSpecies());
            eq.getPhysics().setBaseGravityModifier(gravity.getPersistantGravityModifier());
        });
    }

    public Optional<Float> onImpact(float distance, float damageMultiplier, DamageSource cause) {

        float originalDistance = distance;

        distance *= gravity.getGravityModifier();

        boolean extraProtection = getSpellSlot().get(SpellType.SHIELD, false).isPresent();

        if (!entity.isCreative() && !entity.isSpectator()) {

            if (extraProtection) {
                distance /= (getLevel().getScaled(3) + 1);
                if (entity.isSneaking()) {
                    distance /= 2;
                }
            }

            if (getSpecies().canFly() || (getSpecies().canUseEarth() && entity.isSneaking())) {
                distance -= 5;
            }
            distance = Math.max(0, distance);
        }

        handleFall(distance, damageMultiplier, cause);
        if (distance != originalDistance) {
            return Optional.of(distance);
        }
        return Optional.empty();
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void handleFall(float distance, float damageMultiplier, DamageSource cause) {
        super.handleFall(distance, damageMultiplier, cause);

        if (getSpecies().canUseEarth() && entity.isSneaking()) {
            double radius = distance / 10;
            if (radius > 0) {
                EarthPonyStompAbility.spawnEffectAround(entity, entity.getLandingPos(), radius, radius);
            }
        }
    }

    @Override
    public boolean subtractEnergyCost(double foodSubtract) {

        List<Pony> partyMembers = FriendshipBraceletItem.getPartyMembers(this, 10).toList();

        if (!partyMembers.isEmpty()) {
            foodSubtract /= (partyMembers.size() + 1);
            for (Pony member : partyMembers) {
                member.directTakeEnergy(foodSubtract);
            }
        }

        directTakeEnergy(foodSubtract);

        return entity.getHealth() > 1 && mana.getMana().getPercentFill() > 0.1F;
    }

    protected void directTakeEnergy(double foodSubtract) {
        if (!entity.isCreative() && !entity.world.isClient) {
            magicExhaustion += ManaConsumptionUtil.consumeMana(mana.getMana(), foodSubtract);
        }
    }

    @Override
    protected Stream<ItemStack> getInventoryStacks() {
        return Streams.concat(
                super.getInventoryStacks(),
                entity.getInventory().main.stream()
        );
    }

    @Override
    protected void giveBackItem(ItemStack stack) {
        if (!entity.giveItemStack(stack)) {
            entity.dropItem(stack, false);
        }
    }

    public Optional<Text> trySleep(BlockPos pos) {

        if (AmuletSelectors.ALICORN_AMULET.test(entity)) {
            return Optional.of(Text.translatable("block.unicopia.bed.not_tired"));
        }

        return findAllSpellsInRange(10)
                .filter(p -> p instanceof Pony && ((Pony)p).isEnemy(this))
                .findFirst()
                .map(p -> Text.translatable("block.unicopia.bed.not_safe"));
    }

    public ActionResult canSleepNow() {
        if (asWorld().getGameRules().getBoolean(UGameRules.DO_NOCTURNAL_BAT_PONIES) && getActualSpecies().isNocturnal()) {
            return asWorld().isDay() ? ActionResult.SUCCESS : ActionResult.FAIL;
        }

        return ActionResult.PASS;
    }

    @Override
    public boolean isEnemy(Affine other) {
        return getArmour().contains(UItems.ALICORN_AMULET) || super.isEnemy(other);
    }

    @Override
    public void toSyncronisedNbt(NbtCompound compound) {
        super.toSyncronisedNbt(compound);
        compound.putString("playerSpecies", Race.REGISTRY.getId(getActualSpecies()).toString());
        compound.putFloat("magicExhaustion", magicExhaustion);
        compound.putInt("ticksHanging", ticksHanging);
        BLOCK_POS.writeOptional("hangingPosition", compound, getHangingPosition());
        compound.putInt("ticksInSun", ticksInSun);
        compound.putBoolean("hasShades", hasShades);
        compound.put("powers", powers.toNBT());
        compound.put("gravity", gravity.toNBT());
        compound.put("charms", charms.toNBT());
        compound.put("discoveries", discoveries.toNBT());
        compound.put("mana", mana.toNBT());
        compound.putInt("levels", levels.get());
        compound.putInt("corruption", corruption.get());

        NbtCompound progress = new NbtCompound();
        advancementProgress.forEach((key, count) -> {
            progress.putInt(key, count);
        });
        compound.put("advancementProgress", progress);
    }

    @Override
    public void fromSynchronizedNbt(NbtCompound compound) {
        super.fromSynchronizedNbt(compound);
        setSpecies(Race.fromName(compound.getString("playerSpecies"), Race.HUMAN));
        powers.fromNBT(compound.getCompound("powers"));
        gravity.fromNBT(compound.getCompound("gravity"));
        charms.fromNBT(compound.getCompound("charms"));
        discoveries.fromNBT(compound.getCompound("discoveries"));
        levels.set(compound.getInt("levels"));
        corruption.set(compound.getInt("corruption"));
        mana.fromNBT(compound.getCompound("mana"));

        magicExhaustion = compound.getFloat("magicExhaustion");
        ticksHanging = compound.getInt("ticksHanging");
        entity.getDataTracker().set(HANGING_POSITION, NbtSerialisable.BLOCK_POS.readOptional("hangingPosition", compound));
        ticksInSun = compound.getInt("ticksInSun");
        hasShades = compound.getBoolean("hasShades");

        NbtCompound progress = compound.getCompound("advancementProgress");
        advancementProgress.clear();
        for (String key : progress.getKeys()) {
            advancementProgress.put(key, progress.getInt(key));
        }
    }

    @Override
    public void copyFrom(Pony oldPlayer, boolean alive) {

        boolean forcedSwap = !alive
                && entity instanceof ServerPlayerEntity
                && entity.world.getGameRules().getBoolean(UGameRules.SWAP_TRIBE_ON_DEATH)
                && oldPlayer.respawnRace == Race.UNSET;

        if (alive) {
            oldPlayer.getSpellSlot().stream(true).forEach(getSpellSlot()::put);
        } else {
            if (forcedSwap) {
                Channel.SERVER_SELECT_TRIBE.sendToPlayer(new MsgTribeSelect(Race.allPermitted(entity), "gui.unicopia.tribe_selection.respawn"), (ServerPlayerEntity)entity);
            } else {
                oldPlayer.getSpellSlot().stream(true).filter(SpellPredicate.IS_PLACED).forEach(getSpellSlot()::put);
            }
        }

        oldPlayer.getSpellSlot().put(null);
        setSpecies(oldPlayer.respawnRace != Race.UNSET && !alive ? oldPlayer.respawnRace : oldPlayer.getActualSpecies());
        getDiscoveries().copyFrom(oldPlayer.getDiscoveries(), alive);
        getPhysics().copyFrom(oldPlayer.getPhysics(), alive);
        if (!forcedSwap) {
            getArmour().copyFrom(oldPlayer.getArmour(), alive);
            getCharms().equipSpell(Hand.MAIN_HAND, oldPlayer.getCharms().getEquippedSpell(Hand.MAIN_HAND));
            getCharms().equipSpell(Hand.OFF_HAND, oldPlayer.getCharms().getEquippedSpell(Hand.OFF_HAND));
            corruption.set(oldPlayer.getCorruption().get());
            levels.set(oldPlayer.getLevel().get());
            mana.getXp().set(oldPlayer.getMagicalReserves().getXp().get());
        } else {
            mana.getEnergy().set(0.6F);
            mana.getExhaustion().set(0);
            mana.getExertion().set(0);
        }


        advancementProgress.putAll(oldPlayer.getAdvancementProgress());
        setDirty();
        onSpawn();
    }

    @Override
    public void onSpellSet(@Nullable Spell spell) {
        if (spell != null) {
            if (spell.getAffinity() == Affinity.BAD && entity.getWorld().random.nextInt(120) == 0) {
                getCorruption().add(1);
            }
            getCorruption().add((int)spell.getTraits().getCorruption());
            setDirty();
        }
    }

    public boolean isClientPlayer() {
        return InteractionManager.instance().isClientPlayer(asEntity());
    }

    @SuppressWarnings("unchecked")
    @Nullable
    public static Pony of(@Nullable PlayerEntity player) {
        return player == null ? null : ((Container<Pony>)player).get();
    }

    public static Stream<Pony> stream(Stream<Entity> entities) {
        return entities.flatMap(entity -> of(entity).stream());
    }

    public static Optional<Pony> of(Entity entity) {
        return Equine.<Entity, Pony>of(entity, a -> a instanceof Pony);
    }

    public static boolean equal(GameProfile one, GameProfile two) {
        return one == two || (one != null && two != null && one.getId().equals(two.getId()));
    }

    public static boolean equal(Entity one, Entity two) {
        return Objects.equals(one, two) || (one instanceof PlayerEntity && two instanceof PlayerEntity && equal((PlayerEntity)one, (PlayerEntity)two));
    }

    public static boolean equal(PlayerEntity one, PlayerEntity two) {
        return one == two || (one != null && two != null && equal(one.getGameProfile(), two.getGameProfile()));
    }
}
