/*
 * Copyright (c) 2026, Alexb
 * All rights reserved.
 * Licensed under BSD 2-Clause. See LICENSE.
 */
package com.rebuildplanner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class ItemFactsTest
{
	private final AtomicInteger reads = new AtomicInteger();

	private final ItemInfo client = new ItemInfo()
	{
		@Override
		public String name(int itemId)
		{
			reads.incrementAndGet();
			return "Item" + itemId;
		}

		@Override
		public long price(int itemId)
		{
			return itemId * 10L;
		}

		@Override
		public boolean tradeable(int itemId)
		{
			return itemId != 3;
		}

		@Override
		public Stats stats(int itemId)
		{
			return null;
		}
	};

	@Test
	public void loadedItemsAreServedFromTheCopy()
	{
		final ItemFacts facts = new ItemFacts(client, Collections.emptyMap());
		facts.load(Arrays.asList(1, 2, 3));
		facts.load(Arrays.asList(1, 2));
		assertEquals("Each item is read from the client once", 3, reads.get());
		assertEquals("Item2", facts.name(2));
		assertEquals(20, facts.price(2));
		assertFalse(facts.tradeable(3));
		assertTrue(facts.takeMissed().isEmpty());
	}

	@Test
	public void unloadedItemsAreReportedForTheNextLoad()
	{
		final ItemFacts facts = new ItemFacts(client, Collections.emptyMap());
		assertEquals(0, facts.price(7));
		assertFalse(facts.tradeable(7));
		assertEquals(Collections.singleton(7), facts.takeMissed());
		assertTrue("Taking the misses clears them", facts.takeMissed().isEmpty());
		facts.load(Collections.singleton(7));
		assertEquals(70, facts.price(7));
	}
}
