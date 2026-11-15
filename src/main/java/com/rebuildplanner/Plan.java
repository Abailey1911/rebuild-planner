/*
 * Copyright (c) 2026, Alexb
 * All rights reserved.
 * Licensed under BSD 2-Clause. See LICENSE.
 */
package com.rebuildplanner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;
import lombok.Value;

/** The outcome of planning one rebuild: what to keep, sell, swap and buy. */
@Data
public class Plan
{
	/** A piece of gear the method needs and the player should hold on to. */
	@Value
	public static class Keep
	{
		int itemId;
		String role;
		String slot;
		String name;
		/** More than 1 for runes held back to charge the weapon. */
		long quantity;
		/** True for a piece the swaps tell the player to buy, not one they hold. */
		boolean toBuy;
	}

	/** Gear the method needs that the player does not own. */
	@Value
	public static class Buy
	{
		int itemId;
		String role;
		String slot;
		String name;
		long quantity;
		long cost;
	}

	/** Something in the bank that is not part of the kit. */
	@Value
	public static class Sale
	{
		int itemId;
		String name;
		/** How many to sell. */
		long quantity;
		/** How many are held back. */
		long kept;
		/** Total held. */
		long held;
		/** After Grand Exchange tax. */
		long proceeds;
		/** False once the goal is already covered by the lines above. */
		boolean needed;
		/** True when the player set the quantity themselves. */
		boolean custom;
	}

	/** Selling a kit piece and making do with something cheaper. */
	@Value
	public static class Swap
	{
		/** The item sold, for its picture and for excluding it. */
		int sellId;
		/** Replacement's picture, or -1 when the slot is left empty. */
		int useId;
		String role;
		String sell;
		String use;
		/** True when the replacement is already owned. */
		boolean useOwned;
		/** Net gp gained, after tax and after paying for the replacement. */
		long freed;
		/** Everything else the slot could use instead, best first; empty when there is no choice. */
		List<Choice> choices;
		/** True when the player picked the replacement themselves. */
		boolean custom;
	}

	/** One thing a sold kit piece could be replaced with. */
	@Value
	public static class Choice
	{
		/** The id to hand back when the player picks this. */
		int id;
		/** The id to draw: the form the player holds, when they hold one. */
		int iconId;
		String name;
		boolean owned;
		/** What it costs to buy; 0 when owned. */
		long price;
	}

	/** For a gear upgrade: what it adds to one style's best setup. */
	@Value
	public static class Check
	{
		/** Melee, Ranged or Magic. */
		String style;
		/** The weapon the setup is built around. */
		String weapon;
		/** What the player wears in the upgrade's slot today; null for nothing. */
		String replaces;
		/** Damage gained, in percent; 0 when it could not be worked out. */
		double gainPct;
	}

	/** Something the player has said not to sell. */
	@Value
	public static class Excluded
	{
		int itemId;
		String name;
	}

	private RebuildData.Goal goal;
	private RebuildData.Method method;
	private boolean alreadyOwned;

	private long goalPrice;
	private long coins;
	private long buyCost;
	/** What still has to be raised by selling. */
	private long needed;
	private long raised;
	private long shortfall;

	private final List<Keep> keeps = new ArrayList<>();
	private final List<Buy> buys = new ArrayList<>();
	private final List<Sale> sales = new ArrayList<>();
	private final List<Swap> swaps = new ArrayList<>();
	private final List<Excluded> excluded = new ArrayList<>();
	/** Things the player holds that the plan leaves alone but they could choose to sell; price is the stack's value. */
	private final List<Choice> addable = new ArrayList<>();
	/** For a gear upgrade: the damage check in each style. */
	private final List<Check> checks = new ArrayList<>();
	/** Damage given up per setup, in percent, for setups that were sold down. */
	private final Map<String, Double> roleLoss = new LinkedHashMap<>();

	private boolean usesVoid;
	/** Damage of the rebuild kit against the best possible kit, 0 to 1. */
	private double dpsVersusBest;
	/** Damage given up on the main setup, in percent. */
	private double dpsLossPct;
	private long estGpPerHour;
	/** The player's best weapon today, which the megarare would replace; null if they own none. */
	private String todayWeapon;
	/** Damage the rebuilt setup gains over today's, in percent. */
	private double gainPct;
	/** False when the rebuilt setup barely beats what the player already has. */
	private boolean worthIt = true;
	/** Gross value of the kit pieces sold in swaps. */
	private long kitSold;

	public boolean isFunded()
	{
		return shortfall <= 0;
	}
}
