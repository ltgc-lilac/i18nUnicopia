package com.minelittlepony.unicopia.ability.magic;

import java.util.function.Predicate;

import com.minelittlepony.unicopia.ability.magic.spell.AbstractDisguiseSpell;
import com.minelittlepony.unicopia.ability.magic.spell.ProjectileSpell;
import com.minelittlepony.unicopia.ability.magic.spell.Spell;
import com.minelittlepony.unicopia.ability.magic.spell.effect.ShieldSpell;

import net.minecraft.entity.Entity;

public interface SpellPredicate<T extends Spell> extends Predicate<Spell> {
    SpellPredicate<IllusionarySpell> CAN_SUPPRESS = s -> s instanceof IllusionarySpell;
    SpellPredicate<ProjectileSpell> HAS_PROJECTILE_EVENTS = s -> s instanceof ProjectileSpell;
    SpellPredicate<AbstractDisguiseSpell> IS_DISGUISE = s -> s instanceof AbstractDisguiseSpell;
    SpellPredicate<ShieldSpell> IS_SHIELD_LIKE = spell -> spell instanceof ShieldSpell;

    default boolean isOn(Caster<?> caster) {
        return caster.getSpellSlot().contains(this);
    }

    default boolean isOn(Entity entity) {
        return Caster.of(entity).filter(this::isOn).isPresent();
    }
}