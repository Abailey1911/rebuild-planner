/*
 * Copyright (c) 2026, Alexb
 * All rights reserved.
 * Licensed under BSD 2-Clause. See LICENSE.
 */
package com.rebuildplanner;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup(RebuildPlannerConfig.GROUP)
public interface RebuildPlannerConfig extends Config
{
	String GROUP = "rebuild-planner";

	@ConfigItem(
		keyName = "minStackValue",
		name = "Smallest stack to sell (gp)",
		description = "Bank stacks worth less than this in total are left out of the sell list",
		position = 1
	)
	default int minStackValue()
	{
		return 1_000_000;
	}

	@ConfigItem(
		keyName = "supplyReserve",
		name = "Supplies to keep (gp each)",
		description = "How much of each supply stack (potions, food, runes, ammo) to keep for the rebuild grind; the rest is offered for sale",
		position = 2
	)
	default int supplyReserve()
	{
		return 3_000_000;
	}

	@Range(min = 0, max = 50)
	@ConfigItem(
		keyName = "maxDpsLoss",
		name = "Most damage to give up (%)",
		description = "Gear from the megarare's own setup is only sold while the total damage lost stays under this",
		position = 3
	)
	default int maxDpsLoss()
	{
		return 15;
	}

	@Range(min = 0, max = 80)
	@ConfigItem(
		keyName = "maxSwitchLoss",
		name = "Most switch damage to give up (%)",
		description = "Raid switches only have to exist, so they can be sold down further than the main setup. This caps how far",
		position = 4
	)
	default int maxSwitchLoss()
	{
		return 40;
	}
}
