/*
 * Copyright (c) 2026, Alexb
 * All rights reserved.
 * Licensed under BSD 2-Clause. See LICENSE.
 */
package com.rebuildplanner;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.Data;

/** The player's own corrections to the plan. */
@Data
public class Overrides
{
	/** Items the player will not sell, whatever the plan says. */
	private Set<Integer> excluded = new HashSet<>();

	/** Exact amount to sell, set by the player, per item. */
	private Map<Integer, Long> sellQuantity = new HashMap<>();

	/** For a kit piece being sold, the replacement the player picked: sold item id to list item id. */
	private Map<Integer, Integer> replacement = new HashMap<>();

	/** Items the player added to the sell list themselves; the kit goes without them. */
	private Set<Integer> forceSell = new HashSet<>();

	public boolean isEmpty()
	{
		return forceSell.isEmpty() &&excluded.isEmpty() && sellQuantity.isEmpty() && replacement.isEmpty();
	}
}
