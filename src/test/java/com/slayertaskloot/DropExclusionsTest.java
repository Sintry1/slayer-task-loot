package com.slayertaskloot;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DropExclusionsTest
{
	@Test
	public void globalExclusionAppliesToEveryTask()
	{
		final String global = DropExclusions.addGlobal("Coins", "Fire rune");
		assertTrue(DropExclusions.isExcluded(global, "", "Abyssal demons", "fire RUNE"));
		assertTrue(DropExclusions.isExcluded(global, "", "Gargoyles", "Fire rune"));
		assertFalse(DropExclusions.isExcluded(global, "", "Gargoyles", "Air rune"));
	}

	@Test
	public void taskExclusionOnlyAppliesToNamedTask()
	{
		final String perTask = DropExclusions.addForTask(
			"Gargoyles = Coins", "Abyssal demons", "Fire rune");
		assertTrue(DropExclusions.isExcluded("", perTask, "Abyssal demons", "Fire rune"));
		assertFalse(DropExclusions.isExcluded("", perTask, "Gargoyles", "Fire rune"));
	}

	@Test
	public void addingSameExclusionIsIdempotent()
	{
		final String once = DropExclusions.addForTask("", "Dust devils", "Fire rune");
		final String twice = DropExclusions.addForTask(once, "dust  devils", "fire RUNE");
		assertTrue(once.equals(twice));
	}

	@Test
	public void exclusionsCanBeRemovedWithoutDisturbingOtherEntries()
	{
		final String global = DropExclusions.removeGlobal("Coins; Hydra bones; Fire rune", "hydra BONES");
		assertFalse(DropExclusions.isExcluded(global, "", "Hydras", "Hydra bones"));
		assertTrue(DropExclusions.isExcluded(global, "", "Hydras", "Coins"));

		final String perTask = DropExclusions.removeForTask(
			"Hydras = Hydra bones; Gargoyles = Coins; Hydras = Coins", "hydras", "HYDRA bones");
		assertFalse(DropExclusions.isExcluded("", perTask, "Hydras", "Hydra bones"));
		assertTrue(DropExclusions.isExcluded("", perTask, "Hydras", "Coins"));
		assertTrue(DropExclusions.isExcluded("", perTask, "Gargoyles", "Coins"));
	}

	@Test
	public void taskEntriesOnlyExposeTheSelectedTask()
	{
		final String perTask = "Hydras = Hydra bones; Gargoyles = Coins; Hydras = Coins";
		assertTrue(DropExclusions.entriesForTask(perTask, "Hydras").size() == 2);
		assertTrue(DropExclusions.entriesForTask(perTask, "Hydras").contains("Hydra bones"));
		assertFalse(DropExclusions.entriesForTask(perTask, "Hydras").contains("Gargoyles = Coins"));
	}

	@Test
	public void perTaskEntriesAreGroupedByTask()
	{
		String value = DropExclusions.addForTask("Hydras = Hydra bones", "Hydras", "Coins");
		value = DropExclusions.addForTask(value, "Gargoyles", "Granite maul");
		assertEquals("Hydras: Hydra bones, Coins; Gargoyles: Granite maul", value);
		assertTrue(DropExclusions.isExcluded("", value, "Hydras", "Coins"));
		assertTrue(DropExclusions.isExcluded("", value, "Gargoyles", "Granite maul"));
	}

	@Test
	public void legacyPerTaskEntriesMigrateWithoutLosingItems()
	{
		assertEquals("Hydras: Hydra bones, Coins; Gargoyles: Coins",
			DropExclusions.formatPerTask(
				"Hydras = Hydra bones; Hydras = Coins; Gargoyles = Coins"));
	}
}
