package com.minelittlepony.unicopia.player;

import com.minelittlepony.unicopia.Race;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.RayTraceResult;

import net.minecraftforge.event.entity.ProjectileImpactEvent;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.living.LivingEntityUseItemEvent;
import net.minecraftforge.event.entity.player.PlayerDropsEvent;
import net.minecraftforge.event.entity.player.PlayerFlyableFallEvent;
import net.minecraftforge.event.entity.player.PlayerSleepInBedEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent.Phase;

@EventBusSubscriber
class Hooks {
    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase == Phase.END) {
            PlayerSpeciesList.instance().getPlayer(event.player).onUpdate();
        } else {
            PlayerSpeciesList.instance().getPlayer(event.player).beforeUpdate();
        }
    }

    @SubscribeEvent
    public static void onPlayerTossItem(ItemTossEvent event) {
        Race race = PlayerSpeciesList.instance().getPlayer(event.getPlayer()).getPlayerSpecies();

        PlayerSpeciesList.instance().getEntity(event.getEntityItem()).setPlayerSpecies(race);
    }

    @SubscribeEvent
    public static void onPlayerDropItems(PlayerDropsEvent event) {

        Race race = PlayerSpeciesList.instance().getPlayer(event.getEntityPlayer()).getPlayerSpecies();

        event.getDrops().stream()
            .map(PlayerSpeciesList.instance()::getEntity)
            .forEach(item -> item.setPlayerSpecies(race));
    }

    @SubscribeEvent
    public static void onPlayerTrySleep(PlayerSleepInBedEvent event) {
        if (event.getResultStatus() == null) {
            event.setResult(PlayerSpeciesList.instance().getPlayer(event.getEntityPlayer()).trySleep(event.getPos()));
        }
    }

    @SubscribeEvent
    public static void onPlayerFall(PlayerFlyableFallEvent event) {
        PlayerSpeciesList.instance().getPlayer(event.getEntityPlayer()).onFall(event.getDistance(), event.getMultiplier());
    }

    @SubscribeEvent
    public static void onProjectileHit(ProjectileImpactEvent event) {
        RayTraceResult ray = event.getRayTraceResult();

        if (!event.isCanceled()
            && ray.typeOfHit == RayTraceResult.Type.ENTITY
            && ray.entityHit instanceof EntityPlayer
            && !PlayerSpeciesList.instance().getPlayer((EntityPlayer)ray.entityHit).onProjectileImpact(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onItemUseBegin(LivingEntityUseItemEvent.Start event) {
        Entity e = event.getEntity();

        if (!event.isCanceled() && e instanceof EntityPlayer) {
            PlayerSpeciesList.instance().getPlayer((EntityPlayer)e).getFood().begin(event.getItem());
        }
    }

    @SubscribeEvent
    public static void onItemUseCancel(LivingEntityUseItemEvent.Stop event) {
        Entity e = event.getEntity();

        if (!event.isCanceled() && e instanceof EntityPlayer) {
            PlayerSpeciesList.instance().getPlayer((EntityPlayer)e).getFood().end();
        }
    }

    @SubscribeEvent
    public static void onItemUseFinish(LivingEntityUseItemEvent.Finish event) {
        Entity e = event.getEntity();

        if (!event.isCanceled() && e instanceof EntityPlayer) {
            PlayerSpeciesList.instance().getPlayer((EntityPlayer)e).getFood().finish();
        }
    }
}
