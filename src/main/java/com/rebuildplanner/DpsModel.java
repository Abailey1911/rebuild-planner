/*
 * Copyright (c) 2026, Alexb
 * All rights reserved.
 * Licensed under BSD 2-Clause. See LICENSE.
 */
package com.rebuildplanner;

import lombok.Value;

/**
 * Relative damage of a setup built around one megarare.
 *
 * <p>The weapon is fixed, so attack speed cancels out: the score is accuracy
 * times max hit. It exists to answer one question - how much damage is lost by
 * swapping a piece of armour for a cheaper one - so it models the passives
 * that decide that answer and nothing else.
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

	/** Gear totals for one setup, plus whether the elite void set is worn. */
	public static class Totals
	{
		int aslash;
		int amagic;
		int arange;
		int str;
		int rstr;
		double mdmg;
		boolean eliteVoid;

		void add(ItemInfo.Stats s)
		{
			if (s == null)
			{
				return;
			}
			aslash += s.getAslash();
			amagic += s.getAmagic();
			arange += s.getArange();
			str += s.getStr();
			rstr += s.getRstr();
			mdmg += s.getMdmg();
		}
	}

	private DpsModel()
	{
	}

	public static double score(String style, Totals gear, Levels levels,
							   RebuildData.Target target, int gearMultiplier)
	{
		switch (style)
		{
			case "RANGED":
				return twistedBow(gear, levels, target);
			case "MAGIC":
				return tumekensShadow(gear, levels, target, Math.max(1, gearMultiplier));
			default:
				return scythe(gear, levels, target);
		}
	}

	/**
	 * Twisted bow: accuracy and damage both scale with the target's Magic
	 * level, capped at 250.
	 */
	private static double twistedBow(Totals gear, Levels levels, RebuildData.Target target)
	{
		final int ranged = levels.getRanged() + 4 + levels.getRanged() / 10;
		final double m = Math.min(250, Math.max(0, target.getMagicLevel()));
		final double accuracyPct = Math.min(140,
			140 + (3 * m - 10) / 100 - Math.pow(3 * m / 10 - 100, 2) / 100);
		final double damagePct = Math.min(250,
			250 + (3 * m - 14) / 100 - Math.pow(3 * m / 10 - 140, 2) / 100);

		double effectiveAttack = Math.floor(ranged * 1.20) + 8;
		double effectiveStrength = Math.floor(ranged * 1.23) + 8;
		if (gear.eliteVoid)
		{
			effectiveAttack = Math.floor(effectiveAttack * 1.10);
			effectiveStrength = Math.floor(effectiveStrength * 1.125);
		}

		final double attackRoll = effectiveAttack * (gear.arange + 64) * accuracyPct / 100;
		final double maxHit = Math.floor(0.5 + effectiveStrength * (gear.rstr + 64) / 640)
			* damagePct / 100;
		final double defenceRoll = (target.getDefenceLevel() + 9) * (target.getDrange() + 64.0);
		return hitChance(attackRoll, defenceRoll) * maxHit;
	}

	/**
	 * Tumeken's shadow: multiplies the magic attack bonus and magic damage of
	 * everything worn, which is why armour matters far more here than with any
	 * other weapon.
	 */
	private static double tumekensShadow(Totals gear, Levels levels,
										 RebuildData.Target target, int multiplier)
	{
		final int magic = levels.getMagic();
		double effectiveAttack = Math.floor(magic * 1.25) + 9;
		if (gear.eliteVoid)
		{
			effectiveAttack = Math.floor(effectiveAttack * 1.45);
		}

		final double attackBonus = gear.amagic * multiplier;
		double damagePct = Math.min(100, gear.mdmg * multiplier);
		if (gear.eliteVoid)
		{
			damagePct += 5;
		}

		final double attackRoll = effectiveAttack * (attackBonus + 64);
		final double maxHit = Math.floor((magic / 3 + 1) * (1 + damagePct / 100));
		final double defenceRoll = (target.getMagicLevel() + 9) * (target.getDmagic() + 64.0);
		return hitChance(attackRoll, defenceRoll) * maxHit;
	}

	private static double scythe(Totals gear, Levels levels, RebuildData.Target target)
	{
		final int attack = levels.getAttack() + 5 + (int) (levels.getAttack() * 0.15);
		final int strength = levels.getStrength() + 5 + (int) (levels.getStrength() * 0.15);

		double effectiveAttack = Math.floor(attack * 1.20) + 8;
		double effectiveStrength = Math.floor(strength * 1.23) + 11;
		if (gear.eliteVoid)
		{
			effectiveAttack = Math.floor(effectiveAttack * 1.10);
			effectiveStrength = Math.floor(effectiveStrength * 1.10);
		}

		final double attackRoll = effectiveAttack * (gear.aslash + 64);
		final double maxHit = Math.floor(0.5 + effectiveStrength * (gear.str + 64) / 640);
		final double defenceRoll = (target.getDefenceLevel() + 9) * (target.getDslash() + 64.0);
		return hitChance(attackRoll, defenceRoll) * maxHit;
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
