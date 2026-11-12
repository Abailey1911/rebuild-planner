/*
 * Copyright (c) 2026, Alexb
 * All rights reserved.
 * Licensed under BSD 2-Clause. See LICENSE.
 */
package com.rebuildplanner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

public class PlannerTest
{
	private static final DpsModel.Levels MAXED = new DpsModel.Levels(99, 99, 99, 99);
	private static final Planner.Settings SETTINGS = new Planner.Settings(1_000_000, 3_000_000, 15);

	/** Unrelated tradeables used as bank filler. */
	private static final int SPARE = 900_001;
	private static final int PRAYER_POTION = 2434;

	private List<RebuildData.Goal> goals;
	private FakeItems items;
	private Planner planner;

	private static final class FakeItems implements ItemInfo
	{
		final Map<Integer, String> names = new HashMap<>();
		final Map<Integer, Long> prices = new HashMap<>();
		final Map<Integer, Stats> stats = new HashMap<>();

		void add(int id, String name, long price, Stats s)
		{
			names.put(id, name);
			prices.put(id, price);
			if (s != null)
			{
				stats.put(id, s);
			}
		}

		@Override
		public String name(int itemId)
		{
			return names.getOrDefault(itemId, "Item " + itemId);
		}

		@Override
		public long price(int itemId)
		{
			return prices.getOrDefault(itemId, 0L);
		}

		@Override
		public boolean tradeable(int itemId)
		{
			return prices.getOrDefault(itemId, 0L) > 0;
		}

		@Override
		public Stats stats(int itemId)
		{
			return stats.get(itemId);
		}
	}

	@Before
	public void setUp()
	{
		final Gson gson = new Gson();
		goals = RebuildData.loadGoals(gson);
		items = new FakeItems();
		planner = new Planner(items, RebuildData.loadVariants(gson));

		items.add(SPARE, "Spare trophy", 2_000_000_000L, null);
		items.add(PRAYER_POTION, "Prayer potion(4)", 9_000, null);
	}

	private RebuildData.Goal goal(String name)
	{
		for (RebuildData.Goal goal : goals)
		{
			if (goal.getName().equals(name))
			{
				return goal;
			}
		}
		throw new AssertionError("No goal " + name);
	}

	private RebuildData.Method method(RebuildData.Goal goal, String name)
	{
		for (RebuildData.Method method : goal.getMethods())
		{
			if (method.getName().startsWith(name))
			{
				return method;
			}
		}
		throw new AssertionError("No method " + name);
	}

	/** Id of a named item in one of a method's gear lists. */
	private int id(RebuildData.Method method, String style, String item)
	{
		for (RebuildData.Role role : method.getRoles())
		{
			if (!role.getStyle().equals(style))
			{
				continue;
			}
			for (RebuildData.Slot slot : role.getSlots())
			{
				final int i = slot.getItems().indexOf(item);
				if (i >= 0)
				{
					return slot.getIds().get(i);
				}
			}
		}
		throw new AssertionError("No " + item + " in " + style);
	}

	private static ItemInfo.Stats magic(int attack, double damage)
	{
		return new ItemInfo.Stats(0, attack, 0, 0, 0, damage, false);
	}

	private static ItemInfo.Stats ranged(int attack, int strength)
	{
		return new ItemInfo.Stats(0, 0, attack, 0, strength, 0, false);
	}

	@Test
	public void bundledDataLoads()
	{
		assertEquals(3, goals.size());
		for (RebuildData.Goal goal : goals)
		{
			assertFalse(goal.getMethods().isEmpty());
			for (RebuildData.Method method : goal.getMethods())
			{
				assertNotNull(method.getTarget());
				assertTrue(method.getRoles().stream().anyMatch(RebuildData.Role::isMain));
				for (RebuildData.Role role : method.getRoles())
				{
					for (RebuildData.Slot slot : role.getSlots())
					{
						assertEquals(slot.getItems().size(), slot.getIds().size());
					}
				}
			}
		}
	}

	@Test
	public void geTaxIsTwoPercentCappedAtFiveMillion()
	{
		assertEquals(0, Planner.geTax(40));
		assertEquals(20_000, Planner.geTax(1_000_000));
		assertEquals(5_000_000, Planner.geTax(1_500_000_000L));
	}

	/** Spare bank items go first, supplies keep a reserve, and the kit is untouched. */
	@Test
	public void sellsSpareItemsBeforeTouchingTheKit()
	{
		final RebuildData.Goal shadow = goal("Tumeken's shadow");
		final RebuildData.Method toa = method(shadow, "Tombs of Amascut (solo 450)");
		items.add(shadow.getId(), "Tumeken's shadow", 0, new ItemInfo.Stats(0, 35, 0, 0, 0, 0, true));
		items.add(shadow.getBuyId(), "Tumeken's shadow (uncharged)", 1_200_000_000L, null);

		final int top = id(toa, "MAGIC", "Ancestral robe top");
		items.add(top, "Ancestral robe top", 120_000_000L, magic(35, 3));

		final Map<Integer, Long> owned = new HashMap<>();
		owned.put(top, 1L);
		owned.put(SPARE, 1L);
		owned.put(PRAYER_POTION, 1000L);

		final Plan plan = planner.plan(shadow, toa, owned, 0, MAXED, SETTINGS);

		assertTrue(plan.isFunded());
		assertTrue(plan.getSwaps().isEmpty());
		assertTrue(plan.getKeeps().stream().anyMatch(k -> k.getName().equals("Ancestral robe top")));
		assertTrue(plan.getSales().stream().noneMatch(s -> s.getItemId() == top));

		final Plan.Sale potions = plan.getSales().stream()
			.filter(s -> s.getItemId() == PRAYER_POTION).findFirst().orElse(null);
		assertNotNull("1,000 prayer potions are worth 9m, so some should be offered", potions);
		assertEquals(334, potions.getKept());
		assertEquals(666, potions.getQuantity());
	}

