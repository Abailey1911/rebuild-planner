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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;

public class PlannerTest
{
	private static final DpsModel.Levels MAXED = new DpsModel.Levels(99, 99, 99, 99);
	private static final Planner.Settings SETTINGS = new Planner.Settings(1_000_000, 3_000_000, 15, 40);

	/** Unrelated tradeable used as bank filler. */
	private static final int SPARE = 900_001;
	private static final int PRAYER_POTION = 2434;

	private List<RebuildData.Goal> goals;
	private FakeItems items;
	private Planner planner;
	private Map<Integer, Long> owned;

	private static final class FakeItems implements ItemInfo
	{
		final Map<Integer, String> names = new HashMap<>();
		final Map<Integer, Long> prices = new HashMap<>();
		final Map<Integer, Stats> stats = new HashMap<>();
		final Set<Integer> untradeable = new HashSet<>();

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
			return !untradeable.contains(itemId) && prices.getOrDefault(itemId, 0L) > 0;
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
		owned = new HashMap<>();

		items.add(SPARE, "Spare trophy", 2_000_000_000L, null);
		items.add(PRAYER_POTION, "Prayer potion(4)", 9_000, null);
	}

	// ---- Helpers -------------------------------------------------------------

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
	private static int id(RebuildData.Method method, String style, String item)
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

	/** Adds an item from a method's lists, and optionally owns it. */
	private int item(RebuildData.Method method, String style, String name, long price, ItemInfo.Stats stats,
					 long held)
	{
		final int id = id(method, style, name);
		items.add(id, name, price, stats);
		if (held > 0)
		{
			owned.put(id, held);
		}
		return id;
	}

	private static ItemInfo.Stats weapon(int stab, int slash, int crush, int magic, int range, int str, int rstr,
										 int speed, boolean twoHanded)
	{
		return new ItemInfo.Stats(stab, slash, crush, magic, range, str, rstr, 0, speed, twoHanded);
	}

	private static ItemInfo.Stats magic(int attack, double damage)
	{
		return ItemInfo.Stats.armour(0, 0, 0, attack, 0, 0, 0, damage);
	}

	private static ItemInfo.Stats ranged(int attack, int strength)
	{
		return ItemInfo.Stats.armour(0, 0, 0, 0, attack, 0, strength, 0);
	}

	private static ItemInfo.Stats melee(int stab, int slash, int str)
	{
		return ItemInfo.Stats.armour(stab, slash, 0, 0, 0, str, 0, 0);
	}

	private RebuildData.Method toa(long shadowPrice)
	{
		final RebuildData.Goal shadow = goal("Tumeken's shadow");
		items.add(shadow.getId(), "Tumeken's shadow", 0, weapon(0, 0, 0, 35, 0, 0, 0, 5, true));
		items.add(shadow.getBuyId(), "Tumeken's shadow (uncharged)", shadowPrice, null);
		return method(shadow, "Tombs of Amascut (solo 450)");
	}

	private Plan plan(String goal, RebuildData.Method method, Planner.Settings settings, Overrides overrides)
	{
		return planner.plan(goal(goal), method, owned, 0, MAXED, settings, overrides);
	}

	private static boolean keeps(Plan plan, String name)
	{
		return plan.getKeeps().stream().anyMatch(k -> k.getName().equals(name));
	}

	private static Plan.Sale sale(Plan plan, int id)
	{
		return plan.getSales().stream().filter(s -> s.getItemId() == id).findFirst().orElse(null);
	}

	// ---- Tests ---------------------------------------------------------------

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
					assertTrue("Switches need their own target", role.isMain() || role.getTarget() != null);
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
		final RebuildData.Method toa = toa(1_200_000_000L);
		final int top = item(toa, "MAGIC", "Ancestral robe top", 120_000_000L, magic(35, 3), 1);
		owned.put(SPARE, 1L);
		owned.put(PRAYER_POTION, 1000L);

		final Plan plan = plan("Tumeken's shadow", toa, SETTINGS, null);

		assertTrue(plan.isFunded());
		assertTrue(plan.getSwaps().isEmpty());
		assertTrue(keeps(plan, "Ancestral robe top"));
		assertTrue(sale(plan, top) == null);

