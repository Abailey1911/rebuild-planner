/*
 * Copyright (c) 2026, Alexb
 * All rights reserved.
 * Licensed under BSD 2-Clause. See LICENSE.
 */
package com.rebuildplanner;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ItemComposition;
import net.runelite.api.Skill;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemEquipmentStats;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStats;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import net.runelite.http.api.item.ItemPrice;

@Slf4j
@PluginDescriptor(
	name = "Gear Upgrade: Rebuild Planner",
	description = "Pick a megarare and see what to sell from your bank to afford it, while keeping the gear you need to earn the money back",
	tags = {"rebuild", "megarare", "bank", "sell", "twisted bow", "shadow", "scythe", "money"}
)
public class RebuildPlannerPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ItemManager itemManager;

	@Inject
	private RebuildPlannerConfig config;

	@Inject
	private Holdings holdings;

	@Inject
	private Gson gson;

	@Inject
	private ConfigManager configManager;

	/** RuneLite's background thread; planning runs here so it never holds up the game. */
	@Inject
	private ScheduledExecutorService executor;

	/** The player's own ✕s and amounts; saved so they survive a restart. */
	private Overrides overrides = new Overrides();
	private static final String OVERRIDES_KEY = "overrides";
	private Map<String, List<RebuildData.Component>> components = java.util.Collections.emptyMap();

	private List<RebuildData.Goal> goals;
	private Planner planner;
	/** Item facts copied on the client thread for the background planner. */
	private ItemFacts facts;
	/** Every item the bundled data can ask about, loaded into {@link #facts} up front. */
	private Set<Integer> dataItems = new HashSet<>();
	/** What the last plan was built from; an identical replan is skipped. */
	private Map<Integer, Long> lastOwned;
	private DpsModel.Levels lastLevels;
	private Planner.Settings lastSettings;
	private RebuildData.Goal lastGoal;
	/** Bumped for every plan requested, so a stale background result is dropped. */
	private volatile long planSequence;
	/** Ticks since potion storage was last read. */
	private int potionAge = Integer.MAX_VALUE;
	/** Bank changes re-read potion storage at most this often (about 6s). */
	private static final int POTION_READ_TICKS = 10;
	private RebuildPanel panel;
	private NavigationButton navButton;

	/** Set when holdings change; the plan is rebuilt once on the next tick. */
	private boolean dirty;
	/** Set when potion storage may have changed; it is re-read on the next tick. */
	private boolean potionsDirty;
	/** A replan is owed; it runs once the holdings have stopped changing. */
	private boolean pending;
	private int quietTicks;
	private int waitedTicks;
	/** Ticks with no change before replanning (about 1.2s). */
	private static final int QUIET_TICKS = 2;
	/** Replan anyway after this many ticks of constant change (about 30s). */
	private static final int MAX_WAIT_TICKS = 50;

	@Override
	protected void startUp()
	{
		goals = new java.util.concurrent.CopyOnWriteArrayList<>(RebuildData.loadGoals(gson));
		variants = RebuildData.loadVariants(gson);
		components = RebuildData.loadComponents(gson);
		facts = new ItemFacts(new ClientItemInfo(), components);
		planner = new Planner(facts, variants);
		dataItems = dataItems(goals, variants, components);
		planner.setKnownGear(dataItems);
		gearIndex = RebuildData.gearIndex(goals);
		final RebuildData.Goal custom = loadCustomGoal();
		if (custom != null)
		{
			goals.add(custom);
		}

		overrides = loadOverrides();
		saved = loadSaved();
		activePlan = configManager.getConfiguration(RebuildPlannerConfig.GROUP, ACTIVE_KEY);
		panel = new RebuildPanel(goals, itemManager, new PanelActions());
		final SavedPlan active = find(activePlan);
		if (active == null)
		{
			activePlan = null;
		}
		else
		{
			panel.select(active.getGoal(), active.getMethod());
		}
		pushPlans();

		navButton = NavigationButton.builder()
			.tooltip("Gear Upgrade: Rebuild Planner")
			.icon(icon())
			// The sidebar sorts by priority, then tooltip. Sharing Gear Upgrade's
			// priority and starting with its name keeps this directly beneath it.
			.priority(6)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		if (client.getGameState() == GameState.LOGGED_IN)
		{
			clientThread.invokeLater(this::readAll);
		}
	}

	@Override
	protected void shutDown()
	{
		clientToolbar.removeNavigation(navButton);
		navButton = null;
		panel = null;
		planSequence++;
		lastOwned = null;
		holdings.clear();
	}

	private static Set<Integer> dataItems(List<RebuildData.Goal> goals, Map<String, List<Integer>> variants,
		Map<String, List<RebuildData.Component>> components)
	{
		final Set<Integer> ids = new HashSet<>();
		ids.add(Planner.COINS);
		ids.add(Planner.PLATINUM_TOKEN);
		for (RebuildData.Goal goal : goals)
		{
			ids.add(goal.getId());
			ids.add(goal.getBuyId());
			for (RebuildData.Method method : goal.getMethods())
			{
				for (RebuildData.Role role : method.getRoles())
				{
					for (RebuildData.Slot slot : role.getSlots())
					{
						ids.addAll(slot.getIds());
					}
				}
			}
		}
		for (Map.Entry<String, List<Integer>> e : variants.entrySet())
		{
			addId(ids, e.getKey());
			ids.addAll(e.getValue());
		}
		for (Map.Entry<String, List<RebuildData.Component>> e : components.entrySet())
		{
			addId(ids, e.getKey());
			for (RebuildData.Component part : e.getValue())
			{
				ids.add(part.getId());
			}
		}
		return ids;
	}

	private static void addId(Set<Integer> ids, String key)
	{
		try
		{
			ids.add(Integer.parseInt(key));
		}
		catch (NumberFormatException ignored)
		{
			// Not an item id; nothing to load.
		}
	}

	private static BufferedImage icon()
	{
		return ImageUtil.resizeImage(
			ImageUtil.loadImageResource(RebuildPlannerPlugin.class, "icon.png"), 16, 16);
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			clientThread.invokeLater(this::readAll);
		}
		else if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			holdings.clear();
			dirty = true;
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		final int id = event.getContainerId();
		if (id == InventoryID.BANK || id == InventoryID.INV || id == InventoryID.WORN)
		{
			holdings.refresh(id);
			dirty = true;
			// Reading potion storage runs a few hundred client scripts. Depositing and
			// withdrawing change the bank constantly, so read it at most every few
			// seconds; the potion storage varps flag real changes on their own.
			if (id == InventoryID.BANK && potionAge >= POTION_READ_TICKS)
			{
				potionsDirty = true;
			}
		}
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		// Reading potion storage runs client scripts, which cannot be started from
		// inside an event a script fired. Flag it and read on the next tick.
		if (holdings.isBankSeen() && Holdings.isPotionStoreVarp(event.getVarpId()))
		{
			potionsDirty = true;
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (RebuildPlannerConfig.GROUP.equals(event.getGroup()))
		{
			dirty = true;
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (potionAge < Integer.MAX_VALUE)
		{
			potionAge++;
		}
		if (potionsDirty)
		{
			potionsDirty = false;
			potionAge = 0;
			try
			{
				holdings.refreshPotionStore();
			}
			catch (RuntimeException | AssertionError e)
			{
				log.debug("Could not read potion storage", e);
			}
			dirty = true;
		}
		// Making potions or cooking changes the inventory every tick or two. Replanning
		// on each change redrew the panel constantly, so wait for the changes to stop.
		if (dirty)
		{
			dirty = false;
			pending = true;
			quietTicks = 0;
		}
		else if (pending)
		{
			quietTicks++;
		}
		if (pending && (quietTicks >= QUIET_TICKS || ++waitedTicks >= MAX_WAIT_TICKS))
		{
			pending = false;
			waitedTicks = 0;
			recompute(true);
		}
	}

	private void readAll()
	{
		holdings.refresh(InventoryID.BANK);
		holdings.refresh(InventoryID.INV);
		holdings.refresh(InventoryID.WORN);
		recompute();
	}

	/** Called from the panel when the goal or method selection changes. */
	private void requestPlan()
	{
		clientThread.invokeLater(() -> recompute(false));
	}

	private void recompute()
	{
		recompute(false);
	}

	/**
	 * @param passive true when the game changed something on its own; the panel then
	 *                leaves the screen alone unless the plan actually came out different
	 */
	private void recompute(boolean passive)
	{
		final RebuildPanel target = panel;
		if (target == null || goals == null || goals.isEmpty())
		{
			return;
		}
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			lastOwned = null;
			SwingUtilities.invokeLater(target::showLoggedOut);
			return;
		}

		final RebuildData.Goal goal = target.selectedGoal();
		if (goal == null)
		{
			return;
		}

		final Map<Integer, Long> owned = holdings.totals();
		final DpsModel.Levels levels = new DpsModel.Levels(
			client.getRealSkillLevel(Skill.ATTACK), client.getRealSkillLevel(Skill.STRENGTH),
			client.getRealSkillLevel(Skill.RANGED), client.getRealSkillLevel(Skill.MAGIC));
		final Planner.Settings settings = new Planner.Settings(
			config.minStackValue(), config.supplyReserve(), config.maxDpsLoss(), config.maxSwitchLoss(),
			config.minGain());
		// Swapping gear moves items between the inventory and worn slots, and opening
		// the bank re-reads it, without changing what the player owns. Planning again
		// would only give the same answer, so don't.
		if (passive && goal == lastGoal && owned.equals(lastOwned) && levels.equals(lastLevels)
			&& settings.equals(lastSettings))
		{
			return;
		}
		lastGoal = goal;
		lastOwned = owned;
		lastLevels = levels;
		lastSettings = settings;

		// Everything the planner reads from the client is copied here, on the client
		// thread; the planning itself runs in the background.
		facts.load(dataItems);
		facts.load(owned.keySet());
		facts.load(java.util.Arrays.asList(goal.getId(), goal.getBuyId()));
		for (RebuildData.Piece piece : goal.pieceList())
		{
			facts.load(java.util.Arrays.asList(piece.getId(), piece.getBuyId()));
		}
		final long coins = owned.getOrDefault(Planner.COINS, 0L)
			+ owned.getOrDefault(Planner.PLATINUM_TOKEN, 0L) * 1000L;
		final Overrides choices = copy(overrides);
		final boolean bankSeen = holdings.isBankSeen();
		final boolean changed = !choices.isEmpty();
		final long sequence = ++planSequence;

		executor.execute(() ->
		{
			if (sequence != planSequence)
			{
				return;
			}
			final List<Plan> plans = new ArrayList<>();
			synchronized (planner)
			{
				for (RebuildData.Method method : goal.getMethods())
				{
					try
					{
						plans.add(planner.plan(goal, method, owned, coins, levels, settings, choices));
					}
					catch (RuntimeException e)
					{
						log.warn("Could not plan {} via {}", goal.getName(), method.getName(), e);
					}
				}
			}
			final Set<Integer> missed = facts.takeMissed();
			if (sequence != planSequence)
			{
				return;
			}
			SwingUtilities.invokeLater(() -> target.showPlans(goal, plans, bankSeen, changed, passive));
			if (!missed.isEmpty())
			{
				// Something outside the bundled data and holdings came up: read it and plan again.
				clientThread.invokeLater(() ->
				{
					facts.load(missed);
					lastOwned = null;
					recompute(true);
				});
			}
		});
	}

	// ---- Any other item --------------------------------------------------------

	private static final String CUSTOM_KEY = "customItem";
	private static final int MAX_HITS = 12;
	private static final java.util.regex.Pattern TRADE_FORM = java.util.regex.Pattern.compile(
		"\\s*\\((damaged|inactive|uncharged|empty|broken|u|deadman)\\)$", java.util.regex.Pattern.CASE_INSENSITIVE);
	private static final List<String> ARMOUR = java.util.Arrays.asList("HEAD", "BODY", "LEGS");

	/** Where each piece of gear in the bundled lists is used, by list id. */
	private Map<Integer, List<RebuildData.Use>> gearIndex = java.util.Collections.emptyMap();
	/** Item id to the other ids that count as owning it (charged, cosmetic and other forms). */
	private Map<String, List<Integer>> variants = java.util.Collections.emptyMap();
	/** Whole armour sets offered in the last search, by the name shown. */
	private final Map<String, List<RebuildData.Piece>> searchSets = new java.util.concurrent.ConcurrentHashMap<>();

	/** What a saved gear goal is stored as. */
	@lombok.Data
	private static final class SavedGear
	{
		private String name;
		private List<String> styles;
		private List<RebuildData.Piece> pieces;
	}

	private List<RebuildData.Goal> megarares()
	{
		final List<RebuildData.Goal> out = new ArrayList<>();
		for (RebuildData.Goal goal : goals)
		{
			if (goal.isMegarare())
			{
				out.add(goal);
			}
		}
		return out;
	}

	/** The last item searched for and picked, so it and plans saved for it survive a restart. */
	private RebuildData.Goal loadCustomGoal()
	{
		final String saved = configManager.getConfiguration(RebuildPlannerConfig.GROUP, CUSTOM_KEY);
		if (saved != null && saved.startsWith("{"))
		{
			try
			{
				final SavedGear gear = gson.fromJson(saved, SavedGear.class);
				if (gear != null && gear.getName() != null && gear.getPieces() != null && !gear.getPieces().isEmpty())
				{
					return RebuildData.gear(megarares(), gear.getName(), gear.getPieces(), gear.getStyles());
				}
			}
			catch (RuntimeException e)
			{
				log.debug("Discarding an unreadable saved item", e);
			}
			return null;
		}
		final int bar = saved == null ? -1 : saved.indexOf('|');
		if (bar <= 0)
		{
			return null;
		}
		try
		{
			return RebuildData.junk(Integer.parseInt(saved.substring(0, bar)), saved.substring(bar + 1));
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	/**
	 * The gear-list id an item counts as: itself, the form it is a variant of (a Bow of
	 * faerdhinen (inactive) is the bow), or the list item of the same name once "(damaged)"
	 * and the like are dropped (Torva platebody (damaged) is the platebody). -1 if none.
	 */
	private int listId(int itemId, String name)
	{
		if (gearIndex.containsKey(itemId))
		{
			return itemId;
		}
		for (Map.Entry<String, List<Integer>> e : variants.entrySet())
		{
			if (e.getValue().contains(itemId))
			{
				try
				{
					final int id = Integer.parseInt(e.getKey());
					if (gearIndex.containsKey(id))
					{
						return id;
					}
				}
				catch (NumberFormatException ignored)
				{
					// not an item id
				}
			}
		}
		final String plain = TRADE_FORM.matcher(name).replaceAll("").trim();
		for (Map.Entry<Integer, List<RebuildData.Use>> e : gearIndex.entrySet())
		{
			for (RebuildData.Use use : e.getValue())
			{
				if (use.getName() != null && use.getName().equalsIgnoreCase(plain))
				{
					return e.getKey();
				}
			}
		}
		return -1;
	}

	/** The form of a list item sold on the Grand Exchange, or -1. Client thread only. */
	private int tradeForm(int listId, String name)
	{
		if (itemManager.getItemComposition(listId).isGeTradeable() && itemManager.getItemPrice(listId) > 0)
		{
			return listId;
		}
		for (int id : variants.getOrDefault(String.valueOf(listId), java.util.Collections.emptyList()))
		{
			if (itemManager.getItemComposition(id).isGeTradeable() && itemManager.getItemPrice(id) > 0)
			{
				return id;
			}
		}
		final String lower = name.toLowerCase(java.util.Locale.ROOT);
		for (ItemPrice item : itemManager.search(name))
		{
			final String other = item.getName().toLowerCase(java.util.Locale.ROOT);
			if ((other.equals(lower) || other.startsWith(lower + " (")) && itemManager.getItemPrice(item.getId()) > 0)
			{
				return item.getId();
			}
		}
		return -1;
	}

	/** One piece for a searched item that is gear from the lists, or null. Client thread only. */
	private RebuildData.Piece piece(int itemId, String name)
	{
		final int id = listId(itemId, name);
		if (id < 0)
		{
			return null;
		}
		final RebuildData.Use use = gearIndex.get(id).get(0);
		final String worn = use.getName() != null ? use.getName() : TRADE_FORM.matcher(name).replaceAll("").trim();
		return new RebuildData.Piece(id, itemId, worn, use.getSlot());
	}

	private List<String> styles(List<RebuildData.Piece> pieces)
	{
		final List<String> out = new ArrayList<>();
		for (RebuildData.Piece piece : pieces)
		{
			for (RebuildData.Use use : gearIndex.getOrDefault(piece.getId(), java.util.Collections.emptyList()))
			{
				if (use.getSlot().equals(piece.getSlot()) && !out.contains(use.getStyle()))
				{
					out.add(use.getStyle());
				}
			}
		}
		return out;
	}

	/** "Torva", "Masori ... (f)", "Blood moon": what the pieces of one armour set share. */
	private static String family(String name)
	{
		final String[] words = name.split(" ");
		String key = words.length > 1 && words[1].equalsIgnoreCase("moon") ? words[0] + " " + words[1] : words[0];
		if (name.endsWith("(f)"))
		{
			key += " (f)";
		}
		return key.toLowerCase(java.util.Locale.ROOT);
	}

	/** The head, body and legs of the set a piece belongs to, as list pieces; empty if it has no set. */
	private List<RebuildData.Piece> setOf(RebuildData.Piece piece)
	{
		if (!ARMOUR.contains(piece.getSlot()))
		{
			return java.util.Collections.emptyList();
		}
		final String key = family(piece.getName());
		final Set<String> pieceStyles = new HashSet<>(styles(java.util.Collections.singletonList(piece)));
		final Map<String, RebuildData.Piece> bySlot = new java.util.LinkedHashMap<>();
		for (String slot : ARMOUR)
		{
			for (Map.Entry<Integer, List<RebuildData.Use>> e : gearIndex.entrySet())
			{
				for (RebuildData.Use use : e.getValue())
				{
					if (slot.equals(use.getSlot()) && use.getName() != null && pieceStyles.contains(use.getStyle())
						&& family(use.getName()).equals(key) && !bySlot.containsKey(slot))
					{
						bySlot.put(slot, new RebuildData.Piece(e.getKey(), -1, use.getName(), slot));
					}
				}
			}
		}
		return bySlot.size() < 2 ? java.util.Collections.emptyList() : new ArrayList<>(bySlot.values());
	}

	private void search(String query)
	{
		final RebuildPanel target = panel;
		if (target == null)
		{
			return;
		}
		clientThread.invokeLater(() ->
		{
			final String lower = query.toLowerCase(java.util.Locale.ROOT);
			final List<Plan.Choice> hits = new ArrayList<>();
			for (ItemPrice item : itemManager.search(query))
			{
				final long price = itemManager.getItemPrice(item.getId());
				if (price > 0)
				{
					hits.add(new Plan.Choice(item.getId(), item.getId(), item.getName(), false, price));
				}
			}
			// An exact name first, then names starting with what was typed, then the dearest.
			hits.sort(java.util.Comparator
				.comparing((Plan.Choice c) -> !c.getName().equalsIgnoreCase(query))
				.thenComparing(c -> !c.getName().toLowerCase(java.util.Locale.ROOT).startsWith(lower))
				.thenComparing(Plan.Choice::getPrice, java.util.Comparator.reverseOrder()));
			final List<Plan.Choice> top = new ArrayList<>(hits.subList(0, Math.min(MAX_HITS, hits.size())));

			// Armour from the lists also offers its whole set, e.g. "Torva set" for a Torva platebody.
			final List<Plan.Choice> sets = new ArrayList<>();
			for (Plan.Choice hit : top)
			{
				final RebuildData.Piece piece = piece(hit.getId(), hit.getName());
				final List<RebuildData.Piece> set = piece == null ? java.util.Collections.emptyList() : setOf(piece);
				if (set.isEmpty())
				{
					continue;
				}
				final String words = family(piece.getName());
				final String name = Character.toUpperCase(words.charAt(0)) + words.substring(1).replace(" (f)", "")
					+ " set" + (words.endsWith("(f)") ? " (f)" : "");
				if (sets.stream().anyMatch(c -> c.getName().equals(name)))
				{
					continue;
				}
				long price = 0;
				final List<RebuildData.Piece> priced = new ArrayList<>();
				for (RebuildData.Piece p : set)
				{
					final int buy = tradeForm(p.getId(), p.getName());
					price += buy > 0 ? itemManager.getItemPrice(buy) : 0;
					priced.add(new RebuildData.Piece(p.getId(), buy > 0 ? buy : p.getId(), p.getName(), p.getSlot()));
				}
				searchSets.put(name, priced);
				sets.add(new Plan.Choice(hit.getId(), hit.getId(), name, false, price));
			}
			top.addAll(0, sets);
			SwingUtilities.invokeLater(() -> target.showSearchResults(query, top));
		});
	}

	/**
	 * Plans for a searched-for item. Gear from the lists (one piece or a whole set) is
	 * checked against the player's setups, and what it beats is sold; anything else is
	 * paid for with junk alone.
	 */
	private void pickItem(int itemId, String name)
	{
		final RebuildPanel target = panel;
		if (target == null)
		{
			return;
		}
		clientThread.invokeLater(() ->
		{
			List<RebuildData.Piece> pieces = searchSets.get(name);
			if (pieces == null)
			{
				final RebuildData.Piece piece = piece(itemId, name);
				pieces = piece == null ? null : java.util.Collections.singletonList(piece);
			}
			final RebuildData.Goal goal;
			final String stored;
			if (pieces != null)
			{
				final List<String> styles = styles(pieces);
				goal = RebuildData.gear(megarares(), name, pieces, styles);
				final SavedGear gear = new SavedGear();
				gear.setName(name);
				gear.setStyles(styles);
				gear.setPieces(pieces);
				stored = gson.toJson(gear);
				final List<Integer> ids = new ArrayList<>();
				for (RebuildData.Piece p : pieces)
				{
					ids.add(p.getId());
					ids.add(p.getBuyId());
				}
				facts.load(ids);
			}
			else
			{
				goal = RebuildData.junk(itemId, name);
				stored = itemId + "|" + name;
			}
			SwingUtilities.invokeLater(() ->
			{
				goals.removeIf(RebuildData.Goal::isCustom);
				goals.add(goal);
				configManager.setConfiguration(RebuildPlannerConfig.GROUP, CUSTOM_KEY, stored);
				target.setCustomGoal(goal, true);
			});
		});
	}

	// ---- Saved plans ---------------------------------------------------------

	private static final String SAVED_KEY = "savedPlans";
	private static final String ACTIVE_KEY = "activePlan";
	private List<SavedPlan> saved = new ArrayList<>();
	/** Name of the saved plan being worked on, or null for a new one. */
	private String activePlan;

	private List<SavedPlan> loadSaved()
	{
		final String json = configManager.getConfiguration(RebuildPlannerConfig.GROUP, SAVED_KEY);
		final List<SavedPlan> out = new ArrayList<>();
		if (json == null || json.isEmpty())
		{
			return out;
		}
		try
		{
			final SavedPlan[] loaded = gson.fromJson(json, SavedPlan[].class);
			if (loaded != null)
			{
				for (SavedPlan plan : loaded)
				{
					if (plan != null && plan.getName() != null && plan.getGoal() != null)
					{
						plan.setOverrides(filled(plan.getOverrides()));
						out.add(plan);
					}
				}
			}
		}
		catch (RuntimeException e)
		{
			log.debug("Discarding unreadable saved rebuild plans", e);
		}
		return out;
	}

	private SavedPlan find(String name)
	{
		if (name != null)
		{
			for (SavedPlan plan : saved)
			{
				if (name.equals(plan.getName()))
				{
					return plan;
				}
			}
		}
		return null;
	}

	/** Writes the saved plans and which one is open, and refreshes the panel's list. */
	private void persistSaved()
	{
		if (saved.isEmpty())
		{
			configManager.unsetConfiguration(RebuildPlannerConfig.GROUP, SAVED_KEY);
		}
		else
		{
			configManager.setConfiguration(RebuildPlannerConfig.GROUP, SAVED_KEY, gson.toJson(saved));
		}
		if (activePlan == null)
		{
			configManager.unsetConfiguration(RebuildPlannerConfig.GROUP, ACTIVE_KEY);
		}
		else
		{
			configManager.setConfiguration(RebuildPlannerConfig.GROUP, ACTIVE_KEY, activePlan);
		}
		pushPlans();
	}

	private void pushPlans()
	{
		final RebuildPanel target = panel;
		if (target == null)
		{
			return;
		}
		final List<String> names = new ArrayList<>();
		for (SavedPlan plan : saved)
		{
			names.add(plan.getName());
		}
		target.setSavedPlans(names, activePlan);
	}

	/** Never-null collections, whatever an older save left out. */
	private static Overrides filled(Overrides loaded)
	{
		if (loaded == null)
		{
			return new Overrides();
		}
		if (loaded.getExcluded() == null)
		{
			loaded.setExcluded(new java.util.HashSet<>());
		}
		if (loaded.getSellQuantity() == null)
		{
			loaded.setSellQuantity(new java.util.HashMap<>());
		}
		if (loaded.getForceSell() == null)
		{
			loaded.setForceSell(new java.util.HashSet<>());
		}
		if (loaded.getReplacement() == null)
		{
			loaded.setReplacement(new java.util.HashMap<>());
		}
		return loaded;
	}

	private Overrides copy(Overrides source)
	{
		return filled(gson.fromJson(gson.toJson(source), Overrides.class));
	}

	private Overrides loadOverrides()
	{
		final String json = configManager.getConfiguration(RebuildPlannerConfig.GROUP, OVERRIDES_KEY);
		if (json == null || json.isEmpty())
		{
			return new Overrides();
		}
		try
		{
			return filled(gson.fromJson(json, Overrides.class));
		}
		catch (RuntimeException e)
		{
			log.debug("Discarding unreadable rebuild overrides", e);
			return new Overrides();
		}
	}

	/** Saves and replans. Runs on the Swing thread; planning moves to the client thread. */
	private void overridesChanged()
	{
		if (overrides.isEmpty())
		{
			configManager.unsetConfiguration(RebuildPlannerConfig.GROUP, OVERRIDES_KEY);
		}
		else
		{
			configManager.setConfiguration(RebuildPlannerConfig.GROUP, OVERRIDES_KEY, gson.toJson(overrides));
		}
		requestPlan();
	}

	/** The panel's buttons and fields. */
	private final class PanelActions implements RebuildPanel.Actions
	{
		@Override
		public void changed()
		{
			// A different megarare is a fresh start: choices made for the last one
			// would silently skew this plan. It is no longer the saved plan either.
			if (activePlan != null)
			{
				activePlan = null;
				persistSaved();
			}
			if (!overrides.isEmpty())
			{
				overrides = new Overrides();
				overridesChanged();
				return;
			}
			requestPlan();
		}

		@Override
		public void exclude(int itemId)
		{
			// Crossing out something the player added themselves just takes it back off the list.
			if (!overrides.getForceSell().remove(itemId))
			{
				overrides.getExcluded().add(itemId);
			}
			overrides.getSellQuantity().remove(itemId);
			overridesChanged();
		}

		@Override
		public void restore(int itemId)
		{
			overrides.getExcluded().remove(itemId);
			overridesChanged();
		}

		@Override
		public void setQuantity(int itemId, long quantity)
		{
			overrides.getSellQuantity().put(itemId, quantity);
			overridesChanged();
		}

		@Override
		public void clearQuantity(int itemId)
		{
			overrides.getSellQuantity().remove(itemId);
			overridesChanged();
		}

		@Override
		public void savePlan(String name, String goal, String method)
		{
			final SavedPlan existing = find(name);
			final SavedPlan plan = new SavedPlan(name, goal, method, copy(overrides));
			if (existing == null)
			{
				saved.add(plan);
			}
			else
			{
				saved.set(saved.indexOf(existing), plan);
			}
			activePlan = name;
			persistSaved();
		}

		@Override
		public void loadPlan(String name)
		{
			final SavedPlan plan = find(name);
			final RebuildPanel target = panel;
			if (plan == null || target == null)
			{
				return;
			}
			activePlan = name;
			overrides = copy(plan.getOverrides());
			target.select(plan.getGoal(), plan.getMethod());
			persistSaved();
			overridesChanged();
		}

		@Override
		public void newPlan()
		{
			activePlan = null;
			overrides = new Overrides();
			persistSaved();
			overridesChanged();
		}

		@Override
		public void deletePlan(String name)
		{
			final SavedPlan plan = find(name);
			if (plan == null)
			{
				return;
			}
			saved.remove(plan);
			if (name.equals(activePlan))
			{
				activePlan = null;
			}
			persistSaved();
		}

		@Override
		public void addSale(int itemId)
		{
			overrides.getExcluded().remove(itemId);
			overrides.getForceSell().add(itemId);
			overridesChanged();
		}

		@Override
		public void setReplacement(int soldId, int useId)
		{
			overrides.getReplacement().put(soldId, useId);
			overridesChanged();
		}

		@Override
		public void clearReplacement(int soldId)
		{
			overrides.getReplacement().remove(soldId);
			overridesChanged();
		}

		@Override
		public void resetAll()
		{
			overrides = new Overrides();
			overridesChanged();
		}

		@Override
		public void search(String query)
		{
			RebuildPlannerPlugin.this.search(query);
		}

		@Override
		public void pickItem(int itemId, String name)
		{
			RebuildPlannerPlugin.this.pickItem(itemId, name);
		}
	}

	@Provides
	RebuildPlannerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(RebuildPlannerConfig.class);
	}

	/** Item facts straight from the client; only ever called on the client thread. */
	private final class ClientItemInfo implements ItemInfo
	{
		@Override
		public String name(int itemId)
		{
			final ItemComposition composition = itemManager.getItemComposition(itemId);
			return composition.getName();
		}

		@Override
		public long price(int itemId)
		{
			return itemId <= 0 ? 0 : itemManager.getItemPrice(itemId);
		}

		@Override
		public boolean tradeable(int itemId)
		{
			return itemManager.getItemComposition(itemId).isGeTradeable();
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
			final ItemStats stats = itemManager.getItemStats(itemId);
			final ItemEquipmentStats e = stats == null ? null : stats.getEquipment();
			if (e == null)
			{
				return null;
			}
			return new Stats(e.getAstab(), e.getAslash(), e.getAcrush(), e.getAmagic(), e.getArange(),
				e.getStr(), e.getRstr(), e.getMdmg(), e.getAspeed(), e.isTwoHanded());
		}
	}
}
