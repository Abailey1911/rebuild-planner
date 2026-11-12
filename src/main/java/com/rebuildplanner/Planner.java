/*
 * Copyright (c) 2026, Alexb
 * All rights reserved.
 * Licensed under BSD 2-Clause. See LICENSE.
 */
package com.rebuildplanner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.Value;

/**
 * Works out how to afford a megarare without selling the gear needed to make
 * the money back with it.
 *
 * <p>The order things are sold in is the whole point:
 * <ol>
 *   <li>anything in the bank that is not part of the kit, keeping a working
 *       reserve of supplies;</li>
 *   <li>expensive switch pieces, replaced by cheaper ones - a switch only has
 *       to exist;</li>
 *   <li>pieces of the main setup, cheapest damage first, and only up to a
 *       cap - selling Ancestral to afford a Tumeken's shadow defeats the
 *       purpose.</li>
 * </ol>
 */
public class Planner
{
	static final int COINS = 995;
	static final int PLATINUM_TOKEN = 13204;

	private static final int ELITE_VOID_TOP = 13072;
	private static final int ELITE_VOID_ROBE = 13073;
	private static final int VOID_GLOVES = 8842;
	private static final int VOID_RANGER_HELM = 11664;
	private static final int VOID_MAGE_HELM = 11663;
	private static final int VOID_MELEE_HELM = 11665;
	private static final List<String> VOID_SLOTS = Arrays.asList("HEAD", "BODY", "LEGS", "GLOVES");

	/** A kit piece cheaper than this is not worth swapping out. */
	private static final long MIN_SWAP_VALUE = 500_000;

	private static final Pattern SUPPLY = Pattern.compile(
		"(\\(\\d\\)$)|( rune$)|(arrow)|(bolts)|(dart$)|(javelin)|(scales$)|(chinchompa)|(cannonball)"
			+ "|(^shark$)|(^anglerfish$)|(^manta ray$)|(^cooked karambwan$)|(^sea turtle$)|(^dark crab$)"
			+ "|(^tuna potato$)|(^monkfish$)|(^lobster$)|(^swordfish$)");

	@Value
	public static class Settings
	{
		/** Ignore bank stacks worth less than this. */
		long minStackValue;
		/** Keep this much gp of each supply stack. */
		long supplyReserve;
		/** Most damage the main setup may give up, in percent. */
		double maxDpsLossPct;
	}

	/** One entry in a slot's ranked list. */
	private static final class Option
	{
		final int listId;
		final int rank;
		final String name;
		/** The id actually held (the item or a cosmetic form of it), or -1. */
		int ownedId;

		Option(int listId, int rank, String name, int ownedId)
		{
			this.listId = listId;
			this.rank = rank;
			this.name = name;
			this.ownedId = ownedId;
		}
	}

	private final ItemInfo items;
	private final Map<String, List<Integer>> variants;

	public Planner(ItemInfo items, Map<String, List<Integer>> variants)
	{
		this.items = items;
		this.variants = variants == null ? Collections.emptyMap() : variants;
	}

	public static long geTax(long price)
	{
		return price < 50 ? 0 : Math.min(5_000_000L, price / 50);
	}

	private long net(long price)
	{
		return price - geTax(price);
	}

	static boolean isSupply(String name)
	{
		return name != null && SUPPLY.matcher(name.toLowerCase(Locale.ROOT)).find();
	}

	private int ownedForm(int itemId, Map<Integer, Long> owned)
	{
		if (owned.getOrDefault(itemId, 0L) > 0)
		{
			return itemId;
		}
		for (int variant : variants.getOrDefault(String.valueOf(itemId), Collections.emptyList()))
		{
			if (owned.getOrDefault(variant, 0L) > 0)
			{
				return variant;
			}
		}
		return -1;
	}

	private List<Option> options(RebuildData.Slot slot, Map<Integer, Long> owned, Set<Integer> exclude)
	{
		final List<Option> out = new ArrayList<>();
		for (int i = 0; i < slot.getIds().size(); i++)
		{
			final int id = slot.getIds().get(i);
			if (exclude.contains(id))
			{
				continue;
			}
			final String name = i < slot.getItems().size() ? slot.getItems().get(i) : items.name(id);
			out.add(new Option(id, i, name, ownedForm(id, owned)));
		}
		return out;
	}

	private static String label(RebuildData.Role role)
	{
		final String style = role.getStyle().charAt(0) + role.getStyle().substring(1).toLowerCase(Locale.ROOT);
		return role.isMain() ? style : style + " switch";
	}

