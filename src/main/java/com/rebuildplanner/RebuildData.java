/*
 * Copyright (c) 2026, Alexb
 * All rights reserved.
 * Licensed under BSD 2-Clause. See LICENSE.
 */
package com.rebuildplanner;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * The bundled rebuild goals: each megarare, the ways of making money with it,
 * and the gear each of those needs. Money rates and gear lists come from the
 * OSRS Wiki.
 */
public final class RebuildData
{
	@Data
	public static class Goal
	{
		private String name;
		/** The usable (charged) item, whose stats the damage model reads. */
		private int id;
		/** The form sold on the Grand Exchange. */
		private int buyId;
		private String style;
		/** Runes and the like for one full charge; never sold. */
		private List<Charge> charges;
		private List<Method> methods;
		/**
		 * {@link #MEGARARE} (null too) for a weapon planned around making money with it;
		 * {@link #UPGRADE} for a gear piece checked against each style's setup;
		 * {@link #JUNK} for anything else, paid for with junk alone.
		 */
		private String kind;
		/** For an upgrade: the equipment slot it goes in. */
		private String slot;
		/** For an upgrade: every piece bought, one per slot (a whole armour set has several). */
		private List<Piece> pieces;
		/** For an upgrade: the combat styles it is for (MELEE, RANGED, MAGIC); null for all. */
		private List<String> styles;
		/** True for the one goal the player found with the item search. */
		private boolean custom;

		/** The pieces bought; an upgrade with just a slot counts as one piece. */
		public List<Piece> pieceList()
		{
			if (pieces != null && !pieces.isEmpty())
			{
				return pieces;
			}
			return slot == null ? Collections.emptyList()
				: Collections.singletonList(new Piece(id, buyId, name, slot));
		}

		public boolean appliesTo(String style)
		{
			return styles == null || styles.isEmpty() || styles.contains(style);
		}

		public boolean isMegarare()
		{
			return kind == null || MEGARARE.equals(kind);
		}

		public boolean isUpgrade()
		{
			return UPGRADE.equals(kind);
		}

		public boolean isJunk()
		{
			return JUNK.equals(kind);
		}

		@Override
		public String toString()
		{
			return name;
		}
	}

	/** One piece of a gear upgrade: the id worn, the id bought, and its slot. */
	@Data
	@lombok.NoArgsConstructor
	@lombok.AllArgsConstructor
	public static class Piece
	{
		private int id;
		private int buyId;
		private String name;
		private String slot;
	}

	/** One place an item is used: a style's slot. */
	@lombok.Value
	public static class Use
	{
		String style;
		String slot;
		String name;
	}

	@Data
	public static class Charge
	{
		private int id;
		private long qty;
		private String name;
	}

	@Data
	public static class Method
	{
		private String name;
		private long gpPerHour;
		private String note;
		private Target target;
		/** How far Tumeken's shadow multiplies magic gear here: 4 in ToA, 3 elsewhere. */
		private int gearMultiplier;
		private List<Role> roles;

		@Override
		public String toString()
		{
			return name;
		}
	}

	@Data
	public static class Target
	{
		private String name;
		private int defenceLevel;
		private int magicLevel;
		private int dstab;
		private int dslash;
		private int dcrush;
		private int dmagic;
		private int drange;
	}

	@Data
	public static class Role
	{
		private String style;
		/** True for the setup the megarare is used in; false for a switch. */
		private boolean main;
		/** The boss a switch is used on; null means the method's own target. */
		private Target target;
		private List<Slot> slots;
	}

	@Data
	public static class Slot
	{
		private String slot;
		private List<String> items;
		private List<Integer> ids;
	}

	@Data
	private static class Document
	{
		private List<Goal> goals;
	}

	@Data
	private static class VariantDocument
	{
		private Map<String, List<Integer>> variants;
		private Map<String, List<Component>> components;
	}

	/** One ingredient of an item that cannot be bought whole, like the jaw in a Neitiznot faceguard. */
	@Data
	public static class Component
	{
		private int id;
		private int qty;
	}

	/** Item id to what it is made from, for items the Grand Exchange does not sell. */
	public static Map<String, List<Component>> loadComponents(Gson gson)
	{
		final VariantDocument doc = read(gson, "variants.json", VariantDocument.class);
		return doc == null || doc.getComponents() == null ? Collections.emptyMap() : doc.getComponents();
	}

	public static final String MEGARARE = "megarare";
	public static final String UPGRADE = "upgrade";
	public static final String JUNK = "junk";

	public static final int AVERNIC_TREADS = 31088;

	private static final String[] STYLES = {"MELEE", "RANGED", "MAGIC"};
	/** The boss each style's damage is checked against for an upgrade: one plain target per style. */
	private static final Map<String, String> CHECK_TARGETS = new LinkedHashMap<>();

	static
	{
		CHECK_TARGETS.put("MELEE", "Vardorvis");
		CHECK_TARGETS.put("RANGED", "Phantom Muspah");
		CHECK_TARGETS.put("MAGIC", "The Whisperer");
	}

	private RebuildData()
	{
	}

	/** The megarares from methods.json, followed by the gear upgrades built from their lists. */
	public static List<Goal> loadGoals(Gson gson)
	{
		final Document doc = read(gson, "methods.json", Document.class);
		if (doc == null || doc.getGoals() == null)
		{
			return Collections.emptyList();
		}
		final List<Goal> goals = new ArrayList<>(doc.getGoals());
		goals.add(upgrade(doc.getGoals(), "Avernic treads", AVERNIC_TREADS, "BOOTS",
			"No money-making method: the treads are a small upgrade to every style, so the plan checks "
				+ "the damage they add to your best gear in each, keeps that gear, and sells around it."));
		return goals;
	}

