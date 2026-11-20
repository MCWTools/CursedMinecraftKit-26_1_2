package khoa.cmk;

import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.LivingEntity;

/**
 * A DamageSource that wraps a real damage type (we use generic_kill, which
 * sits in bypasses_invulnerability / bypasses_resistance, so totems,
 * creative invulnerability, Resistance, absorption etc. can't stop it) but
 * replaces the death message with arbitrary player-supplied text.
 *
 * CombatTracker#getDeathMessage ends in damageSource.getLocalizedDeathMessage(victim)
 * for normal (non fall-variant) damage types, so overriding it here is all it
 * takes - the same Component is used for the chat broadcast and for the
 * death screen text, no mixin needed.
 */
public final class CmkDeathSource extends DamageSource {
	private final Component message;

	public CmkDeathSource(Holder<DamageType> type, Component message) {
		super(type);
		this.message = message;
	}

	@Override
	public Component getLocalizedDeathMessage(LivingEntity victim) {
		return message;
	}
}
