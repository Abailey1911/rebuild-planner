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
import java.util.Collections;
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

		@Override
		public String toString()
		{
			return name;
		}
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

	private RebuildData()
	{
	}

	public static List<Goal> loadGoals(Gson gson)
	{
		final Document doc = read(gson, "methods.json", Document.class);
		return doc == null || doc.getGoals() == null ? Collections.emptyList() : doc.getGoals();
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