	/**
	 * A piece of gear rather than a weapon to make money with. It has one "method",
	 * holding a setup per combat style built from every list the megarares use for that
	 * style, so the planner can find the player's best gear in each and check the piece in it.
	 */
	static Goal upgrade(List<Goal> megarares, String name, int id, String slot, String note)
	{
		final Method method = new Method();
		method.setName("Light DPS check");
		method.setNote(note);
		method.setGearMultiplier(1);
		method.setRoles(new ArrayList<>());
		for (String style : STYLES)
		{
			final Map<String, Map<Integer, String>> slots = new LinkedHashMap<>();
			Target target = null;
			for (Goal goal : megarares)
			{
				for (Method m : goal.getMethods())
				{
					for (Role role : m.getRoles())
					{
						if (!style.equals(role.getStyle()))
						{
							continue;
						}
						if (target == null || m.getName().startsWith(CHECK_TARGETS.get(style)))
						{
							target = role.getTarget() != null ? role.getTarget() : m.getTarget();
						}
						for (Slot s : role.getSlots())
						{
							final Map<Integer, String> list = slots.computeIfAbsent(s.getSlot(), k -> new LinkedHashMap<>());
							for (int i = 0; i < s.getIds().size(); i++)
							{
								list.putIfAbsent(s.getIds().get(i), i < s.getItems().size() ? s.getItems().get(i) : null);
							}
						}
					}
				}
			}
			if (slots.isEmpty())
			{
				continue;
			}
			final Role role = new Role();
			role.setStyle(style);
			role.setMain(false);
			role.setTarget(target);
			role.setSlots(new ArrayList<>());
			for (Map.Entry<String, Map<Integer, String>> e : slots.entrySet())
			{
				final Slot s = new Slot();
				s.setSlot(e.getKey());
				s.setIds(new ArrayList<>(e.getValue().keySet()));
				s.setItems(new ArrayList<>(e.getValue().values()));
				role.getSlots().add(s);
			}
			method.getRoles().add(role);
			if (method.getTarget() == null)
			{
				method.setTarget(target);
			}
		}
		final Goal goal = new Goal();
		goal.setName(name);
		goal.setId(id);
		goal.setBuyId(id);
		goal.setStyle("ALL");
		goal.setKind(UPGRADE);
		goal.setSlot(slot);
		goal.setCharges(Collections.emptyList());
		goal.setMethods(Collections.singletonList(method));
		return goal;
	}

	/**
	 * Gear the player searched for: one piece or a whole armour set, for the styles it is
	 * used in. Checked like the treads; the pieces it beats are sold and the rest is kept.
	 */
	public static Goal gear(List<Goal> megarares, String name, List<Piece> pieces, List<String> styles)
	{
		final Piece first = pieces.get(0);
		final Goal goal = upgrade(megarares, name, first.getId(), first.getSlot(),
			"Checked against your best gear in " + (styles == null || styles.isEmpty() ? "each style"
				: styles.size() == 1 ? "its style" : "each style it is used in")
				+ ". The pieces it beats are sold; the rest of your gear is kept.");
		goal.setBuyId(first.getBuyId());
		goal.setPieces(new ArrayList<>(pieces));
		goal.setStyles(styles == null ? null : new ArrayList<>(styles));
		goal.setCustom(true);
		return goal;
	}

	/** Where each item in the megarares' gear lists is used (style and slot), by list id. */
	public static Map<Integer, List<Use>> gearIndex(List<Goal> megarares)
	{
		final Map<Integer, List<Use>> index = new LinkedHashMap<>();
		for (Goal goal : megarares)
		{
			if (!goal.isMegarare())
			{
				continue;
			}
			for (Method m : goal.getMethods())
			{
				for (Role role : m.getRoles())
				{
					for (Slot s : role.getSlots())
					{
						for (int i = 0; i < s.getIds().size(); i++)
						{
							final String item = i < s.getItems().size() ? s.getItems().get(i) : null;
							final Use use = new Use(role.getStyle(), s.getSlot(), item);
							final List<Use> uses = index.computeIfAbsent(s.getIds().get(i), k -> new ArrayList<>());
							if (!uses.contains(use))
							{
								uses.add(use);
							}
						}
					}
				}
			}
		}
		return index;
	}

	/** Any item at all, paid for by selling junk only: the player's gear is never touched. */
	public static Goal junk(int id, String name)
	{
		final Method method = new Method();
		method.setName("Sell junk only");
		method.setNote("Only items that are not gear from the planner's lists are sold: supplies above "
			+ "your reserve, drops, and anything else lying in the bank.");
		method.setGearMultiplier(1);
		method.setRoles(Collections.emptyList());
		final Goal goal = new Goal();
		goal.setName(name);
		goal.setId(id);
		goal.setBuyId(id);
		goal.setStyle("ALL");
		goal.setKind(JUNK);
		goal.setCustom(true);
		goal.setCharges(Collections.emptyList());
		goal.setMethods(Collections.singletonList(method));
		return goal;
	}

	/** Item id to the other ids that count as owning it. */
	public static Map<String, List<Integer>> loadVariants(Gson gson)
	{
		final VariantDocument doc = read(gson, "variants.json", VariantDocument.class);
		return doc == null || doc.getVariants() == null ? Collections.emptyMap() : doc.getVariants();
	}

	private static <T> T read(Gson gson, String resource, Class<T> type)
	{
		try (InputStream in = RebuildData.class.getResourceAsStream(resource))
		{
			if (in == null)
			{
				return null;
			}
			return gson.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), type);
		}
		catch (IOException e)
		{
			return null;
		}
	}
}
