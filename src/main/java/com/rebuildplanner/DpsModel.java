/*
 * Copyright (c) 2026, Alexb
 * All rights reserved.
 * Licensed under BSD 2-Clause. See LICENSE.
 */
package com.rebuildplanner;

import java.util.Locale;
import java.util.Map;
import lombok.Value;

/**
 * Damage per second of one setup against one target.
 *
 * <p>It exists to compare setups, not to predict kill times, so it models the
 * passives that decide which gear is worth keeping and nothing else: the
 * Twisted bow's magic scaling, Tumeken's shadow multiplying magic gear, the
 * Scythe's extra hits, Osmumten's fang rolling accuracy twice, the crystal set
 * with a crystal bow, Soulreaper stacks and elite void. These are the same
 * passives Gear Upgrade models, for the same reason - without them a raw stat
 * total picks the wrong gear.
 *
 * <p>Assumes the usual boosts: Piety, Rigour or Augury, and a combat potion.
 */
public final class DpsModel
{
	@Value
	public static class Levels
	{
		int attack;
		int strength;
		int ranged;
		int magic;
	}

	private DpsModel()
	{
	}

	private static String name(ItemInfo items, int id)
	{
		final String n = id > 0 ? items.name(id) : null;
		return n == null ? "" : n.toLowerCase(Locale.ROOT);
	}

	// ---- Ammunition ------------------------------------------------------

	enum Ammo
	{
		NONE, ARROWS, BOLTS, ASCENSION_BOLTS, JAVELINS, ATLATL_DARTS
	}

	/** What a weapon fires from the ammo slot; NONE for weapons that need nothing there. */
	static Ammo firedBy(String weapon)
	{
		if (weapon.startsWith("crystal bow") || weapon.startsWith("bow of faerdhinen"))
		{
			return Ammo.NONE;
		}
		if (weapon.startsWith("ascension crossbow"))
		{
			return Ammo.ASCENSION_BOLTS;
		}
		if (weapon.contains("ballista"))
		{
			return Ammo.JAVELINS;
		}
		if (weapon.startsWith("eclipse atlatl"))
		{
			return Ammo.ATLATL_DARTS;
		}
		if (weapon.contains("crossbow") || weapon.contains("c'bow"))
		{
			return Ammo.BOLTS;
		}
		if (weapon.contains("bow") && !weapon.contains("blowpipe"))
		{
			return Ammo.ARROWS;
		}
		return Ammo.NONE;
	}

	/** What an ammo-slot item is. Blessings and the like are NONE. */
	static Ammo kindOf(String ammo)
	{
		if (ammo.contains("javelin"))
		{
			return Ammo.JAVELINS;
		}
		if (ammo.startsWith("atlatl dart"))
		{
			return Ammo.ATLATL_DARTS;
		}
		if (ammo.contains("ascension bolt"))
		{
			return Ammo.ASCENSION_BOLTS;
		}
		if (ammo.contains("bolt"))
		{
			return Ammo.BOLTS;
		}
		if (ammo.contains("arrow"))
		{
			return Ammo.ARROWS;
		}
		return Ammo.NONE;
	}

	/**
	 * True when this item may sit in the ammo slot beside the weapon. A weapon
	 * that fires nothing still wears a blessing; ammunition only goes with the
	 * weapon that fires it.
	 */
	static boolean ammoFits(ItemInfo items, int weaponId, int ammoId)
	{
		final Ammo kind = kindOf(name(items, ammoId));
		return kind == Ammo.NONE || kind == firedBy(name(items, weaponId));
	}

	// ---- Scoring ---------------------------------------------------------

	private static final class Totals
	{
		int astab;
		int aslash;
		int acrush;
		int amagic;
		int arange;
		int str;
		int rstr;
		double mdmg;

