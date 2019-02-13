package com.minelittlepony.unicopia.spell;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import com.minelittlepony.unicopia.UParticles;
import com.minelittlepony.unicopia.entity.EntitySpell;
import com.minelittlepony.unicopia.particle.Particles;
import com.minelittlepony.util.shape.IShape;
import com.minelittlepony.util.shape.Line;

import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

public class SpellCharge extends AbstractSpell {

    boolean searching = true;

    private UUID targettedEntityId;
    private EntitySpell targettedEntity;

    private static final AxisAlignedBB searchArea = new AxisAlignedBB(-15, -15, -15, 15, 15, 15);

    @Override
    public String getName() {
        return "charge";
    }

    @Override
    public SpellAffinity getAffinity() {
        return SpellAffinity.GOOD;
    }

    @Override
    public int getTint() {
        return 0x7272B7;
    }

    @Override
    public void render(ICaster<?> source) {
        if (source.getWorld().rand.nextInt(4 + source.getCurrentLevel() * 4) == 0) {
            EntitySpell target = getTarget(source);

            if (target != null) {
                Vec3d start = source.getEntity().getPositionVector();

                IShape line = new Line(start, target.getPositionVector());

                source.spawnParticles(line, (int)line.getVolumeOfSpawnableSpace(), pos -> {
                    Particles.instance().spawnParticle(UParticles.UNICORN_MAGIC, false, pos.add(start), 0, 0, 0);
                });
            }

        }
    }

    protected boolean canTargetEntity(Entity e) {
        return e instanceof EntitySpell && ((EntitySpell)e).hasEffect();
    }

    protected void setTarget(EntitySpell e) {
        searching = false;
        targettedEntity = e;
        targettedEntityId = e.getUniqueID();
    }

    protected EntitySpell getTarget(ICaster<?> source) {
        if (targettedEntity == null && targettedEntityId != null) {
            source.getWorld().getEntities(EntitySpell.class, e -> e.getUniqueID().equals(targettedEntityId))
                .stream()
                .findFirst()
                .ifPresent(this::setTarget);
        }

        if (targettedEntity != null && targettedEntity.isDead) {
            targettedEntity = null;
            targettedEntityId = null;
            searching = true;
        }

        return targettedEntity;
    }

    @Override
    public boolean update(ICaster<?> source) {

        if (searching) {
            BlockPos origin = source.getOrigin();

            List<Entity> list = source.getWorld().getEntitiesInAABBexcluding(source.getEntity(),
                    searchArea.offset(origin), this::canTargetEntity).stream().sorted((a, b) ->
                        (int)(a.getDistanceSq(origin) - b.getDistanceSq(origin))
                    ).collect(Collectors.toList());

            if (list.size() > 0) {
                setTarget((EntitySpell)list.get(0));
            }
        } else {
            EntitySpell target = getTarget(source);

            if (target != null && !target.overLevelCap() && source.getCurrentLevel() > 0) {
                source.addLevels(-1);
                target.addLevels(1);
            }
        }

        return !getDead();
    }

    @Override
    public void writeToNBT(NBTTagCompound compound) {
        super.writeToNBT(compound);

        if (targettedEntityId != null) {
            compound.setUniqueId("target", targettedEntityId);
        }
    }

    @Override
    public void readFromNBT(NBTTagCompound compound) {
        super.readFromNBT(compound);

        if (compound.hasKey("target")) {
            targettedEntityId = compound.getUniqueId("target");
        }
    }
}
