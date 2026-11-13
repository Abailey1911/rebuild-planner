/*
 * Copyright (c) 2026, Alexb
 * All rights reserved.
 * Licensed under BSD 2-Clause. See LICENSE.
 */
package com.rebuildplanner;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A plan the player named and kept: what they are rebuilding to, how, and their own changes. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SavedPlan
{
	private String name;
	/** The megarare's name, as in the goal list. */
	private String goal;
	/** The method's name, or null for "best method for me". */
	private String method;
	private Overrides overrides;
}
