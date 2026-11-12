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

	public boolean isEmpty()
	{
		return excluded.isEmpty() && sellQuantity.isEmpty();
	}
}