		void add(ItemInfo.Stats s)
		{
			if (s == null)
			{
				return;
			}
			astab += s.getAstab();
			aslash += s.getAslash();
			acrush += s.getAcrush();
			amagic += s.getAmagic();
			arange += s.getArange();
			str += s.getStr();
			rstr += s.getRstr();
			mdmg += s.getMdmg();
		}
	}

	/**
	 * @param worn           slot name to item id; the weapon under "WEAPON"
	 * @param eliteVoid      whether the elite void set is worn in place of armour
	 * @param gearMultiplier Tumeken's shadow multiplier: 4 in ToA, 3 elsewhere
	 */
	public static double dps(ItemInfo items, String style, Map<String, Integer> worn,
							 boolean eliteVoid, Levels levels, RebuildData.Target target,
							 int gearMultiplier)
	{
		final Integer weaponId = worn.get("WEAPON");
		if (weaponId == null || weaponId <= 0 || target == null)
		{
			return 0;
		}
		final String weapon = name(items, weaponId);
		final ItemInfo.Stats weaponStats = items.stats(weaponId);
		final int speed = weaponStats != null && weaponStats.getAspeed() > 0 ? weaponStats.getAspeed() : 4;

		final Totals t = new Totals();
		for (Map.Entry<String, Integer> entry : worn.entrySet())
		{
			final int id = entry.getValue() == null ? -1 : entry.getValue();
			if (id <= 0)
			{
				continue;
			}
			// Ammunition only counts when the weapon fires it.
			if ("AMMO".equals(entry.getKey())
				&& (kindOf(name(items, id)) == Ammo.NONE || kindOf(name(items, id)) != firedBy(weapon)))
			{
				continue;
			}
			t.add(items.stats(id));
		}

		final double perHit;
		switch (style)
		{
			case "RANGED":
				perHit = ranged(items, weapon, t, worn, eliteVoid, levels, target);
				break;
			case "MAGIC":
				perHit = magic(weapon, t, eliteVoid, levels, target, gearMultiplier);
				break;
			default:
				perHit = melee(weapon, t, eliteVoid, levels, target);
				break;
		}
		return perHit / (speed * 0.6);
	}

	private static double melee(String weapon, Totals t, boolean eliteVoid, Levels levels,
								RebuildData.Target target)
	{
		final int attack = levels.getAttack() + 5 + (int) (levels.getAttack() * 0.15);
		final int strength = levels.getStrength() + 5 + (int) (levels.getStrength() * 0.15);
		double effAttack = Math.floor(attack * 1.20) + 8;
		double effStrength = Math.floor(strength * 1.23) + 11;
		if (eliteVoid)
		{
			effAttack = Math.floor(effAttack * 1.10);
			effStrength = Math.floor(effStrength * 1.10);
		}

		double maxHit = Math.floor(0.5 + effStrength * (t.str + 64) / 640);
		if (weapon.startsWith("soulreaper axe"))
		{
			maxHit *= 1.24;
		}

		double best = 0;
		final int[] bonus = {t.astab, t.aslash, t.acrush};
		final int[] defence = {target.getDstab(), target.getDslash(), target.getDcrush()};
		for (int type = 0; type < 3; type++)
		{
			final double attackRoll = effAttack * (bonus[type] + 64);
			final double defenceRoll = (target.getDefenceLevel() + 9) * (defence[type] + 64.0);
			double hit = hitChance(attackRoll, defenceRoll);
			if (type == 0 && weapon.startsWith("osmumten's fang"))
			{
				// Rolls accuracy twice and keeps the better.
				hit = 1 - (1 - hit) * (1 - hit);
			}
			double damage = hit * maxHit / 2;
			if (weapon.startsWith("scythe of vitur"))
			{
				// Two extra hits at half and quarter damage on large targets.
				damage *= 1.75;
			}
			best = Math.max(best, damage);
		}
		return best;
	}

