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
 * <p>Every setup the method needs - the one the megarare is used in, and any
 * raid switches - is picked the way Gear Upgrade picks gear: the wiki table
 * decides what is eligible, and damage decides what is worn, so set bonuses,
 * two-handed weapons and ammunition all sort themselves out. A Bow of
 * faerdhinen pulls in the crystal set and leaves Masori spare; a Fang with a
 * defender can beat a two-handed halberd.
 *
 * <p>Then money is raised in this order:
 * <ol>
 *   <li>anything the setups do not use, keeping a working reserve of
 *       supplies;</li>
 *   <li>switches, sold down a price tier at a time. Each tier is rebuilt as a
 *       whole kit from what is left plus cheap buys, so a switch never ends up
 *       as an Oathplate chest over mixed hide legs;</li>
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

	/** A piece cheaper than this is not worth swapping out. */
	private static final long MIN_SWAP_VALUE = 500_000;

	/** Price tiers a switch is sold down through, most expensive first. */
	private static final long[] SWITCH_TIERS = {
		150_000_000, 80_000_000, 40_000_000, 20_000_000, 10_000_000, 5_000_000, 2_000_000,
	};

	/** Cheaper ammunition is used when it costs at most this share of damage. */
	private static final double AMMO_TOLERANCE = 0.03;

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
		/** Most damage a switch may give up, in percent. */
		double maxSwitchLossPct;
		/** Least damage the rebuilt setup must gain over today's, in percent, to be worth doing. */
		double minGainPct;
	}

	/** One entry in a slot's ranked list. */
	private static final class Option
	{
		final int listId;
		final int rank;
		final String name;
		/** The id actually held (the item or a cosmetic form of it), or -1. */
		final int ownedId;

		Option(int listId, int rank, String name, int ownedId)
		{
			this.listId = listId;
			this.rank = rank;
			this.name = name;
			this.ownedId = ownedId;
		}
	}

	/** One setup the method needs, and what is chosen for it. */
	private static final class Role
	{
		final RebuildData.Role data;
		final boolean main;
		final String label;
		final RebuildData.Target target;
		final Map<String, List<Option>> slots = new LinkedHashMap<>();
		/** For the main setup: the weapons the megarare would replace. */
		final List<Option> otherWeapons = new ArrayList<>();
		/** As the player owns it, before anything is sold. */
		Map<String, Option> start;
		/** As it stands now. */
		Map<String, Option> current;
		double startDps;

		Role(RebuildData.Role data, RebuildData.Method method)
		{
			this.data = data;
			this.main = data.isMain();
			final String style = data.getStyle().charAt(0) + data.getStyle().substring(1).toLowerCase(Locale.ROOT);
			this.label = main ? style : style + " switch";
			this.target = data.getTarget() != null ? data.getTarget() : method.getTarget();
		}
	}

	/** Who may wear what, at a given price tier. */
	private final class Rules
	{
		final long tier;
		final boolean allowBuy;
		/** The switch being sold down, so a slot can buy a fair replacement; null otherwise. */
		final Role role;

		Rules(long tier, boolean allowBuy)
		{
			this(tier, allowBuy, null);
		}

		Rules(long tier, boolean allowBuy, Role role)
		{
			this.tier = tier;
			this.allowBuy = allowBuy;
			this.role = role;
		}

		/**
		 * Most a replacement may cost in a slot. Where the piece the player
		 * owned is being sold, up to half its price - selling Oathplate legs
		 * and buying Bandos tassets still frees most of the money, and leaves
		 * a real pair of legs. Elsewhere, the tier itself.
		 */
		long buyCap(String slot)
		{
			if (role != null && slot != null && role.start != null)
			{
				final Option was = role.start.get(slot);
				if (was != null && was.ownedId >= 0 && items.tradeable(was.ownedId))
				{
					final long price = items.price(was.ownedId);
					if (price > tier)
					{
						return Math.max(tier, price / 2);
					}
				}
			}
			return tier;
		}

		long cost(Option option)
		{
			return cost(null, option);
		}

		/** -1 when the option may not be used; 0 when it is owned and kept; else its cost. */
		long cost(String slot, Option option)
		{
			if (option.ownedId >= 0 && !sold.contains(option.ownedId))
			{
				final long price = items.price(option.ownedId);
				final boolean protectedItem = excluded.contains(option.ownedId)
					|| mainKept.contains(option.ownedId) || !items.tradeable(option.ownedId)
					|| chosenIds.contains(option.listId);
				if (protectedItem || price <= tier)
				{
					return 0;
				}
			}
			final long price = items.buyPrice(option.listId);
			if (!allowBuy || goalIds.contains(option.listId) || price <= 0 || putUpForSale(option.listId))
			{
				return -1;
			}
			// A replacement the player picked is allowed whatever it costs.
			return price > 0 && (price <= buyCap(slot) || chosenIds.contains(option.listId)) ? price : -1;
		}
	}

	private final ItemInfo items;
	private final Map<String, List<Integer>> variants;

	// Per-plan state, reset by plan().
	private RebuildData.Goal goal;
	private RebuildData.Method method;
	private DpsModel.Levels levels;
	private Settings settings;
	private Map<Integer, Long> owned;
	private Set<Integer> goalIds;
	private Set<Integer> excluded;
	/** Replacements the player picked: sold item id to list item id. */
	private Map<Integer, Integer> replace = Collections.emptyMap();
	private Set<Integer> chosenIds = Collections.emptySet();
	/** Items the player added to the sell list themselves. */
	private Set<Integer> forceSell = Collections.emptySet();
	private final Set<Integer> sold = new HashSet<>();
	private final Set<Integer> mainKept = new HashSet<>();

	public Planner(ItemInfo items, Map<String, List<Integer>> variants)
	{
		this.items = items;
		this.variants = variants == null ? Collections.emptyMap() : variants;
	}

	public static long geTax(long price)
	{
		return price < 50 ? 0 : Math.min(5_000_000L, price / 50);
	}

	private static long net(long price)
	{
		return price - geTax(price);
	}

	static boolean isSupply(String name)
	{
		return name != null && SUPPLY.matcher(name.toLowerCase(Locale.ROOT)).find();
	}

	/** True for an item the player put up for sale, in any of its forms: never bought back. */
	private boolean putUpForSale(int itemId)
	{
		if (forceSell.isEmpty())
		{
			return false;
		}
		if (forceSell.contains(itemId))
		{
			return true;
		}
		for (int variant : variants.getOrDefault(String.valueOf(itemId), Collections.emptyList()))
		{
			if (forceSell.contains(variant))
			{
				return true;
			}
		}
		return false;
	}

	/** The form of an item the kit may use; one the player put up for sale does not count. */
	private int ownedForm(int itemId)
	{
		if (owned.getOrDefault(itemId, 0L) > 0 && !forceSell.contains(itemId))
		{
			return itemId;
		}
		for (int variant : variants.getOrDefault(String.valueOf(itemId), Collections.emptyList()))
		{
			if (owned.getOrDefault(variant, 0L) > 0 && !forceSell.contains(variant))
			{
				return variant;
			}
		}
		return -1;
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

	private long ammoReserve(int itemId)
	{
		final long price = Math.max(1, items.price(itemId));
		return Math.max(1, (settings.getSupplyReserve() + price - 1) / price);
	}

	private String lower(int itemId)
	{
		final String name = itemId > 0 ? items.name(itemId) : null;
		return name == null ? "" : name.toLowerCase(Locale.ROOT);
	}

	// ---- Scoring and choosing ----------------------------------------------

	private Map<String, Integer> worn(Role role, Map<String, Option> loadout, boolean useVoid)
	{
		final Map<String, Integer> worn = new HashMap<>();
		for (Map.Entry<String, Option> entry : loadout.entrySet())
		{
			if (entry.getValue() != null && !(useVoid && VOID_SLOTS.contains(entry.getKey())))
			{
				worn.put(entry.getKey(), entry.getValue().listId);
			}
		}
		if (role.main)
		{
			worn.put("WEAPON", goal.getId());
		}
		if (useVoid)
		{
			worn.put("HEAD", voidHelm(role.data.getStyle()));
			worn.put("BODY", ELITE_VOID_TOP);
			worn.put("LEGS", ELITE_VOID_ROBE);
			worn.put("GLOVES", VOID_GLOVES);
		}
		return worn;
	}

	private double dps(Role role, Map<String, Option> loadout, boolean useVoid)
	{
		return DpsModel.dps(items, role.data.getStyle(), worn(role, loadout, useVoid), useVoid, levels,
			role.target, method.getGearMultiplier());
	}

	private int weaponId(Role role, Map<String, Option> loadout)
	{
		if (role.main)
		{
			return goal.getId();
		}
		final Option weapon = loadout.get("WEAPON");
		return weapon == null ? -1 : weapon.listId;
	}

	private boolean twoHanded(Role role, Map<String, Option> loadout)
	{
		final int weapon = weaponId(role, loadout);
		final ItemInfo.Stats stats = weapon > 0 ? items.stats(weapon) : null;
		return stats != null && stats.isTwoHanded();
	}

	/**
	 * Void only does anything as a complete set, which the main setup handles as
	 * one decision. A lone void robe under a fighter torso is worse than useless.
	 */
	private boolean isVoidPiece(int itemId)
	{
		final String name = lower(itemId);
		return name.startsWith("void ") || name.startsWith("elite void") || name.contains("void knight");
	}

	private boolean isAmmunition(Option option)
	{
		return DpsModel.kindOf(lower(option.listId)) != DpsModel.Ammo.NONE;
	}

	/**
	 * The highest-damage setup for a role under the given rules: each weapon
	 * tried in turn, then every other slot settled around it.
	 */
	private Map<String, Option> choose(Role role, Rules rules, boolean useVoid)
	{
		return choose(role, rules, useVoid, null);
	}

	/** As above, but around a given weapon, when the switch's weapon has to be bought. */
	private Map<String, Option> choose(Role role, Rules rules, boolean useVoid, Option forcedWeapon)
	{
		final List<Option> weapons = new ArrayList<>();
		if (forcedWeapon != null)
		{
			weapons.add(forcedWeapon);
		}
		else if (role.main)
		{
			weapons.add(null);
		}
		else
		{
			for (Option option : role.slots.getOrDefault("WEAPON", Collections.emptyList()))
			{
				if (rules.cost("WEAPON", option) >= 0 && items.stats(option.listId) != null)
				{
					weapons.add(option);
				}
			}
		}

		Map<String, Option> best = null;
		double bestScore = -1;
		long bestCost = Long.MAX_VALUE;
		for (Option weapon : weapons)
		{
			final Map<String, Option> loadout = new LinkedHashMap<>();
			for (String slot : role.slots.keySet())
			{
				loadout.put(slot, null);
			}
			if (!role.main)
			{
				loadout.put("WEAPON", weapon);
			}
			final boolean twoHanded = twoHanded(role, loadout);
			final int weaponId = weaponId(role, loadout);

			for (int pass = 0; pass < 2; pass++)
			{
				for (Map.Entry<String, List<Option>> entry : role.slots.entrySet())
				{
					final String slot = entry.getKey();
					if ("WEAPON".equals(slot) || ("SHIELD".equals(slot) && twoHanded)
						|| (useVoid && VOID_SLOTS.contains(slot)))
					{
						continue;
					}
					Option pick = loadout.get(slot);
					double pickScore = dps(role, loadout, useVoid);
					long pickCost = pick == null ? 0 : rules.cost(slot, pick);
					// Where the piece the player had is being sold and they picked
					// what replaces it, nothing else is considered.
					final Option was = rules.role != null && rules.role.start != null
						? rules.role.start.get(slot) : null;
					final Integer chosen = was != null && was.ownedId >= 0 && rules.cost(slot, was) != 0
						? replace.get(was.ownedId) : null;
					for (Option option : entry.getValue())
					{
						final long cost = rules.cost(slot, option);
						if (cost < 0 || isVoidPiece(option.listId) || (chosen != null && option.listId != chosen)
							|| ("AMMO".equals(slot) && !DpsModel.ammoFits(items, weaponId, option.listId)))
						{
							continue;
						}
						loadout.put(slot, option);
						final double score = dps(role, loadout, useVoid);
						// Better damage wins; on a tie the cheaper item, and an
						// owned piece over an empty slot.
						// Better damage wins. On a tie the wiki's order stands, except
						// that an owned piece beats a bought one. An empty slot takes an
						// owned piece freely, and a bought one only where the piece the
						// player had is being sold - armour is more than its damage, so
						// sold Oathplate legs become Bandos tassets, not bare legs.
						final boolean tie = score > pickScore - 1e-9;
						final boolean wasFilled = rules.role != null && rules.role.start != null
							&& rules.role.start.get(slot) != null;
						if (score > pickScore + 1e-9
							|| (tie && pick == null && (cost == 0 || wasFilled))
							|| (tie && pick != null && cost == 0 && pickCost > 0))
						{
							pick = option;
							pickScore = score;
							pickCost = cost;
						}
					}
					loadout.put(slot, pick);
				}
			}
			cheaperAmmo(role, loadout, rules, useVoid);

			final double score = dps(role, loadout, useVoid);
			final long cost = cost(loadout, rules);
			if (score > bestScore + 1e-9 || (score > bestScore - 1e-9 && cost < bestCost))
			{
				best = loadout;
				bestScore = score;
				bestCost = cost;
			}
		}
		return best;
	}

	/**
	 * Ammunition is a consumable, so the best arrow is not worth it when a much
	 * cheaper one does nearly the same damage - Seeking dragon arrows against
	 * amethyst, say.
	 */
	private void cheaperAmmo(Role role, Map<String, Option> loadout, Rules rules, boolean useVoid)
	{
		final Option current = loadout.get("AMMO");
		if (current == null || !isAmmunition(current))
		{
			return;
		}
		final double best = dps(role, loadout, useVoid);
		final int weaponId = weaponId(role, loadout);
		Option cheapest = current;
		for (Option option : role.slots.getOrDefault("AMMO", Collections.emptyList()))
		{
			if (rules.cost("AMMO", option) < 0 || !isAmmunition(option)
				|| !DpsModel.ammoFits(items, weaponId, option.listId))
			{
				continue;
			}
			loadout.put("AMMO", option);
			if (dps(role, loadout, useVoid) >= best * (1 - AMMO_TOLERANCE)
				&& items.price(option.listId) < items.price(cheapest.listId))
			{
				cheapest = option;
			}
		}
		loadout.put("AMMO", cheapest);
	}

	/** What a loadout costs to buy under the rules; ammunition by the stack. */
	private long cost(Map<String, Option> loadout, Rules rules)
	{
		long total = 0;
		for (Map.Entry<String, Option> entry : loadout.entrySet())
		{
			final Option option = entry.getValue();
			if (option == null)
			{
				continue;
			}
			final long cost = Math.max(0, rules.cost(entry.getKey(), option));
			total += "AMMO".equals(entry.getKey()) && cost > 0 ? cost * ammoReserve(option.listId) : cost;
		}
		return total;
	}

	/** The cheapest sensible thing to buy for a slot the player has nothing in. */
	private Option cheapFill(String slot, List<Option> options, long goalPrice, int weaponId)
	{
		Option best = null;
		Option cheapest = null;
		for (Option option : options)
		{
			final long price = items.buyPrice(option.listId);
			if (price <= 0 || goalIds.contains(option.listId)
				|| isVoidPiece(option.listId) || putUpForSale(option.listId) || ("AMMO".equals(slot) && !DpsModel.ammoFits(items, weaponId, option.listId)))
			{
				continue;
			}
			if (best == null && price <= goalPrice / 100)
			{
				best = option;
			}
			if (cheapest == null || price < items.buyPrice(cheapest.listId))
			{
				cheapest = option;
			}
		}
		return best != null ? best : cheapest;
	}

	// ---- Planning ----------------------------------------------------------

	public Plan plan(RebuildData.Goal goal, RebuildData.Method method, Map<Integer, Long> owned,
					 long coins, DpsModel.Levels levels, Settings settings, Overrides overrides)
	{
		this.goal = goal;
		this.method = method;
		this.levels = levels;
		this.settings = settings;
		this.owned = owned;
		this.goalIds = new HashSet<>(Arrays.asList(goal.getId(), goal.getBuyId()));
		this.excluded = overrides == null ? Collections.emptySet() : overrides.getExcluded();
		final Map<Integer, Long> customQty = overrides == null
			? Collections.emptyMap() : overrides.getSellQuantity();
		this.replace = overrides == null || overrides.getReplacement() == null
			? Collections.emptyMap() : overrides.getReplacement();
		this.chosenIds = new HashSet<>(replace.values());
		this.forceSell = overrides == null || overrides.getForceSell() == null
			? Collections.emptySet() : overrides.getForceSell();
		sold.clear();
		mainKept.clear();

		final Plan plan = new Plan();
		plan.setGoal(goal);
		plan.setMethod(method);
		plan.setCoins(coins);
		plan.setGoalPrice(items.price(goal.getBuyId()));
		plan.setAlreadyOwned(ownedForm(goal.getId()) >= 0 || ownedForm(goal.getBuyId()) >= 0);

		// ---- Every setup, as the player owns it ---------------------------
		final List<Role> roles = new ArrayList<>();
		Role main = null;
		for (RebuildData.Role data : method.getRoles())
		{
			final Role role = new Role(data, method);
			for (RebuildData.Slot slot : data.getSlots())
			{
				if (role.main && "WEAPON".equals(slot.getSlot()))
				{
					for (int i = 0; i < slot.getIds().size(); i++)
					{
						final int id = slot.getIds().get(i);
						if (!goalIds.contains(id))
						{
							role.otherWeapons.add(new Option(id, i, i < slot.getItems().size()
								? slot.getItems().get(i) : items.name(id), ownedForm(id)));
						}
					}
					continue;
				}
				final List<Option> options = new ArrayList<>();
				for (int i = 0; i < slot.getIds().size(); i++)
				{
					final int id = slot.getIds().get(i);
					if (goalIds.contains(id))
					{
						continue;
					}
					final String name = i < slot.getItems().size() ? slot.getItems().get(i) : items.name(id);
					options.add(new Option(id, i, name, ownedForm(id)));
				}
				role.slots.put(slot.getSlot(), options);
			}
			if (role.main)
			{
				main = role;
			}
			else
			{
				roles.add(role);
			}
		}
		if (main == null)
		{
			return plan;
		}
		roles.add(0, main);

		final Rules ownedOnly = new Rules(Long.MAX_VALUE, false);
		long buyCost = 0;

		// The main setup.
		main.current = choose(main, ownedOnly, false);
		final boolean voidOwned = ownedForm(ELITE_VOID_TOP) >= 0 && ownedForm(ELITE_VOID_ROBE) >= 0
			&& ownedForm(VOID_GLOVES) >= 0 && ownedForm(voidHelm(main.data.getStyle())) >= 0;
		boolean useVoid = false;
		if (voidOwned)
		{
			final Map<String, Option> withVoid = choose(main, ownedOnly, true);
			if (dps(main, withVoid, true) > dps(main, main.current, false))
			{
				main.current = withVoid;
				useVoid = true;
			}
		}
		// Slots with nothing at all: buy something cheap to fill them.
		for (Map.Entry<String, List<Option>> entry : main.slots.entrySet())
		{
			final String slot = entry.getKey();
			if (main.current.get(slot) != null || (useVoid && VOID_SLOTS.contains(slot))
				|| ("SHIELD".equals(slot) && twoHanded(main, main.current)))
			{
				continue;
			}
			final Option fill = cheapFill(slot, entry.getValue(), plan.getGoalPrice(), goal.getId());
			if (fill != null)
			{
				final long qty = "AMMO".equals(slot) && isAmmunition(fill) ? ammoReserve(fill.listId) : 1;
				final long cost = items.buyPrice(fill.listId) * qty;
				plan.getBuys().add(new Plan.Buy(fill.listId, main.label, slot, fill.name, qty, cost));
				buyCost += cost;
				main.current.put(slot, new Option(fill.listId, fill.rank, fill.name, -1));
			}
		}
		main.start = new LinkedHashMap<>(main.current);
		main.startDps = dps(main, main.current, useVoid);
		for (Option option : main.current.values())
		{
			if (option != null && option.ownedId >= 0)
			{
				mainKept.add(option.ownedId);
			}
		}

		// The switches, around what the main setup keeps.
		for (Role role : roles)
		{
			if (role.main)
			{
				continue;
			}
			role.current = choose(role, ownedOnly, false);
			if (role.current == null)
			{
				// Owns no weapon for this switch at all: buy a cheap one.
				role.current = new LinkedHashMap<>();
				for (String slot : role.slots.keySet())
				{
					role.current.put(slot, null);
				}
				final Option fill = cheapFill("WEAPON", role.slots.getOrDefault("WEAPON", Collections.emptyList()),
					plan.getGoalPrice(), -1);
				if (fill != null)
				{
					final long cost = items.buyPrice(fill.listId);
					plan.getBuys().add(new Plan.Buy(fill.listId, role.label, "WEAPON", fill.name, 1, cost));
					buyCost += cost;
					final Option bought = new Option(fill.listId, fill.rank, fill.name, -1);
					final Map<String, Option> around = choose(role, ownedOnly, false, bought);
					if (around != null)
					{
						role.current = around;
					}
					role.current.put("WEAPON", bought);
				}
			}
			role.start = new LinkedHashMap<>(role.current);
			role.startDps = dps(role, role.current, false);
		}

		plan.setBuyCost(buyCost);
		plan.setUsesVoid(useVoid);
		final boolean startVoid = useVoid;

		// ---- What the kit holds on to --------------------------------------
		final Map<Integer, Long> keepQty = new HashMap<>();
		final Set<Integer> keptAmmo = new HashSet<>();
		for (Role role : roles)
		{
			for (Map.Entry<String, Option> entry : role.current.entrySet())
			{
				final Option option = entry.getValue();
				if (option == null || option.ownedId < 0
					|| (role.main && useVoid && VOID_SLOTS.contains(entry.getKey())))
				{
					continue;
				}
				keepQty.put(option.ownedId, 1L);
				if ("AMMO".equals(entry.getKey()))
				{
					keptAmmo.add(option.ownedId);
				}
			}
		}
		if (useVoid)
		{
			for (int id : new int[]{ELITE_VOID_TOP, ELITE_VOID_ROBE, VOID_GLOVES, voidHelm(main.data.getStyle())})
			{
				keepQty.put(ownedForm(id), 1L);
			}
		}

		// Enough runes for one full charge of the megarare stay, whatever else goes.
		final List<RebuildData.Charge> charges = goal.getCharges() == null
			? Collections.emptyList() : goal.getCharges();
		for (RebuildData.Charge charge : charges)
		{
			final long have = owned.getOrDefault(charge.getId(), 0L);
			keepQty.put(charge.getId(), Math.max(keepQty.getOrDefault(charge.getId(), 0L), charge.getQty()));
			if (have < charge.getQty())
			{
				final long cost = items.price(charge.getId()) * (charge.getQty() - have);
				plan.getBuys().add(new Plan.Buy(charge.getId(), "Charges", "CHARGE", charge.getName(),
					charge.getQty() - have, cost));
				buyCost += cost;
			}
		}
		plan.setBuyCost(buyCost);

		// ---- 1. Everything the kit does not use ----------------------------
		final long need = Math.max(0, plan.getGoalPrice() + buyCost - coins);
		plan.setNeeded(need);

		final List<Plan.Sale> pool = new ArrayList<>();
		for (Map.Entry<Integer, Long> entry : owned.entrySet())
		{
			final int id = entry.getKey();
			final long held = entry.getValue();
			if (id == COINS || id == PLATINUM_TOKEN || goalIds.contains(id) || held <= 0
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
			if (excluded.contains(id))
			{
				if (price * held >= settings.getMinStackValue() && !keepQty.containsKey(id))
				{
					plan.getExcluded().add(new Plan.Excluded(id, name));
				}
				continue;
			}

			final long kitKeep = keepQty.getOrDefault(id, 0L);
			final Long custom = customQty.get(id);
			final boolean forced = forceSell.contains(id);
			final long sell;
			if (custom != null)
			{
				sell = Math.max(0, Math.min(held - kitKeep, custom));
			}
			else if (forced)
			{
				// The player put this up themselves: all of it, however little it is worth.
				sell = held - kitKeep;
				if (sell <= 0)
				{
					continue;
				}
			}
			else
			{
				long keep = kitKeep;
				// Ammunition is only worth holding back if a weapon in the kit fires it:
				// ruby dragon bolts are dead weight in a Twisted bow rebuild.
				final boolean ammunition = DpsModel.kindOf(lower(id)) != DpsModel.Ammo.NONE;
				if (keptAmmo.contains(id) || (held > 1 && !ammunition && isSupply(name)))
				{
					keep = Math.max(keep, Math.min(held, ammoReserve(id)));
				}
				sell = held - keep;
				if (sell <= 0 || sell * price < settings.getMinStackValue())
				{
					continue;
				}
			}
			pool.add(new Plan.Sale(id, name, sell, held - sell, held, sell * net(price), true, custom != null || forced));
		}
		// Ordered by what the whole stack is worth, so changing an amount does not
		// move the row out from under the player's cursor.
		pool.sort(Comparator.comparingLong((Plan.Sale s) -> s.getHeld() * items.price(s.getItemId())).reversed());

		long raised = 0;
		for (Plan.Sale sale : pool)
		{
			final boolean needed = sale.isCustom() || raised < need;
			if (needed)
			{
				raised += sale.getProceeds();
			}
			plan.getSales().add(new Plan.Sale(sale.getItemId(), sale.getName(), sale.getQuantity(),
				sale.getKept(), sale.getHeld(), sale.getProceeds(), needed, sale.isCustom()));
		}

		// ---- 2. Switches, a price tier at a time ---------------------------
		final Map<Role, Integer> tier = new HashMap<>();
		final Map<Role, Long> roleFreed = new HashMap<>();
		final Map<Role, Map<Integer, Map<String, Option>>> tierCache = new HashMap<>();
		while (raised < need)
		{
			Role bestRole = null;
			int bestTier = -1;
			Map<String, Option> bestLoadout = null;
			long bestFreed = 0;
			double bestRatio = 0;

			for (Role role : roles)
			{
				if (role.main || role.startDps <= 0)
				{
					continue;
				}
				final long freedSoFar = roleFreed.getOrDefault(role, 0L);
				final double lossSoFar = loss(role, role.current);
				for (int t = tier.getOrDefault(role, -1) + 1; t < SWITCH_TIERS.length; t++)
				{
					final Rules rules = new Rules(SWITCH_TIERS[t], true, role);
					final Map<String, Option> loadout = tierCache
						.computeIfAbsent(role, k -> new HashMap<>())
						.computeIfAbsent(t, k -> choose(role, rules, false));
					if (loadout == null)
					{
						continue;
					}
					final double loss = loss(role, loadout);
					if (loss > settings.getMaxSwitchLossPct())
					{
						continue;
					}
					final long freed = freed(role, loadout, roles, rules);
					final long gain = freed - freedSoFar;
					if (gain < MIN_SWAP_VALUE)
					{
						continue;
					}
					final double ratio = gain / Math.max(0.25, loss - lossSoFar);
					if (ratio > bestRatio)
					{
						bestRatio = ratio;
						bestRole = role;
						bestTier = t;
						bestLoadout = loadout;
						bestFreed = freed;
					}
				}
			}
			if (bestRole == null)
			{
				break;
			}
			raised += bestFreed - roleFreed.getOrDefault(bestRole, 0L);
			roleFreed.put(bestRole, bestFreed);
			tier.put(bestRole, bestTier);
			bestRole.current = bestLoadout;
		}

		// Record what the switches changed.
		long kitSold = 0;
		for (Role role : roles)
		{
			if (role.main || !tier.containsKey(role))
			{
				continue;
			}
			kitSold += describe(plan, role, new Rules(SWITCH_TIERS[tier.get(role)], true, role), roles);
			plan.getRoleLoss().put(role.label, loss(role, role.current));
		}

		// ---- 3. The main setup, cheapest damage first -----------------------
		double current = main.startDps;
		final Rules buyAny = new Rules(Long.MAX_VALUE, true);
		while (raised < need && main.startDps > 0)
		{
			String bestSlot = null;
			Option bestAlt = null;
			long bestFreed = 0;
			long bestGross = 0;
			double bestNew = 0;
			double bestRatio = 0;
			boolean bestIsVoid = false;
			boolean bestAltOwned = false;

			for (Map.Entry<String, List<Option>> entry : main.slots.entrySet())
			{
				final String slot = entry.getKey();
				final Option pick = main.current.get(slot);
				if (pick == null || pick.ownedId < 0 || sold.contains(pick.ownedId)
					|| excluded.contains(pick.ownedId) || (useVoid && VOID_SLOTS.contains(slot)))
				{
					continue;
				}
				final long price = items.price(pick.ownedId);
				if (!items.tradeable(pick.ownedId) || price < MIN_SWAP_VALUE)
				{
					continue;
				}
				final Integer chosen = replace.get(pick.ownedId);
				for (Option alt : entry.getValue())
				{
					if (alt == pick || alt.listId == pick.ownedId || isVoidPiece(alt.listId)
						|| (chosen != null && alt.listId != chosen)
						|| ("AMMO".equals(slot) && !DpsModel.ammoFits(items, goal.getId(), alt.listId)))
					{
						continue;
					}
					// Only really owned if it is a different item that is not already sold.
					final boolean altOwned = alt.ownedId >= 0 && alt.ownedId != pick.ownedId
						&& !sold.contains(alt.ownedId);
					final long cost;
					if (altOwned)
					{
						cost = 0;
					}
					else
					{
						final long altPrice = items.buyPrice(alt.listId);
						if (altPrice <= 0 || buyAny.cost(alt) < 0 || (chosen == null && altPrice > price / 2))
						{
							continue;
						}
						cost = altPrice;
					}
					final long freed = net(price) - cost;
					if (chosen == null ? freed < MIN_SWAP_VALUE : freed <= 0)
					{
						continue;
					}

					main.current.put(slot, alt);
					final double s = dps(main, main.current, useVoid);
					main.current.put(slot, pick);

					final double total = (main.startDps - s) / main.startDps * 100;
					// The player's own pick stands even past the damage cap.
					if (chosen == null && total > settings.getMaxDpsLossPct())
					{
						continue;
					}
					final double ratio = freed / Math.max(0.05, (current - s) / main.startDps * 100);
					if (ratio > bestRatio)
					{
						bestRatio = ratio;
						bestSlot = slot;
						bestAlt = alt;
						bestFreed = freed;
						bestGross = price;
						bestNew = s;
						bestIsVoid = false;
						bestAltOwned = altOwned;
					}
				}
			}

			// Elite void costs nothing to anyone who already owns it.
			if (voidOwned && !useVoid)
			{
				long freed = 0;
				long gross = 0;
				for (String slot : VOID_SLOTS)
				{
					final Option pick = main.current.get(slot);
					if (pick != null && pick.ownedId >= 0 && items.tradeable(pick.ownedId)
						&& !excluded.contains(pick.ownedId))
					{
						freed += net(items.price(pick.ownedId));
						gross += items.price(pick.ownedId);
					}
				}
				final double s = dps(main, main.current, true);
				final double total = (main.startDps - s) / main.startDps * 100;
				if (freed >= MIN_SWAP_VALUE && total <= settings.getMaxDpsLossPct())
				{
					final double ratio = freed / Math.max(0.05, (current - s) / main.startDps * 100);
					if (ratio > bestRatio)
					{
						bestRatio = ratio;
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

			if (bestIsVoid)
			{
				for (String slot : VOID_SLOTS)
				{
					final Option pick = main.current.get(slot);
					if (pick != null && pick.ownedId >= 0 && items.tradeable(pick.ownedId)
						&& !excluded.contains(pick.ownedId))
					{
						sold.add(pick.ownedId);
						final int voidPiece = "HEAD".equals(slot) ? voidHelm(main.data.getStyle())
							: "BODY".equals(slot) ? ELITE_VOID_TOP : "LEGS".equals(slot) ? ELITE_VOID_ROBE : VOID_GLOVES;
						plan.getSwaps().add(new Plan.Swap(pick.ownedId, ownedForm(voidPiece) >= 0 ? ownedForm(voidPiece) : voidPiece,
							main.label, items.name(pick.ownedId), items.name(voidPiece), true,
							net(items.price(pick.ownedId)), Collections.emptyList(), false));
					}
				}
				useVoid = true;
				plan.setUsesVoid(true);
			}
			else
			{
				final Option pick = main.current.get(bestSlot);
				sold.add(pick.ownedId);
				plan.getSwaps().add(new Plan.Swap(pick.ownedId, bestAltOwned ? bestAlt.ownedId : bestAlt.listId,
					main.label, items.name(pick.ownedId), bestAlt.name, bestAltOwned, bestFreed,
					choices(main, bestSlot, pick.ownedId, goal.getId()), replace.containsKey(pick.ownedId)));
				// A bought replacement is a new item, not the one just sold.
				main.current.put(bestSlot, bestAltOwned ? bestAlt
					: new Option(bestAlt.listId, bestAlt.rank, bestAlt.name, -1));
			}
			raised += bestFreed;
			kitSold += bestGross;
			current = bestNew;
		}
		if (current < main.startDps - 1e-9)
		{
			plan.getRoleLoss().put(main.label, (main.startDps - current) / main.startDps * 100);
		}

		// ---- Result ---------------------------------------------------------
		for (Role role : roles)
		{
			if (role.main && useVoid)
			{
				for (int id : new int[]{voidHelm(role.data.getStyle()), ELITE_VOID_TOP, ELITE_VOID_ROBE, VOID_GLOVES})
				{
					final int held = ownedForm(id);
					plan.getKeeps().add(new Plan.Keep(held >= 0 ? held : id, role.label, "VOID", items.name(id), 1, false));
				}
			}
			for (Map.Entry<String, Option> entry : role.current.entrySet())
			{
				final Option option = entry.getValue();
				if (option == null || (role.main && useVoid && VOID_SLOTS.contains(entry.getKey())))
				{
					continue;
				}
				// The kit as it ends up: pieces held on to, plus the ones the swaps
				// say to buy, so each setup reads as one complete loadout.
				final boolean held = option.ownedId >= 0 && !sold.contains(option.ownedId);
				plan.getKeeps().add(new Plan.Keep(held ? option.ownedId : option.listId, role.label,
					entry.getKey(), option.name, 1, !held));
			}
		}

		for (RebuildData.Charge charge : charges)
		{
			plan.getKeeps().add(new Plan.Keep(charge.getId(), "Charges", "CHARGE", charge.getName(), charge.getQty(), false));
		}

		// Is it worth it at all? Compare the rebuilt setup with what the player
		// does today: their best owned weapon in their current, unsold gear.
		// Selling half a setup to afford the megarare can leave it barely ahead.
		final double rebuilt = dps(main, main.current, useVoid);
		double today = 0;
		String todayWeapon = null;
		for (Option weapon : main.otherWeapons)
		{
			if (weapon.ownedId < 0 || items.stats(weapon.listId) == null)
			{
				continue;
			}
			final Map<String, Integer> worn = worn(main, main.start, startVoid);
			worn.put("WEAPON", weapon.listId);
			if (!DpsModel.ammoFits(items, weapon.listId, worn.getOrDefault("AMMO", -1)))
			{
				worn.remove("AMMO");
			}
			final double score = DpsModel.dps(items, main.data.getStyle(), worn, startVoid, levels, main.target,
				method.getGearMultiplier());
			if (score > today)
			{
				today = score;
				todayWeapon = weapon.name;
			}
		}
		if (todayWeapon != null && today > 0)
		{
			plan.setTodayWeapon(todayWeapon);
			plan.setGainPct((rebuilt - today) / today * 100);
			plan.setWorthIt(plan.getGainPct() >= settings.getMinGainPct());
		}

		// What else the player could put up: anything of value the plan leaves alone.
		final Set<Integer> selling = new HashSet<>(sold);
		for (Plan.Sale sale : plan.getSales())
		{
			if (sale.isNeeded())
			{
				selling.add(sale.getItemId());
			}
		}
		for (Map.Entry<Integer, Long> entry : owned.entrySet())
		{
			final int id = entry.getKey();
			final long value = items.price(id) * entry.getValue();
			if (id == COINS || id == PLATINUM_TOKEN || goalIds.contains(id) || selling.contains(id)
				|| excluded.contains(id) || !items.tradeable(id) || value < settings.getMinStackValue())
			{
				continue;
			}
			plan.getAddable().add(new Plan.Choice(id, id, items.name(id), true, value));
		}
		plan.getAddable().sort(Comparator.comparingLong(Plan.Choice::getPrice).reversed());

		final double bestDps = dps(main, bestPossible(main), false);
		plan.setRaised(raised);
		plan.setShortfall(Math.max(0, need - raised));
		plan.setKitSold(kitSold);
		plan.setDpsLossPct(main.startDps > 0 ? Math.max(0, (main.startDps - current) / main.startDps * 100) : 0);
		plan.setDpsVersusBest(bestDps > 0 ? Math.min(1, current / bestDps) : 0);
		plan.setEstGpPerHour((long) (method.getGpPerHour() * plan.getDpsVersusBest()));
		return plan;
	}

	/** Damage a switch loses with this loadout, in percent of what it started with. */
	private double loss(Role role, Map<String, Option> loadout)
	{
		return role.startDps <= 0 ? 0
			: Math.max(0, (role.startDps - dps(role, loadout, false)) / role.startDps * 100);
	}

	/** Owned pieces of the starting switch that a new loadout no longer uses and can be sold. */
	private List<Option> soldBy(Role role, Map<String, Option> loadout, List<Role> roles, Rules rules)
	{
		final Set<Integer> stillUsed = new HashSet<>(mainKept);
		for (Map.Entry<String, Option> entry : loadout.entrySet())
		{
			final Option option = entry.getValue();
			if (option != null && option.ownedId >= 0 && rules.cost(entry.getKey(), option) == 0)
			{
				stillUsed.add(option.ownedId);
			}
		}
		// A piece another switch still wears is not this switch's to sell.
		for (Role other : roles)
		{
			if (other != role && other.current != null)
			{
				for (Option option : other.current.values())
				{
					if (option != null && option.ownedId >= 0)
					{
						stillUsed.add(option.ownedId);
					}
				}
			}
		}

		final List<Option> out = new ArrayList<>();
		final Set<Integer> seen = new HashSet<>();
		for (Option option : role.start.values())
		{
			if (option == null || option.ownedId < 0 || stillUsed.contains(option.ownedId)
				|| excluded.contains(option.ownedId) || !items.tradeable(option.ownedId)
				|| items.price(option.ownedId) <= rules.tier || !seen.add(option.ownedId))
			{
				continue;
			}
			out.add(option);
		}
		return out;
	}

	private long freed(Role role, Map<String, Option> loadout, List<Role> roles, Rules rules)
	{
		long freed = 0;
		for (Option option : soldBy(role, loadout, roles, rules))
		{
			freed += net(items.price(option.ownedId));
		}
		return freed - cost(loadout, rules);
	}

	/** Turns a switch's tier change into plan lines; returns the gross value sold. */
	private long describe(Plan plan, Role role, Rules rules, List<Role> roles)
	{
		long gross = 0;
		final Set<String> replacedSlots = new HashSet<>();
		for (Option gone : soldBy(role, role.current, roles, rules))
		{
			// Name the replacement worn in the same slot, if any.
			String slot = null;
			for (Map.Entry<String, Option> entry : role.start.entrySet())
			{
				if (entry.getValue() == gone)
				{
					slot = entry.getKey();
				}
			}
			final Option use = slot == null ? null : role.current.get(slot);
			final boolean useOwned = use != null && rules.cost(slot, use) == 0;
			long cost = 0;
			if (use != null && !useOwned)
			{
				cost = Math.max(0, rules.cost(slot, use)) * ("AMMO".equals(slot) ? ammoReserve(use.listId) : 1);
				replacedSlots.add(slot);
			}
			sold.add(gone.ownedId);
			gross += items.price(gone.ownedId);
			plan.getSwaps().add(new Plan.Swap(gone.ownedId,
				use == null ? -1 : (useOwned ? use.ownedId : use.listId), role.label,
				items.name(gone.ownedId), use == null ? "nothing" : use.name, useOwned,
				net(items.price(gone.ownedId)) - cost,
				slot == null ? Collections.emptyList() : choices(role, slot, gone.ownedId, weaponId(role, role.current)),
				replace.containsKey(gone.ownedId)));
		}
		// Buys that do not replace a sold piece.
		for (Map.Entry<String, Option> entry : role.current.entrySet())
		{
			final Option use = entry.getValue();
			if (use == null || replacedSlots.contains(entry.getKey()) || rules.cost(entry.getKey(), use) <= 0)
			{
				continue;
			}
			final long qty = "AMMO".equals(entry.getKey()) && isAmmunition(use) ? ammoReserve(use.listId) : 1;
			plan.getBuys().add(new Plan.Buy(use.listId, role.label, entry.getKey(), use.name, qty,
				rules.cost(entry.getKey(), use) * qty));
		}
		return gross;
	}

	/**
	 * What a sold kit piece could be replaced with, in the wiki's order: pieces
	 * the player holds, and cheaper ones they could buy.
	 */
	private List<Plan.Choice> choices(Role role, String slot, int soldId, int weaponId)
	{
		final List<Plan.Choice> out = new ArrayList<>();
		final long soldPrice = items.price(soldId);
		for (Option option : role.slots.getOrDefault(slot, Collections.emptyList()))
		{
			if (option.listId == soldId || option.ownedId == soldId || goalIds.contains(option.listId)
				|| isVoidPiece(option.listId)
				|| ("AMMO".equals(slot) && !DpsModel.ammoFits(items, weaponId, option.listId)))
			{
				continue;
			}
			final boolean held = option.ownedId >= 0
				&& (!sold.contains(option.ownedId) || chosenIds.contains(option.listId));
			final long price = items.buyPrice(option.listId);
			if (!held && (price <= 0 || price >= soldPrice
				|| putUpForSale(option.listId)))
			{
				continue;
			}
			out.add(new Plan.Choice(option.listId, held ? option.ownedId : option.listId, option.name, held,
				held ? 0 : price));
		}
		return out;
	}

	/** The top of every list, regardless of ownership: the damage ceiling. */
	private Map<String, Option> bestPossible(Role role)
	{
		final Map<String, Option> loadout = new LinkedHashMap<>();
		final boolean twoHanded = twoHanded(role, loadout);
		for (Map.Entry<String, List<Option>> entry : role.slots.entrySet())
		{
			if ("SHIELD".equals(entry.getKey()) && twoHanded)
			{
				continue;
			}
			for (Option option : entry.getValue())
			{
				if (items.stats(option.listId) != null
					&& (!"AMMO".equals(entry.getKey()) || DpsModel.ammoFits(items, goal.getId(), option.listId)))
				{
					loadout.put(entry.getKey(), option);
					break;
				}
			}
		}
		return loadout;
	}
}
