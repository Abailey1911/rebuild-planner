/*
 * Copyright (c) 2026, Alexb
 * All rights reserved.
 * Licensed under BSD 2-Clause. See LICENSE.
 */
package com.rebuildplanner;

import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.game.ItemManager;

/**
 * Everything the player holds, with quantities, across the bank, inventory and
 * worn equipment. The bank is only readable while it is open, so its contents
 * are remembered from the last time it was seen this session.
 */
@Singleton
public class Holdings
{
	private final Client client;
	private final ItemManager itemManager;

	/** Per container, so re-reading one replaces it rather than adding to it. */
	private final Map<Integer, Map<Integer, Long>> byContainer = new HashMap<>();

	@Getter
	private boolean bankSeen;

	@Inject
	public Holdings(Client client, ItemManager itemManager)
	{
		this.client = client;
		this.itemManager = itemManager;
	}

	/** Must run on the client thread. */
	public void refresh(int containerId)
	{
		final ItemContainer container = client.getItemContainer(containerId);
		if (container == null)
		{
			return;
		}
		if (containerId == InventoryID.BANK)
		{
			bankSeen = true;
			refreshPotionStore();
		}

		final Map<Integer, Long> items = new HashMap<>();
		for (Item item : container.getItems())
		{
			final int id = item.getId();
			if (id <= 0 || item.getQuantity() <= 0)
			{
				continue;
			}
			// A bank placeholder canonicalises to the real item it stands for.
			if (itemManager.getItemComposition(id).getPlaceholderTemplateId() > -1)
			{
				continue;
			}
			items.merge(itemManager.canonicalize(id), (long) item.getQuantity(), Long::sum);
		}
		byContainer.put(containerId, items);
	}

	/** Key for potion storage, which is not an item container. */
	private static final int POTION_STORE = -2;

	/** The two enums listing every potion the bank's potion storage can hold. */
	private static final int[] POTION_STORE_ENUMS = {4826, 4829};
	/** Client scripts returning, for one potion, the doses stored and the dose size it withdraws as. */
	private static final int SCRIPT_POTION_DOSES = 3750;
	private static final int SCRIPT_POTION_WITHDRAW_DOSES = 4818;

	/** Varps that change when potion storage does. */
	public static boolean isPotionStoreVarp(int varpId)
	{
		return varpId >= 4272 && varpId <= 4277;
	}

	/**
	 * Reads the bank's potion storage. Potions there are not in the bank
	 * container at all - they are counted in doses on the server - so without
	 * this a bank full of prayer potions looks empty. Mirrors how RuneLite's own
	 * bank tags plugin reads it. Must run on the client thread.
	 */
	public void refreshPotionStore()
	{
		final Map<Integer, Long> potions = new HashMap<>();
		for (int listEnum : POTION_STORE_ENUMS)
		{
			final EnumComposition list = client.getEnum(listEnum);
			if (list == null)
			{
				continue;
			}
			for (int potionEnumId : list.getIntVals())
			{
				final EnumComposition potion = client.getEnum(potionEnumId);
				client.runScript(SCRIPT_POTION_DOSES, potionEnumId);
				final int doses = client.getIntStack()[0];
				client.runScript(SCRIPT_POTION_WITHDRAW_DOSES, potionEnumId);
				final int perPotion = client.getIntStack()[0];
				if (potion == null || doses <= 0 || perPotion <= 0)
				{
					continue;
				}
				final int itemId = potion.getIntValue(perPotion);
				final long count = doses / perPotion;
				if (itemId > 0 && count > 0)
				{
					potions.merge(itemManager.canonicalize(itemId), count, Long::sum);
				}
			}
		}
		byContainer.put(POTION_STORE, potions);
	}

	/** Canonical item id to total quantity held. */
	public Map<Integer, Long> totals()
	{
		final Map<Integer, Long> totals = new HashMap<>();
		for (Map<Integer, Long> items : byContainer.values())
		{
			items.forEach((id, qty) -> totals.merge(id, qty, Long::sum));
		}
		return totals;
	}

	public void clear()
	{
		byContainer.clear();
		bankSeen = false;
	}
}