	/** A switch is sold down before the main setup loses anything. */
	@Test
	public void swapsSwitchesBeforeTheMainSetup()
	{
		final RebuildData.Goal shadow = goal("Tumeken's shadow");
		final RebuildData.Method toa = method(shadow, "Tombs of Amascut (solo 450)");
		items.add(shadow.getId(), "Tumeken's shadow", 0, new ItemInfo.Stats(0, 35, 0, 0, 0, 0, true));
		items.add(shadow.getBuyId(), "Tumeken's shadow (uncharged)", 150_000_000L, null);

		final int top = id(toa, "MAGIC", "Ancestral robe top");
		final int masori = id(toa, "RANGED", "Masori body (f)");
		items.add(top, "Ancestral robe top", 120_000_000L, magic(35, 3));
		items.add(masori, "Masori body (f)", 160_000_000L, ranged(43, 4));

		final Map<Integer, Long> owned = new HashMap<>();
		owned.put(top, 1L);
		owned.put(masori, 1L);

		final Plan plan = planner.plan(shadow, toa, owned, 0, MAXED, SETTINGS);

		assertTrue(plan.isFunded());
		assertEquals(1, plan.getSwaps().size());
		assertEquals("Masori body (f)", plan.getSwaps().get(0).getSell());
		assertEquals(0, plan.getDpsLossPct(), 0.001);
		assertTrue(plan.getKeeps().stream().anyMatch(k -> k.getName().equals("Ancestral robe top")));
	}

	/**
	 * Owning Masori body (f) also counts as owning a plain Masori body, so the
	 * planner once "swapped" the (f) for itself five times over, counting its
	 * price each time. Every item may be sold once, and never to itself.
	 */
	@Test
	public void neverSwapsAnItemForItself()
	{
		final RebuildData.Goal bow = goal("Twisted bow");
		final RebuildData.Method doom = method(bow, "Doom of Mokhaiotl");
		items.add(bow.getId(), "Twisted bow", 1_400_000_000L, new ItemInfo.Stats(0, 0, 70, 0, 20, 0, true));

		final int bodyF = id(doom, "RANGED", "Masori body (f)");
		final int body = id(doom, "RANGED", "Masori body");
		items.add(bodyF, "Masori body (f)", 85_000_000L, ranged(43, 4));
		items.add(body, "Masori body", 9_000_000L, ranged(40, 2));

		final Map<Integer, Long> owned = new HashMap<>();
		owned.put(bodyF, 1L);

		final Plan plan = planner.plan(bow, doom, owned, 0, MAXED, new Planner.Settings(1_000_000, 3_000_000, 50));

		final java.util.Set<Integer> soldIds = new java.util.HashSet<>();
		for (Plan.Swap swap : plan.getSwaps())
		{
			assertTrue("Sold twice: " + swap.getSell(), soldIds.add(swap.getSellId()));
			assertFalse("Swapped for itself: " + swap.getSell(), swap.getSellId() == swap.getUseId());
			assertFalse("The (f) cannot be replaced by what it already counts as",
				swap.isUseOwned() && swap.getUse().equals("Masori body"));
		}
		assertTrue(plan.getRaised() <= 85_000_000L);
	}

	/** Ancestral is not sold when it would cost more damage than the cap allows. */
	@Test
	public void refusesToGutTheMainSetup()
	{
		final RebuildData.Goal shadow = goal("Tumeken's shadow");
		final RebuildData.Method toa = method(shadow, "Tombs of Amascut (solo 450)");
		items.add(shadow.getId(), "Tumeken's shadow", 0, new ItemInfo.Stats(0, 35, 0, 0, 0, 0, true));
		items.add(shadow.getBuyId(), "Tumeken's shadow (uncharged)", 1_200_000_000L, null);

		final int top = id(toa, "MAGIC", "Ancestral robe top");
		final int bottom = id(toa, "MAGIC", "Ancestral robe bottom");
		items.add(top, "Ancestral robe top", 120_000_000L, magic(35, 3));
		items.add(bottom, "Ancestral robe bottom", 90_000_000L, magic(26, 3));

		final Map<Integer, Long> owned = new HashMap<>();
		owned.put(top, 1L);
		owned.put(bottom, 1L);

		final Plan plan = planner.plan(shadow, toa, owned, 0, MAXED, new Planner.Settings(1_000_000, 3_000_000, 2));

		assertFalse(plan.isFunded());
		assertTrue(plan.getSwaps().isEmpty());
		assertEquals(2, plan.getKeeps().size());
	}
}