	private static double ranged(ItemInfo items, String weapon, Totals t, Map<String, Integer> worn,
								 boolean eliteVoid, Levels levels, RebuildData.Target target)
	{
		final int ranged = levels.getRanged() + 4 + levels.getRanged() / 10;
		double effAttack = Math.floor(ranged * 1.20) + 8;
		double effStrength = Math.floor(ranged * 1.23) + 8;
		if (eliteVoid)
		{
			effAttack = Math.floor(effAttack * 1.10);
			effStrength = Math.floor(effStrength * 1.125);
		}

		double accuracy = 1;
		double damage = 1;

		if (weapon.startsWith("twisted bow"))
		{
			final double m = Math.min(250, Math.max(0, target.getMagicLevel()));
			accuracy = Math.min(140, 140 + (3 * m - 10) / 100 - Math.pow(3 * m / 10 - 100, 2) / 100) / 100;
			damage = Math.min(250, 250 + (3 * m - 14) / 100 - Math.pow(3 * m / 10 - 140, 2) / 100) / 100;
		}

		if (weapon.startsWith("bow of faerdhinen") || weapon.startsWith("crystal bow"))
		{
			// Crystal armour only does anything with a crystal bow.
			if (name(items, worn.getOrDefault("HEAD", -1)).startsWith("crystal helm"))
			{
				accuracy *= 1.05;
				damage *= 1.025;
			}
			if (name(items, worn.getOrDefault("BODY", -1)).startsWith("crystal body"))
			{
				accuracy *= 1.15;
				damage *= 1.075;
			}
			if (name(items, worn.getOrDefault("LEGS", -1)).startsWith("crystal legs"))
			{
				accuracy *= 1.10;
				damage *= 1.05;
			}
		}

		final double attackRoll = effAttack * (t.arange + 64) * accuracy;
		final double maxHit = Math.floor(0.5 + effStrength * (t.rstr + 64) / 640) * damage;
		final double defenceRoll = (target.getDefenceLevel() + 9) * (target.getDrange() + 64.0);
		return hitChance(attackRoll, defenceRoll) * maxHit / 2;
	}

	private static double magic(String weapon, Totals t, boolean eliteVoid, Levels levels,
								RebuildData.Target target, int gearMultiplier)
	{
		final int magic = levels.getMagic();
		double effAttack = Math.floor(magic * 1.25) + 9;
		if (eliteVoid)
		{
			effAttack = Math.floor(effAttack * 1.45);
		}

		double attackBonus = t.amagic;
		double damagePct = t.mdmg;
		final double base;
		if (weapon.startsWith("tumeken's shadow"))
		{
			final int mult = Math.max(1, gearMultiplier);
			attackBonus *= mult;
			damagePct = Math.min(100, damagePct * mult);
			base = magic / 3 + 1;
		}
		else if (weapon.startsWith("sanguinesti staff"))
		{
			base = magic / 3 - 1;
		}
		else if (weapon.startsWith("trident of the swamp"))
		{
			base = magic / 3 - 2;
		}
		else if (weapon.startsWith("trident of the seas"))
		{
			base = magic / 3 - 5;
		}
		else if (weapon.startsWith("warped sceptre"))
		{
			base = (8 * magic + 96) / 37;
		}
		else
		{
			// A standard elemental or ancient spell.
			base = 24;
		}
		if (eliteVoid)
		{
			damagePct += 5;
		}

		final double attackRoll = effAttack * (attackBonus + 64);
		final double maxHit = Math.floor(base * (1 + damagePct / 100));
		final double defenceRoll = (target.getMagicLevel() + 9) * (target.getDmagic() + 64.0);
		return hitChance(attackRoll, defenceRoll) * maxHit / 2;
	}

	static double hitChance(double attackRoll, double defenceRoll)
	{
		if (attackRoll > defenceRoll)
		{
			return 1.0 - (defenceRoll + 2.0) / (2.0 * (attackRoll + 1.0));
		}
		return attackRoll / (2.0 * (defenceRoll + 1.0));
	}
}
