/*
 * Copyright (c) 2026, Alexb
 * All rights reserved.
 * Licensed under BSD 2-Clause. See LICENSE.
 */
package com.rebuildplanner;

import java.util.ArrayList;
import java.util.List;
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
		String role;
		String slot;
		String name;
	}

	/** Gear the method needs that the player does not own. */
	@Value
	public static class Buy
	{
		String role;
		String slot;
		String name;
		long cost;
	}

	/** Something in the bank that is not part of the kit. */
	@Value
	public static class Sale
	{
		int itemId;
		String name;
		long quantity;
		/** How many are held back as a working reserve. */
		long kept;
		/** After Grand Exchange tax. */
		long proceeds;
		/** False once the goal is already covered by the lines above. */
		boolean needed;
	}

	/** Selling a kit piece and making do with something cheaper. */
	@Value
	public static class Swap
	{
		String role;
		String sell;
		String use;
		/** True when the replacement is already owned. */
		boolean useOwned;
		/** Net gp gained, after tax and after paying for the replacement. */
		long freed;
		/** Damage lost on the main setup, in percent. Zero for switches. */
		double lossPct;
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

	private boolean usesVoid;
	/** Damage of the rebuild kit against the best possible kit, 0 to 1. */
	private double dpsVersusBest;
	/** Damage given up by the swaps, in percent of the kit the player owns. */
	private double dpsLossPct;
	private long estGpPerHour;
	/** Gross value of the kit pieces sold in swaps. */
	private long kitSold;

	public boolean isFunded()
	{
		return shortfall <= 0;
	}
}