	private int voidHelm(String style)
	{
		switch (style)
		{
			case "RANGED":
				return VOID_RANGER_HELM;
			case "MAGIC":
				return VOID_MAGE_HELM;
			default:
				return VOID_MELEE_HELM;
		}
	}

	private double score(RebuildData.Goal goal, RebuildData.Method method,
						 Map<String, Option> picks, boolean useVoid, DpsModel.Levels levels)
	{
		final DpsModel.Totals totals = new DpsModel.Totals();
		totals.add(items.stats(goal.getId()));
		for (Map.Entry<String, Option> entry : picks.entrySet())
		{
			if (entry.getValue() == null || (useVoid && VOID_SLOTS.contains(entry.getKey())))
			{
				continue;
			}
			totals.add(items.stats(entry.getValue().listId));
		}
		if (useVoid)
		{
			totals.add(items.stats(ELITE_VOID_TOP));
			totals.add(items.stats(ELITE_VOID_ROBE));
			totals.add(items.stats(VOID_GLOVES));
			totals.add(items.stats(voidHelm(goal.getStyle())));
			totals.eliteVoid = true;
		}
		return DpsModel.score(goal.getStyle(), totals, levels, method.getTarget(),
			method.getGearMultiplier());
	}

	/** The option to buy for a slot the player has nothing in. */
	private Option cheapFill(List<Option> options, long goalPrice)
	{
		Option best = null;
		Option cheapest = null;
		for (Option option : options)
		{
			final long price = items.price(option.listId);
			if (price <= 0 || !items.tradeable(option.listId))
			{
				continue;
			}
			if (best == null && price <= goalPrice / 100)
			{
				best = option;
			}
			if (cheapest == null || price < items.price(cheapest.listId))
			{
				cheapest = option;
			}
		}
		return best != null ? best : cheapest;
	}

