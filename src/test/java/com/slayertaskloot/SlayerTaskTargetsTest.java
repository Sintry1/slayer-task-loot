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

	/**
	 * The three wilderness bosses each have a singles-plus variant that counts for the same
	 * assignment, which is only ever handed out under the main boss's name.
	 */
	@Test
	public void matchesWildernessBossVariants()
	{
		assertTrue(SlayerTaskTargets.matches("Vet'ion", "Calvar'ion"));
		assertTrue(SlayerTaskTargets.matches("Vet'ion", "Vet'ion"));
		// Both bosses fight on in a second form under a longer name.
		assertTrue(SlayerTaskTargets.matches("Vet'ion", "Calvar'ion Reborn"));
		assertTrue(SlayerTaskTargets.matches("Vet'ion", "Vet'ion Reborn"));
		assertTrue(SlayerTaskTargets.matches("Callisto", "Artio"));
		assertTrue(SlayerTaskTargets.matches("Callisto", "Callisto"));
		assertTrue(SlayerTaskTargets.matches("Venenatis", "Spindel"));
		assertTrue(SlayerTaskTargets.matches("Venenatis", "Venenatis"));
		// The variants are not interchangeable across assignments.
		assertFalse(SlayerTaskTargets.matches("Callisto", "Spindel"));
		assertFalse(SlayerTaskTargets.matches("Venenatis", "Artio"));
	}

	/**
	 * A venator counts for a vampyres assignment and shares no word with it, so only an alias can
	 * connect the two. The relation is one-way and the lookup is keyed on the assignment, which
	 * keeps it so: a vampyres task reaches a venator, a venators task reaches only venators.
	 */
	@Test
	public void matchesVenatorsOnAVampyresTask()
	{
		assertTrue(SlayerTaskTargets.matches("Vampyres", "Venator"));
		assertTrue(SlayerTaskTargets.matches("Vampyres", "Blood-starved venator"));
		// The dedicated assignment reaches both on the singular strip, with no key of its own.
		assertTrue(SlayerTaskTargets.matches("Venators", "Venator"));
		assertTrue(SlayerTaskTargets.matches("Venators", "Blood-starved venator"));
		// ...and no further. Vampyres do not count for a venators task.
		assertFalse(SlayerTaskTargets.matches("Venators", "Vyrewatch"));
		assertFalse(SlayerTaskTargets.matches("Venators", "Vyrewatch Sentinel"));
		assertFalse(SlayerTaskTargets.matches("Venators", "Feral Vampyre"));
		assertFalse(SlayerTaskTargets.matches("Venators", "Vampyre Juvinate"));
		// The rest of the vampyres targets, which were never broken.
		assertTrue(SlayerTaskTargets.matches("Vampyres", "Feral Vampyre"));
		assertTrue(SlayerTaskTargets.matches("Vampyres", "Vampyre Juvinate"));
		assertTrue(SlayerTaskTargets.matches("Vampyres", "Vampyre Juvenile"));
		assertTrue(SlayerTaskTargets.matches("Vampyres", "Vyrewatch"));
		assertTrue(SlayerTaskTargets.matches("Vampyres", "Vyrewatch Sentinel"));
	}

	/** The game spells the apostrophe both ways; neither may decide whether a kill counts. */
	@Test
	public void toleratesTypographicApostrophes()
	{
		assertTrue(SlayerTaskTargets.matches("Vet\u2019ion", "Calvar'ion"));
		assertTrue(SlayerTaskTargets.matches("Vet'ion", "Calvar\u2019ion"));
	}

	@Test
	public void usesWholeWordsAndRejectsUnrelatedNpcs()
	{
		assertFalse(SlayerTaskTargets.matches("Smoke devils", "Dust devil"));
		assertFalse(SlayerTaskTargets.matches("Dogs", "Underdog"));
		assertFalse(SlayerTaskTargets.matches("Araxytes", "Spider"));
	}
}
