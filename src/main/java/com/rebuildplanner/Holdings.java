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
