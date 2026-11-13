/*
 * Copyright (c) 2026, Alexb
 * All rights reserved.
 * Licensed under BSD 2-Clause. See LICENSE.
 */
package com.rebuildplanner;

import lombok.Value;

/**
 * Everything the planner needs to know about an item. Kept behind an interface
 * so the planning logic can be tested without a running client.
 */
public interface ItemInfo
{
	/** Offensive stats of a piece of equipment. */
	@Value
	class Stats
	{
		int astab;
		int aslash;
		int acrush;
		int amagic;
		int arange;
		int str;
		int rstr;
		double mdmg;
		/** Attack speed in ticks; only meaningful on a weapon. */
		int aspeed;
		boolean twoHanded;

		/** Shorthand for armour, which has no speed and is never two-handed. */
		public static Stats armour(int astab, int aslash, int acrush, int amagic, int arange,
								   int str, int rstr, double mdmg)
		{
			return new Stats(astab, aslash, acrush, amagic, arange, str, rstr, mdmg, 0, false);
		}
	}

	String name(int itemId);

	/** Live Grand Exchange price per unit, or 0 when there is none. */
	long price(int itemId);

	boolean tradeable(int itemId);

	/**
	 * What it costs to get one: its price, or for an item that is made rather
	 * than bought, the price of its parts. 0 when it cannot be had for gp.
	 */
	default long buyPrice(int itemId)
	{
		return tradeable(itemId) ? price(itemId) : 0;
	}

	/** Null when the item is not equipment. */
	Stats stats(int itemId);
}
