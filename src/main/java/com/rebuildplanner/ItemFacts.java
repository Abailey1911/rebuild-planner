/*
 * Copyright (c) 2026, Alexb
 * All rights reserved.
 * Licensed under BSD 2-Clause. See LICENSE.
 */
package com.rebuildplanner;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A copy of the item facts the planner needs, so planning can run off the client
 * thread. Item names, prices and stats come from the client's item cache, which
 * may only be read on the client thread; reading them there while planning made
 * the game stutter. {@link #load} fills this on the client thread, and the planner
 * then reads it from anywhere. Anything it asks for that was not loaded is noted
 * in {@link #takeMissed} so the next load can add it.
 */
final class ItemFacts implements ItemInfo
{
	/** Prices move; re-read them this often. */
	private static final long PRICE_LIFETIME_MS = 10 * 60_000L;

	private static final class Fact
	{
		final String name;
		final boolean tradeable;
		final long price;
		final Stats stats;

		Fact(String name, boolean tradeable, long price, Stats stats)
		{
			this.name = name;
			this.tradeable = tradeable;
			this.price = price;
			this.stats = stats;
		}
	}

	/** Reads straight from the client; only ever called on the client thread. */
	private final ItemInfo source;
	private final Map<String, List<RebuildData.Component>> components;
	private final Map<Integer, Fact> facts = new ConcurrentHashMap<>();
	private final Set<Integer> missed = ConcurrentHashMap.newKeySet();
	private long loadedAt;

	ItemFacts(ItemInfo source, Map<String, List<RebuildData.Component>> components)
	{
		this.source = source;
		this.components = components;
	}

	/** Client thread: reads any of these items not already known. */
	void load(Collection<Integer> ids)
	{
		final long now = System.currentTimeMillis();
		if (now - loadedAt > PRICE_LIFETIME_MS)
		{
			facts.clear();
			loadedAt = now;
		}
		for (Integer id : ids)
		{
			if (id != null && id > 0 && !facts.containsKey(id))
			{
				facts.put(id, new Fact(source.name(id), source.tradeable(id), source.price(id), source.stats(id)));
			}
		}
	}

	/** Items asked for since the last call that had not been loaded. */
	Set<Integer> takeMissed()
	{
		final Set<Integer> out = new java.util.HashSet<>(missed);
		missed.removeAll(out);
		return out;
	}

	private Fact fact(int itemId)
	{
		final Fact fact = itemId > 0 ? facts.get(itemId) : null;
		if (fact == null && itemId > 0)
		{
			missed.add(itemId);
		}
		return fact;
	}

	@Override
	public String name(int itemId)
	{
		final Fact fact = fact(itemId);
		return fact == null ? "Item " + itemId : fact.name;
	}

	@Override
	public long price(int itemId)
	{
		final Fact fact = fact(itemId);
		return fact == null ? 0 : fact.price;
	}

	@Override
	public boolean tradeable(int itemId)
	{
		final Fact fact = fact(itemId);
		return fact != null && fact.tradeable;
	}

	@Override
	public long buyPrice(int itemId)
	{
		if (tradeable(itemId))
		{
			return price(itemId);
		}
		// Made, not bought: a Neitiznot faceguard is a helm plus a basilisk jaw.
		final List<RebuildData.Component> parts = components.get(String.valueOf(itemId));
		if (parts == null)
		{
			return 0;
		}
		long total = 0;
		for (RebuildData.Component part : parts)
		{
			final long each = price(part.getId());
			if (each <= 0)
			{
				return 0;
			}
			total += each * Math.max(1, part.getQty());
		}
		return total;
	}

	@Override
	public Stats stats(int itemId)
	{
		final Fact fact = fact(itemId);
		return fact == null ? null : fact.stats;
	}
}