		final Plan.Sale potions = sale(plan, PRAYER_POTION);
		assertNotNull("1,000 prayer potions are worth 9m, so some should be offered", potions);
		assertEquals(334, potions.getKept());
		assertEquals(666, potions.getQuantity());
	}

	/**
	 * A Bow of faerdhinen switch keeps the crystal set together, and the Masori
	 * and crossbow that the switch no longer uses are spare.
	 */
	@Test
	public void keepsCrystalWithBowfaAndSellsMasori()
	{
		final RebuildData.Method toa = toa(1_200_000_000L);
		item(toa, "MAGIC", "Ancestral robe top", 120_000_000L, magic(35, 3), 1);
		item(toa, "RANGED", "Bow of faerdhinen", 40_000_000L, weapon(0, 0, 0, 0, 128, 0, 106, 4, true), 1);
		item(toa, "RANGED", "Crystal helm", 2_000_000L, ranged(9, 0), 1);
		item(toa, "RANGED", "Crystal body", 3_000_000L, ranged(31, 0), 1);
		item(toa, "RANGED", "Crystal legs", 2_500_000L, ranged(18, 0), 1);
		final int masori = item(toa, "RANGED", "Masori body (f)", 85_000_000L, ranged(43, 4), 1);
		final int acb = item(toa, "RANGED", "Armadyl crossbow", 30_000_000L,
			weapon(0, 0, 0, 0, 100, 0, 0, 5, false), 1);
		final int bolts = item(toa, "RANGED", "Ruby dragon bolts (e)", 10_000L, ranged(0, 122), 1500);
		owned.put(SPARE, 1L);

		final Plan plan = plan("Tumeken's shadow", toa, SETTINGS, null);

		assertTrue(keeps(plan, "Bow of faerdhinen"));
		assertTrue(keeps(plan, "Crystal body"));
		assertTrue(keeps(plan, "Crystal legs"));
		assertFalse(keeps(plan, "Masori body (f)"));
		assertFalse("Bolts do nothing for a Bowfa", keeps(plan, "Ruby dragon bolts (e)"));
		assertNotNull(sale(plan, masori));
		assertNotNull(sale(plan, acb));
		assertEquals("No reserve of ammunition nothing fires", 1500, sale(plan, bolts).getQuantity());
	}

	/** A Twisted bow fires arrows; bolts are spare however good they are. */
	@Test
	public void twistedBowKeepsArrowsNotBolts()
	{
		final RebuildData.Goal bow = goal("Twisted bow");
		final RebuildData.Method doom = method(bow, "Doom of Mokhaiotl");
		items.add(bow.getId(), "Twisted bow", 1_400_000_000L, weapon(0, 0, 0, 0, 70, 0, 20, 5, true));
		final int bolts = item(doom, "RANGED", "Ruby dragon bolts (e)", 10_000L, ranged(0, 122), 2000);
		item(doom, "RANGED", "Dragon arrow", 1_500L, ranged(0, 60), 5000);
		owned.put(SPARE, 1L);

		final Plan plan = plan("Twisted bow", doom, SETTINGS, null);

		assertTrue(keeps(plan, "Dragon arrow"));
		assertFalse(keeps(plan, "Ruby dragon bolts (e)"));
		assertEquals(2000, sale(plan, bolts).getQuantity());
	}

	/** Against the ToA Warden a Fang with a defender beats a two-handed halberd. */
	@Test
	public void fangAndDefenderBeatTheHalberdAtToA()
	{
		final RebuildData.Method toa = toa(1_200_000_000L);
		item(toa, "MAGIC", "Ancestral robe top", 120_000_000L, magic(35, 3), 1);
		item(toa, "MELEE", "Osmumten's fang", 25_000_000L, weapon(105, 75, 0, 0, 0, 103, 0, 5, false), 1);
		item(toa, "MELEE", "Avernic defender", 0, melee(30, 29, 8), 1);
		items.untradeable.add(id(toa, "MELEE", "Avernic defender"));
		final int halberd = item(toa, "MELEE", "Noxious halberd", 60_000_000L,
			weapon(55, 132, 0, 0, 0, 142, 0, 5, true), 1);
		owned.put(SPARE, 1L);

		final Plan plan = plan("Tumeken's shadow", toa, SETTINGS, null);

		assertTrue(keeps(plan, "Osmumten's fang"));
		assertTrue(keeps(plan, "Avernic defender"));
		assertFalse(keeps(plan, "Noxious halberd"));
		assertNotNull(sale(plan, halberd));
	}

	/**
	 * Switches are sold down as whole kits by price tier, so an expensive chest
	 * is never kept over cheap legs.
	 */
	@Test
	public void neverKeepsAnOathplateChestOverCheapLegs()
	{
		final RebuildData.Method toa = toa(150_000_000L);
		item(toa, "MAGIC", "Ancestral robe top", 120_000_000L, magic(35, 3), 1);
		item(toa, "MELEE", "Osmumten's fang", 25_000_000L, weapon(105, 75, 0, 0, 0, 103, 0, 5, false), 1);
		item(toa, "MELEE", "Oathplate chest", 90_000_000L, melee(0, 0, 10), 1);
		item(toa, "MELEE", "Oathplate legs", 65_000_000L, melee(0, 0, 8), 1);
		item(toa, "MELEE", "Fighter torso", 0, melee(0, 0, 4), 1);
		items.untradeable.add(id(toa, "MELEE", "Fighter torso"));
		item(toa, "MELEE", "Bandos tassets", 25_000_000L, melee(0, 0, 2), 0);
		item(toa, "MELEE", "Mixed hide legs", 1_000_000L, melee(0, 0, 0), 0);

		final Plan plan = plan("Tumeken's shadow", toa, SETTINGS, null);

		assertTrue(plan.isFunded());
		final boolean chestKept = keeps(plan, "Oathplate chest");
		final boolean cheapLegs = plan.getSwaps().stream().anyMatch(s -> s.getUse().equals("Mixed hide legs"));
		assertFalse("Oathplate chest over mixed hide legs", chestKept && cheapLegs);
		assertTrue("The main setup is untouched", keeps(plan, "Ancestral robe top"));
	}

	/** An item the player crosses out is never sold; something else is found. */
	@Test
	public void honoursItemsThePlayerKeeps()
	{
		final RebuildData.Method toa = toa(1_200_000_000L);
		item(toa, "MAGIC", "Ancestral robe top", 120_000_000L, magic(35, 3), 1);
		final int second = 900_002;
		items.add(second, "Second trophy", 1_500_000_000L, null);
		owned.put(SPARE, 1L);
		owned.put(second, 1L);

		final Overrides overrides = new Overrides();
		overrides.getExcluded().add(SPARE);
		final Plan plan = plan("Tumeken's shadow", toa, SETTINGS, overrides);

		assertTrue(plan.isFunded());
		assertTrue(sale(plan, SPARE) == null);
		assertNotNull(sale(plan, second));
		assertEquals(1, plan.getExcluded().size());

		// With nothing else to sell, the plan says it cannot be done.
		overrides.getExcluded().add(second);
		final Plan without = plan("Tumeken's shadow", toa, SETTINGS, overrides);
		assertFalse(without.isFunded());
		assertTrue(without.getShortfall() > 0);
	}

	/** The player can sell a different amount, and the plan works around it. */
	@Test
	public void usesThePlayersQuantity()
	{
		final RebuildData.Method toa = toa(1_200_000_000L);
		owned.put(SPARE, 1L);
		owned.put(PRAYER_POTION, 1000L);

		final Overrides overrides = new Overrides();
		overrides.getSellQuantity().put(PRAYER_POTION, 100L);
		final Plan plan = plan("Tumeken's shadow", toa, SETTINGS, overrides);

		final Plan.Sale potions = sale(plan, PRAYER_POTION);
		assertNotNull(potions);
		assertTrue(potions.isCustom());
		assertEquals(100, potions.getQuantity());
		assertEquals(900, potions.getKept());
	}

	/**
	 * Owning Masori body (f) also counts as owning a plain Masori body, so the
	 * planner once "swapped" the (f) for itself five times over. Every item may
	 * be sold once, and never to itself.
	 */
	@Test
	public void neverSwapsAnItemForItself()
	{
		final RebuildData.Goal bow = goal("Twisted bow");
		final RebuildData.Method doom = method(bow, "Doom of Mokhaiotl");
		items.add(bow.getId(), "Twisted bow", 1_400_000_000L, weapon(0, 0, 0, 0, 70, 0, 20, 5, true));
		item(doom, "RANGED", "Masori body (f)", 85_000_000L, ranged(43, 4), 1);
		item(doom, "RANGED", "Masori body", 9_000_000L, ranged(40, 2), 0);

		final Plan plan = plan("Twisted bow", doom, new Planner.Settings(1_000_000, 3_000_000, 50, 40), null);

		final Set<Integer> soldIds = new HashSet<>();
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
		final RebuildData.Method toa = toa(1_200_000_000L);
		item(toa, "MAGIC", "Ancestral robe top", 120_000_000L, magic(35, 3), 1);
		item(toa, "MAGIC", "Ancestral robe bottom", 90_000_000L, magic(26, 3), 1);

		final Plan plan = plan("Tumeken's shadow", toa, new Planner.Settings(1_000_000, 3_000_000, 2, 40), null);

		assertFalse(plan.isFunded());
		assertTrue(plan.getSwaps().isEmpty());
		assertTrue(keeps(plan, "Ancestral robe top"));
		assertTrue(keeps(plan, "Ancestral robe bottom"));
	}
}