	public Plan plan(RebuildData.Goal goal, RebuildData.Method method, Map<Integer, Long> owned,
					 long coins, DpsModel.Levels levels, Settings settings)
	{
		final Plan plan = new Plan();
		plan.setGoal(goal);
		plan.setMethod(method);
		plan.setCoins(coins);
		plan.setGoalPrice(items.price(goal.getBuyId()));
		plan.setAlreadyOwned(ownedForm(goal.getId(), owned) >= 0 || ownedForm(goal.getBuyId(), owned) >= 0);

		final Set<Integer> goalIds = new HashSet<>(Arrays.asList(goal.getId(), goal.getBuyId()));
		final ItemInfo.Stats goalStats = items.stats(goal.getId());
		final boolean twoHanded = goalStats == null || goalStats.isTwoHanded();

		// ---- Main setup -------------------------------------------------
		RebuildData.Role mainRole = null;
		for (RebuildData.Role role : method.getRoles())
		{
			if (role.isMain())
			{
				mainRole = role;
			}
		}

		final Map<String, List<Option>> mainOptions = new LinkedHashMap<>();
		final Map<String, Option> picks = new LinkedHashMap<>();
		final Map<String, Option> bestPossible = new LinkedHashMap<>();
		if (mainRole != null)
		{
			for (RebuildData.Slot slot : mainRole.getSlots())
			{
				if ("WEAPON".equals(slot.getSlot()) || ("SHIELD".equals(slot.getSlot()) && twoHanded))
				{
					continue;
				}
				final List<Option> options = options(slot, owned, goalIds);
				mainOptions.put(slot.getSlot(), options);

				Option top = null;
				Option first = null;
				for (Option option : options)
				{
					if (first == null && items.stats(option.listId) != null)
					{
						first = option;
					}
					if (top == null && option.ownedId >= 0)
					{
						top = option;
					}
				}
				picks.put(slot.getSlot(), top);
				bestPossible.put(slot.getSlot(), first);
			}
		}

		// List order is the wiki's, but the damage model decides what is worn.
		for (int pass = 0; pass < 2; pass++)
		{
			for (Map.Entry<String, List<Option>> entry : mainOptions.entrySet())
			{
				Option best = picks.get(entry.getKey());
				double bestScore = score(goal, method, picks, false, levels);
				for (Option option : entry.getValue())
				{
					if (option.ownedId < 0)
					{
						continue;
					}
					picks.put(entry.getKey(), option);
					final double s = score(goal, method, picks, false, levels);
					if (s > bestScore + 1e-9)
					{
						bestScore = s;
						best = option;
					}
				}
				picks.put(entry.getKey(), best);
			}
		}

		final boolean voidOwned = ownedForm(ELITE_VOID_TOP, owned) >= 0
			&& ownedForm(ELITE_VOID_ROBE, owned) >= 0
			&& ownedForm(VOID_GLOVES, owned) >= 0
			&& ownedForm(voidHelm(goal.getStyle()), owned) >= 0;
		boolean useVoid = voidOwned
			&& score(goal, method, picks, true, levels) > score(goal, method, picks, false, levels);

		// Gear the method needs and the player has none of.
		long buyCost = 0;
		if (mainRole != null)
		{
			for (Map.Entry<String, List<Option>> entry : mainOptions.entrySet())
			{
				if (picks.get(entry.getKey()) != null || (useVoid && VOID_SLOTS.contains(entry.getKey())))
				{
					continue;
				}
				final Option fill = cheapFill(entry.getValue(), plan.getGoalPrice());
				if (fill != null)
				{
					final long cost = items.price(fill.listId);
					plan.getBuys().add(new Plan.Buy(label(mainRole), entry.getKey(), fill.name, cost));
					buyCost += cost;
					picks.put(entry.getKey(), fill);
				}
			}
		}

		final double bestScore = score(goal, method, bestPossible, false, levels);
		final double ownedScore = score(goal, method, picks, useVoid, levels);

		// ---- Switches ---------------------------------------------------
		final Map<Integer, Long> keepQty = new HashMap<>();
		final Set<Integer> mainKept = new HashSet<>();
		final Set<Integer> ammoKept = new HashSet<>();

		/** A switch piece the player is holding on to. */
		final class SwitchKeep
		{
			RebuildData.Role role;
			String slot;
			Option pick;
			List<Option> options;
		}
		final List<SwitchKeep> switchKeeps = new ArrayList<>();

		for (RebuildData.Role role : method.getRoles())
		{
			if (role.isMain())
			{
				continue;
			}
			for (RebuildData.Slot slot : role.getSlots())
			{
				if ("SHIELD".equals(slot.getSlot()))
				{
					continue;
				}
				final List<Option> options = options(slot, owned, goalIds);
				Option top = null;
				for (Option option : options)
				{
					if (option.ownedId >= 0)
					{
						top = option;
						break;
					}
				}
				if (top == null)
				{
					if ("WEAPON".equals(slot.getSlot()))
					{
						final Option fill = cheapFill(options, plan.getGoalPrice());
						if (fill != null)
						{
							final long cost = items.price(fill.listId);
							plan.getBuys().add(new Plan.Buy(label(role), slot.getSlot(), fill.name, cost));
							buyCost += cost;
						}
					}
					continue;
				}
				final SwitchKeep keep = new SwitchKeep();
				keep.role = role;
				keep.slot = slot.getSlot();
				keep.pick = top;
				keep.options = options;
				switchKeeps.add(keep);
			}
		}

		plan.setBuyCost(buyCost);
		plan.setUsesVoid(useVoid);

		if (useVoid)
		{
			for (int id : new int[]{ELITE_VOID_TOP, ELITE_VOID_ROBE, VOID_GLOVES, voidHelm(goal.getStyle())})
			{
				keepQty.put(ownedForm(id, owned), 1L);
			}
		}
		for (Map.Entry<String, Option> entry : picks.entrySet())
		{
			final Option pick = entry.getValue();
			if (pick == null || pick.ownedId < 0 || (useVoid && VOID_SLOTS.contains(entry.getKey())))
			{
				continue;
			}
			keepQty.put(pick.ownedId, 1L);
			mainKept.add(pick.ownedId);
			if ("AMMO".equals(entry.getKey()))
			{
				ammoKept.add(pick.ownedId);
			}
		}
		for (SwitchKeep keep : switchKeeps)
		{
			keepQty.put(keep.pick.ownedId, 1L);
			if ("AMMO".equals(keep.slot))
			{
				ammoKept.add(keep.pick.ownedId);
			}
		}

		// ---- 1. Everything that is not kit ------------------------------
		final long need = Math.max(0, plan.getGoalPrice() + buyCost - coins);
		plan.setNeeded(need);

		final Map<Integer, Plan.Sale> saleById = new HashMap<>();
		final List<Plan.Sale> pool = new ArrayList<>();
		for (Map.Entry<Integer, Long> entry : owned.entrySet())
		{
			final int id = entry.getKey();
			final long qty = entry.getValue();
			if (id == COINS || id == PLATINUM_TOKEN || goalIds.contains(id) || qty <= 0
				|| !items.tradeable(id))
			{
				continue;
			}
			final long price = items.price(id);
			if (price <= 0)
			{
				continue;
			}

			final String name = items.name(id);
			long keep = keepQty.getOrDefault(id, 0L);
			if (ammoKept.contains(id) || (qty > 1 && isSupply(name)))
			{
				final long reserve = (settings.getSupplyReserve() + price - 1) / price;
				keep = Math.max(keep, Math.min(qty, reserve));
			}

			final long sell = qty - keep;
			if (sell <= 0 || sell * price < settings.getMinStackValue())
			{
				continue;
			}
			pool.add(new Plan.Sale(id, name, sell, keep, sell * net(price), true));
		}
		pool.sort(Comparator.comparingLong(Plan.Sale::getProceeds).reversed());

		long raised = 0;
		for (Plan.Sale sale : pool)
		{
			final Plan.Sale line = raised >= need
				? new Plan.Sale(sale.getItemId(), sale.getName(), sale.getQuantity(), sale.getKept(),
					sale.getProceeds(), false)
				: sale;
			if (line.isNeeded())
			{
				raised += line.getProceeds();
			}
			plan.getSales().add(line);
			saleById.put(line.getItemId(), line);
		}

		long kitSold = 0;

		// ---- 2. Expensive switches --------------------------------------
		if (raised < need)
		{
			final List<Object[]> moves = new ArrayList<>();
			for (SwitchKeep keep : switchKeeps)
			{
				final int soldId = keep.pick.ownedId;
				final long price = items.price(soldId);
				if (mainKept.contains(soldId) || !items.tradeable(soldId) || price < MIN_SWAP_VALUE)
				{
					continue;
				}

				Option replacement = null;
				long replacementCost = 0;
				for (Option option : keep.options)
				{
					if (option.rank > keep.pick.rank && option.ownedId >= 0 && option.ownedId != soldId)
					{
						replacement = option;
						// Using it means it is no longer sold with the rest.
						final Plan.Sale queued = saleById.get(option.ownedId);
						replacementCost = queued != null && queued.isNeeded()
							? net(items.price(option.ownedId)) : 0;
						break;
					}
				}
				if (replacement == null)
				{
					for (Option option : keep.options)
					{
						final long p = items.price(option.listId);
						if (option.rank > keep.pick.rank && items.tradeable(option.listId)
							&& p > 0 && p < price / 10
							&& (replacement == null || p < replacementCost))
						{
							replacement = option;
							replacementCost = p;
						}
					}
				}

				final long freed = net(price) - replacementCost;
				if (freed >= MIN_SWAP_VALUE)
				{
					moves.add(new Object[]{keep, replacement, freed, price});
				}
			}
			moves.sort((a, b) -> Long.compare((long) b[2], (long) a[2]));

			final Set<Integer> used = new HashSet<>();
			for (Object[] move : moves)
			{
				if (raised >= need)
				{
					break;
				}
				final SwitchKeep keep = (SwitchKeep) move[0];
				final Option replacement = (Option) move[1];
				if (!used.add(keep.pick.ownedId)
					|| (replacement != null && replacement.ownedId >= 0 && !used.add(replacement.ownedId)))
				{
					continue;
				}
				raised += (long) move[2];
				kitSold += (long) move[3];
				plan.getSwaps().add(new Plan.Swap(label(keep.role), items.name(keep.pick.ownedId),
					replacement == null ? "nothing in this slot" : replacement.name,
					replacement != null && replacement.ownedId >= 0, (long) move[2], 0));
				keepQty.remove(keep.pick.ownedId);
				if (replacement != null && replacement.ownedId >= 0)
				{
					keepQty.put(replacement.ownedId, 1L);
					plan.getSales().remove(saleById.remove(replacement.ownedId));
				}
				keep.pick = replacement;
			}
		}

		// ---- 3. The main setup, cheapest damage first --------------------
		double current = ownedScore;
		while (raised < need && ownedScore > 0 && mainRole != null)
		{
			String bestSlot = null;
			Option bestAlt = null;
			long bestFreed = 0;
			long bestGross = 0;
			double bestNew = 0;
			double bestRatio = 0;
			boolean bestIsVoid = false;

			for (Map.Entry<String, List<Option>> entry : mainOptions.entrySet())
			{
				final String slot = entry.getKey();
				final Option pick = picks.get(slot);
				if (pick == null || pick.ownedId < 0 || (useVoid && VOID_SLOTS.contains(slot)))
				{
					continue;
				}
				final long price = items.price(pick.ownedId);
				if (!items.tradeable(pick.ownedId) || price < MIN_SWAP_VALUE)
				{
					continue;
				}

				for (Option alt : entry.getValue())
				{
					if (alt == pick)
					{
						continue;
					}
					long cost;
					if (alt.ownedId >= 0)
					{
						final Plan.Sale queued = saleById.get(alt.ownedId);
						cost = queued != null && queued.isNeeded() ? net(items.price(alt.ownedId)) : 0;
					}
					else
					{
						cost = items.price(alt.listId);
						if (cost <= 0 || !items.tradeable(alt.listId) || cost > price / 5)
						{
							continue;
						}
					}
					final long freed = net(price) - cost;
					if (freed < MIN_SWAP_VALUE)
					{
						continue;
					}

					picks.put(slot, alt);
					final double s = score(goal, method, picks, useVoid, levels);
					picks.put(slot, pick);

					final double total = (ownedScore - s) / ownedScore * 100;
					if (total > settings.getMaxDpsLossPct())
					{
						continue;
					}
					final double step = Math.max(0.05, (current - s) / ownedScore * 100);
					final double ratio = freed / step;
					if (ratio > bestRatio)
					{
						bestRatio = ratio;
						bestSlot = slot;
						bestAlt = alt;
						bestFreed = freed;
						bestGross = price;
						bestNew = s;
						bestIsVoid = false;
					}
				}
			}

			// Elite void is free to anyone who already owns it.
			if (voidOwned && !useVoid)
			{
				long freed = 0;
				long gross = 0;
				for (String slot : VOID_SLOTS)
				{
					final Option pick = picks.get(slot);
					if (pick != null && pick.ownedId >= 0 && items.tradeable(pick.ownedId))
					{
						freed += net(items.price(pick.ownedId));
						gross += items.price(pick.ownedId);
					}
				}
				final double s = score(goal, method, picks, true, levels);
				final double total = (ownedScore - s) / ownedScore * 100;
				if (freed >= MIN_SWAP_VALUE && total <= settings.getMaxDpsLossPct())
				{
					final double step = Math.max(0.05, (current - s) / ownedScore * 100);
					if (freed / step > bestRatio)
					{
						bestRatio = freed / step;
						bestIsVoid = true;
						bestFreed = freed;
						bestGross = gross;
						bestNew = s;
					}
				}
			}

			if (bestRatio <= 0)
			{
				break;
			}

			final double loss = Math.max(0, (current - bestNew) / ownedScore * 100);
			if (bestIsVoid)
			{
				final List<String> sold = new ArrayList<>();
				for (String slot : VOID_SLOTS)
				{
					final Option pick = picks.get(slot);
					if (pick != null && pick.ownedId >= 0 && items.tradeable(pick.ownedId))
					{
						sold.add(items.name(pick.ownedId));
						keepQty.remove(pick.ownedId);
					}
				}
				useVoid = true;
				plan.setUsesVoid(true);
				plan.getSwaps().add(new Plan.Swap(label(mainRole), String.join(", ", sold),
					"Elite void set", true, bestFreed, loss));
			}
			else
			{
				final Option pick = picks.get(bestSlot);
				keepQty.remove(pick.ownedId);
				if (bestAlt.ownedId >= 0)
				{
					keepQty.put(bestAlt.ownedId, 1L);
					plan.getSales().remove(saleById.remove(bestAlt.ownedId));
				}
				plan.getSwaps().add(new Plan.Swap(label(mainRole), items.name(pick.ownedId),
					bestAlt.name, bestAlt.ownedId >= 0, bestFreed, loss));
				picks.put(bestSlot, bestAlt);
			}
			raised += bestFreed;
			kitSold += bestGross;
			current = bestNew;
		}

		// ---- Result -----------------------------------------------------
		if (mainRole != null)
		{
			if (useVoid)
			{
				plan.getKeeps().add(new Plan.Keep(label(mainRole), "SET", "Elite void set"));
			}
			for (Map.Entry<String, Option> entry : picks.entrySet())
			{
				final Option pick = entry.getValue();
				if (pick != null && pick.ownedId >= 0 && !(useVoid && VOID_SLOTS.contains(entry.getKey())))
				{
					plan.getKeeps().add(new Plan.Keep(label(mainRole), entry.getKey(), pick.name));
				}
			}
		}
		for (SwitchKeep keep : switchKeeps)
		{
			if (keep.pick != null && keep.pick.ownedId >= 0)
			{
				plan.getKeeps().add(new Plan.Keep(label(keep.role), keep.slot, keep.pick.name));
			}
		}

		plan.setRaised(raised);
		plan.setShortfall(Math.max(0, need - raised));
		plan.setKitSold(kitSold);
		plan.setDpsLossPct(ownedScore > 0 ? Math.max(0, (ownedScore - current) / ownedScore * 100) : 0);
		plan.setDpsVersusBest(bestScore > 0 ? Math.min(1, current / bestScore) : 0);
		plan.setEstGpPerHour((long) (method.getGpPerHour() * plan.getDpsVersusBest()));
		return plan;
	}
}
