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

	/**
	 * A boss whose name ends in an s is not a plural. Singularising first left "sarachni", which
	 * matched the NPC nowhere, so the assignment recognised none of its own kills: the counter
	 * still banked them, but no session opened and neither loot nor supplies could attach.
	 */
	@Test
	public void matchesBossNamesEndingInS()
	{
		assertTrue(SlayerTaskTargets.matches("Sarachnis", "Sarachnis"));
		assertTrue(SlayerTaskTargets.matches("Venenatis", "Venenatis"));
		assertTrue(SlayerTaskTargets.matches("Cerberus", "Cerberus"));
		assertTrue(SlayerTaskTargets.matches("Vardorvis", "Vardorvis"));
		assertTrue(SlayerTaskTargets.matches("Duke Sucellus", "Duke Sucellus"));
		// The singular strip still has to work for the plurals it exists for.
		assertTrue(SlayerTaskTargets.matches("Spiders", "Spider"));
	}

	/** Assignable as a regular Dagannoth task and as the boss task, which read differently. */
	@Test
	public void matchesDagannothKingsUnderBothAssignments()
	{
		for (String king : new String[]{"Dagannoth Rex", "Dagannoth Prime", "Dagannoth Supreme"})
		{
			assertTrue(SlayerTaskTargets.matches("Dagannoth", king));
			assertTrue(SlayerTaskTargets.matches("Dagannoth Kings", king));
		}
		assertTrue(SlayerTaskTargets.matches("Dagannoth", "Dagannoth"));
		// The boss assignment is the three kings only — an ordinary dagannoth is not one of them.
		assertFalse(SlayerTaskTargets.matches("Dagannoth Kings", "Dagannoth"));
	}

	@Test
	public void usesWholeWordsAndRejectsUnrelatedNpcs()
	{
		assertFalse(SlayerTaskTargets.matches("Smoke devils", "Dust devil"));
		assertFalse(SlayerTaskTargets.matches("Dogs", "Underdog"));
		assertFalse(SlayerTaskTargets.matches("Araxytes", "Spider"));
	}
}
