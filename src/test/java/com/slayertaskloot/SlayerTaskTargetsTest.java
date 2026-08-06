package com.slayertaskloot;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SlayerTaskTargetsTest
{
	@Test
	public void matchesOrdinarySuperiorAndBossTargets()
	{
		assertTrue(SlayerTaskTargets.matches("Smoke devils", "Smoke devil"));
		assertTrue(SlayerTaskTargets.matches("Dust devils", "Choke devil"));
		assertTrue(SlayerTaskTargets.matches("Blue dragons", "Vorkath"));
		assertTrue(SlayerTaskTargets.matches("Araxytes", "Araxxor"));
		// Shares no word with its assignment, so only the alias can connect the two.
		assertTrue(SlayerTaskTargets.matches("Rats", "Scurrius"));
		assertTrue(SlayerTaskTargets.matches("Rats", "Giant rat"));
	}

	@Test
	public void usesWholeWordsAndRejectsUnrelatedNpcs()
	{
		assertFalse(SlayerTaskTargets.matches("Smoke devils", "Dust devil"));
		assertFalse(SlayerTaskTargets.matches("Dogs", "Underdog"));
		assertFalse(SlayerTaskTargets.matches("Araxytes", "Spider"));
	}
}
