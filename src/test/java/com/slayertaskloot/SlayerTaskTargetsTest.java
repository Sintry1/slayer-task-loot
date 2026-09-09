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
		// An alias that carries a hyphen of its own has to be folded the same way the NPC name is,
		// or it stops matching the moment the hyphen becomes a word break on one side only.
		assertTrue(SlayerTaskTargets.matches("Fossil island wyverns", "Long-tailed wyvern"));
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

	/**
	 * The assignment is handed out as "TzHaar" and the four city monsters are named directly after
	 * it, but only with a hyphen between the race and the caste — which the whole-word matcher read
	 * as part of the word, so the assignment recognised none of its own targets.
	 */
	@Test
	public void matchesTheCityTzhaarAcrossTheHyphen()
	{
		assertTrue(SlayerTaskTargets.matches("TzHaar", "TzHaar-Ket"));
		assertTrue(SlayerTaskTargets.matches("TzHaar", "TzHaar-Xil"));
		assertTrue(SlayerTaskTargets.matches("TzHaar", "TzHaar-Hur"));
		assertTrue(SlayerTaskTargets.matches("TzHaar", "TzHaar-Mej"));
		// The panel titlecases the assignment from the uppercase name the task table holds.
		assertTrue(SlayerTaskTargets.matches("Tzhaar", "TzHaar-Ket"));
		// The named individuals are the same monsters under a third name part.
		assertTrue(SlayerTaskTargets.matches("TzHaar", "TzHaar-Ket-Zuh"));
		assertTrue(SlayerTaskTargets.matches("TzHaar", "TzHaar-Mej-Roh"));
	}

	/**
	 * The Fight Caves and the Inferno are the same slayer category, and nothing in either shares a
	 * word with the assignment, so every one of them needs an alias.
	 */
	@Test
	public void matchesTheFightCaveAndInfernoTzhaar()
	{
		for (String caveMonster : new String[]{
			"Tz-Kih", "Tz-Kek", "Tok-Xil", "Yt-MejKot", "Ket-Zek", "Yt-HurKot", "TzTok-Jad"})
		{
			assertTrue(caveMonster, SlayerTaskTargets.matches("TzHaar", caveMonster));
		}
		for (String infernoMonster : new String[]{
			"Jal-Nib", "Jal-MejRah", "Jal-Ak", "Jal-AkRek-Mej", "Jal-AkRek-Xil", "Jal-AkRek-Ket",
			"Jal-ImKot", "Jal-Xil", "Jal-Zek", "Jal-MejJak", "JalTok-Jad", "TzKal-Zuk"})
		{
			assertTrue(infernoMonster, SlayerTaskTargets.matches("TzHaar", infernoMonster));
		}
		// The blob's spawns are longer names beginning with its own, and the whole-word rule has to
		// keep telling them apart now that the hyphen no longer does.
		assertFalse(SlayerTaskTargets.matches("Jal-Ak", "Jal-AkRek-Mej"));
	}

	/**
	 * Both bosses are also assignable in their own right, and the assignment is then the boss
	 * alone — killing the waves that lead to it does not count towards it.
	 */
	@Test
	public void aJadOrZukAssignmentIsThatBossOnly()
	{
		assertTrue(SlayerTaskTargets.matches("TzTok-Jad", "TzTok-Jad"));
		assertTrue(SlayerTaskTargets.matches("TzKal-Zuk", "TzKal-Zuk"));
		assertFalse(SlayerTaskTargets.matches("TzTok-Jad", "Ket-Zek"));
		assertFalse(SlayerTaskTargets.matches("TzKal-Zuk", "Jal-Zek"));
		assertFalse(SlayerTaskTargets.matches("TzTok-Jad", "TzHaar-Ket"));
	}

	/**
	 * The game titles a boss assignment with an article the NPC's own name does not carry, so
	 * every one of these recognised nothing at all: no session, no loot, no supply cost, while
	 * the name-blind counter kept banking kills. Confirmed rather than assumed — a persisted
	 * record in the profile reads "The Shellbane Gryphon" at 3 counter kills and 0 sessions.
	 */
	@Test
	public void matchesBossAssignmentsTitledWithTheArticle()
	{
		assertTrue(SlayerTaskTargets.matches("The Shellbane Gryphon", "Shellbane gryphon"));
		assertTrue(SlayerTaskTargets.matches("The Abyssal Sire", "Abyssal Sire"));
		assertTrue(SlayerTaskTargets.matches("The Alchemical Hydra", "Alchemical Hydra"));
		assertTrue(SlayerTaskTargets.matches("The Chaos Elemental", "Chaos Elemental"));
		assertTrue(SlayerTaskTargets.matches("The Chaos Fanatic", "Chaos Fanatic"));
		assertTrue(SlayerTaskTargets.matches("The Giant Mole", "Giant Mole"));
		assertTrue(SlayerTaskTargets.matches("The Kalphite Queen", "Kalphite Queen"));
		assertTrue(SlayerTaskTargets.matches("The King Black Dragon", "King Black Dragon"));
		assertTrue(SlayerTaskTargets.matches("The Maggot King", "Maggot King"));
		assertTrue(SlayerTaskTargets.matches("The Phantom Muspah", "Phantom Muspah"));
		assertTrue(SlayerTaskTargets.matches("The Thermonuclear Smoke Devil", "Thermonuclear smoke devil"));
		// The two whose NPC name carries the article too must keep working from the raw name.
		assertTrue(SlayerTaskTargets.matches("The Leviathan", "The Leviathan"));
		assertTrue(SlayerTaskTargets.matches("The Whisperer", "The Whisperer"));
		// Aliases are keyed without the article, and reachable with or without it, because
		// nothing here can prove which spelling the game's task table holds.
		assertTrue(SlayerTaskTargets.matches("The Cave Kraken Boss", "Kraken"));
		assertTrue(SlayerTaskTargets.matches("Cave Kraken Boss", "Kraken"));
		assertTrue(SlayerTaskTargets.matches("The Grotesque Guardians", "Dusk"));
		assertTrue(SlayerTaskTargets.matches("Grotesque Guardians", "Dawn"));
		// Only the assignment loses its article, never the NPC name.
		assertFalse(SlayerTaskTargets.matches("The Giant Mole", "Mole"));
	}

	/** The six brothers are assigned as a group and none of them shares a word with it. */
	@Test
	public void matchesTheBarrowsBrothers()
	{
		for (String brother : new String[]{
			"Ahrim the Blighted", "Dharok the Wretched", "Guthan the Infested",
			"Karil the Tainted", "Torag the Corrupted", "Verac the Defiled"})
		{
			assertTrue(brother, SlayerTaskTargets.matches("Barrows Brothers", brother));
		}
	}

	/**
	 * A named individual is one whole word of something else's name, so it has to match the
	 * NPC exactly. "Rock" is a troll in the Troll Stronghold, and as a whole-word needle it
	 * also matched Rock Crab — a common training spot, on a common Turael task.
	 */
	@Test
	public void namedIndividualsMatchOnlyTheirOwnNpc()
	{
		assertTrue(SlayerTaskTargets.matches("Trolls", "Rock"));
		assertFalse(SlayerTaskTargets.matches("Trolls", "Rock Crab"));
		assertFalse(SlayerTaskTargets.matches("Trolls", "Giant Rock Crab"));
		assertFalse(SlayerTaskTargets.matches("Trolls", "Rock lobster"));
		assertFalse(SlayerTaskTargets.matches("Trolls", "Rock Golem"));
		// The multi-word troll names are unambiguous and stay ordinary aliases.
		assertTrue(SlayerTaskTargets.matches("Trolls", "Chuck up"));
		assertTrue(SlayerTaskTargets.matches("Trolls", "The rocks"));
		// A monkeys assignment reaches the Ape Atoll guard called Duke, and stops there.
		assertTrue(SlayerTaskTargets.matches("Monkeys", "Duke"));
		assertFalse(SlayerTaskTargets.matches("Monkeys", "Duke Sucellus"));
		// ...and against the spawn, which carries no slayer category of its own.
		assertTrue(SlayerTaskTargets.matches("Lesser demons", "Reanimated demon"));
		assertFalse(SlayerTaskTargets.matches("Lesser demons", "Reanimated demon spawn"));
	}

	/**
	 * Targets found by sweeping every assignment against the wiki's per-monster slayer
	 * category, each one a monster that shares no word with the assignment it counts for.
	 */
	@Test
	public void matchesTargetsThatShareNoWordWithTheirAssignment()
	{
		assertTrue(SlayerTaskTargets.matches("Basilisks", "The Jormungand"));
		assertTrue(SlayerTaskTargets.matches("Basilisks", "Typhor"));
		assertTrue(SlayerTaskTargets.matches("Chaos druids", "Salarin the twisted"));
		assertTrue(SlayerTaskTargets.matches("Crazy Archaeologists", "Deranged archaeologist"));
		assertTrue(SlayerTaskTargets.matches("Dogs", "Shadow Hound"));
		assertTrue(SlayerTaskTargets.matches("Elves", "Mourner"));
		assertTrue(SlayerTaskTargets.matches("Ghosts", "Treus Dayth"));
		assertTrue(SlayerTaskTargets.matches("Goblins", "Wormbrain"));
		assertTrue(SlayerTaskTargets.matches("Greater demons", "Judge of Yama"));
		assertTrue(SlayerTaskTargets.matches("Hellhounds", "Bouncer"));
		assertTrue(SlayerTaskTargets.matches("Ice warriors", "Kamil"));
		assertTrue(SlayerTaskTargets.matches("Lesser Nagua", "Earthen Nagua"));
		assertTrue(SlayerTaskTargets.matches("Ogres", "Gorad"));
		assertTrue(SlayerTaskTargets.matches("Scorpions", "Scorpia's guardian"));
		assertTrue(SlayerTaskTargets.matches("Skeletons", "Ulfric"));
		assertTrue(SlayerTaskTargets.matches("Vampyres", "Ranis Drakan"));
		assertTrue(SlayerTaskTargets.matches("Zombies", "Slash Bash"));
		assertTrue(SlayerTaskTargets.matches("Abyssal demons", "Respiratory system"));
		assertTrue(SlayerTaskTargets.matches("The Abyssal Sire", "Respiratory system"));
		// One alias covers the superior and the infernal one, both being pyrelords by name.
		assertTrue(SlayerTaskTargets.matches("Pyrefiends", "Pyrelord"));
		assertTrue(SlayerTaskTargets.matches("Pyrefiends", "Flaming pyrelord"));
		assertTrue(SlayerTaskTargets.matches("Pyrefiends", "Infernal pyrelord"));
	}

	/**
	 * The scabarite scarabs are listed by name rather than under a bare "Scarab", which would
	 * also take the Tombs of Amascut scarabs — none of which carries a slayer category.
	 */
	@Test
	public void scarabsDoNotReachTheTombsOfAmascut()
	{
		assertTrue(SlayerTaskTargets.matches("Scabarites", "Giant Scarab"));
		assertTrue(SlayerTaskTargets.matches("Scabarites", "Scarabs"));
		assertTrue(SlayerTaskTargets.matches("Scabarites", "Scarab Mage"));
		assertTrue(SlayerTaskTargets.matches("Scabarites", "Small scarab"));
		assertFalse(SlayerTaskTargets.matches("Scabarites", "Soldier Scarab"));
		assertFalse(SlayerTaskTargets.matches("Scabarites", "Spitting Scarab"));
		assertFalse(SlayerTaskTargets.matches("Scabarites", "Agile Scarab"));
		assertFalse(SlayerTaskTargets.matches("Scabarites", "Arcane Scarab"));
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
