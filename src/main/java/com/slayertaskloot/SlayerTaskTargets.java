package com.slayertaskloot;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Matches assignment names to NPC names using the same singular-name convention and aliases as
 * RuneLite's Slayer plugin. Kept locally because the upstream Task enum is package-private.
 */
final class SlayerTaskTargets
{
	private static final Map<String, List<String>> TASK_ALIASES;

	static
	{
		final Map<String, List<String>> aliases = new HashMap<>();
		aliases.put("aberrant spectres", Arrays.asList("Spectre"));
		aliases.put("abyssal demons", Arrays.asList("Abyssal Sire"));
		aliases.put("araxytes", Arrays.asList("Araxxor"));
		aliases.put("aviansies", Arrays.asList("Kree'arra", "Flight Kilisa", "Flockleader Geerin", "Wingman Skree"));
		aliases.put("bandits", Arrays.asList("Bandit", "Black Heather", "Donny the Lad", "Speedy Keith"));
		aliases.put("bats", Arrays.asList("Death wing"));
		aliases.put("bears", Arrays.asList("Callisto", "Artio"));
		aliases.put("birds", Arrays.asList("Chicken", "Rooster", "Terrorbird", "Seagull", "Vulture", "Duck", "Penguin", "Baby Roc"));
		aliases.put("black demons", Arrays.asList("Demonic gorilla", "Balfrug Kreeyath", "Skotizo", "Porazdir"));
		aliases.put("black knights", Arrays.asList("Black Knight"));
		aliases.put("blue dragons", Arrays.asList("Vorkath"));
		// The three wilderness bosses each have a singles-plus variant that counts for the same
		// assignment, and the assignment is only ever handed out under the main boss's name. The
		// variants share no whole word with it, so only an alias can connect the two.
		aliases.put("callisto", Arrays.asList("Artio"));
		aliases.put("cave crawlers", Arrays.asList("Chasm crawler"));
		aliases.put("cave horrors", Arrays.asList("Cave abomination"));
		aliases.put("cave kraken", Arrays.asList("Kraken"));
		aliases.put("cockatrice", Arrays.asList("Cockathrice"));
		aliases.put("cows", Arrays.asList("Buffalo", "Brutus"));
		aliases.put("crabs", Arrays.asList("Ammonite Crab", "Frost Crab", "King Sand Crab", "Rock Crab", "Giant Rock Crab", "Sand Crab", "Swamp Crab"));
		aliases.put("crawling hands", Arrays.asList("Crushing hand"));
		aliases.put("custodian stalkers", Arrays.asList("Ancient Custodian"));
		// Assignable two ways, and only one of them needs help. A regular "Dagannoth" task already
		// reaches the kings on the whole-word rule, since each is a Dagannoth by name. The boss
		// assignment is handed out as "Dagannoth Kings", which shares no whole word with any of
		// them — "kings" is not what they are called individually. Not in RuneLite's table either.
		aliases.put("dagannoth kings", Arrays.asList("Dagannoth Rex", "Dagannoth Prime", "Dagannoth Supreme"));
		aliases.put("dark beasts", Arrays.asList("Night beast"));
		aliases.put("dark warriors", Arrays.asList("Dark warrior"));
		aliases.put("dogs", Arrays.asList("Jackal", "Temple Guardian"));
		aliases.put("dust devils", Arrays.asList("Choke devil"));
		aliases.put("dwarves", Arrays.asList("Dwarf", "Black Guard"));
		aliases.put("elves", Arrays.asList("Elf", "Iorwerth Warrior", "Iorwerth Archer"));
		aliases.put("fire giants", Arrays.asList("Branda the Fire Queen"));
		aliases.put("fleshcrawlers", Arrays.asList("Flesh crawler"));
		aliases.put("fossil island wyverns", Arrays.asList("Ancient wyvern", "Long-tailed wyvern", "Spitting wyvern", "Taloned wyvern"));
		aliases.put("gargoyles", Arrays.asList("Dusk", "Dawn"));
		aliases.put("ghosts", Arrays.asList("Death wing", "Tortured soul", "Forgotten Soul", "Revenant"));
		aliases.put("goblins", Arrays.asList("Sergeant Strongstack", "Sergeant Grimspike", "Sergeant Steelwill"));
		aliases.put("greater demons", Arrays.asList("K'ril Tsutsaroth", "Tstanon Karlak", "Skotizo", "Tormented Demon"));
		aliases.put("green dragons", Arrays.asList("Elvarg"));
		aliases.put("the grotesque guardians", Arrays.asList("Dusk", "Dawn"));
		aliases.put("hellhounds", Arrays.asList("Cerberus"));
		aliases.put("hill giants", Arrays.asList("Cyclops", "Reanimated giant", "Obor"));
		aliases.put("ice giants", Arrays.asList("Eldric the Ice King"));
		aliases.put("ice warriors", Arrays.asList("Icelord"));
		aliases.put("infernal mages", Arrays.asList("Malevolent mage"));
		aliases.put("jellies", Arrays.asList("Jelly"));
		aliases.put("the cave kraken boss", Arrays.asList("Kraken"));
		aliases.put("lava dragons", Arrays.asList("Lava dragon"));
		aliases.put("lesser demons", Arrays.asList("Zakl'n Gritch"));
		aliases.put("lesser nagua", Arrays.asList("Sulphur Nagua", "Frost Nagua", "Amoxliatl"));
		aliases.put("lizardmen", Arrays.asList("Lizardman"));
		aliases.put("metal dragons", Arrays.asList("Bronze dragon", "Iron Dragon", "Steel dragon", "Mithril dragon", "Adamant dragon", "Rune dragon"));
		aliases.put("monkeys", Arrays.asList("Tortured gorilla", "Demonic gorilla", "Padulah"));
		aliases.put("moss giants", Arrays.asList("Bryophyta"));
		aliases.put("mutated zygomites", Arrays.asList("Zygomite", "Fungi"));
		aliases.put("nechryael", Arrays.asList("Nechryarch"));
		aliases.put("ogres", Arrays.asList("Enclave guard", "Mogre", "Ogress", "Skogre", "Zogre"));
		aliases.put("pirates", Arrays.asList("Pirate"));
		aliases.put("pyrefiends", Arrays.asList("Flaming pyrelord"));
		// Not in RuneLite's table. Scurrius counts for a rat assignment and shares no word with
		// it, so without this its kills and its drops are both invisible to the task.
		aliases.put("rats", Arrays.asList("Scurrius"));
		aliases.put("scabarites", Arrays.asList("Scarab swarm", "Locust rider", "Scarab mage", "Small Scarab"));
		aliases.put("scorpions", Arrays.asList("Scorpia", "Lobstrosity"));
		aliases.put("shades", Arrays.asList("Loar", "Phrin", "Riyl", "Asyn", "Fiyr", "Urium"));
		aliases.put("skeletons", Arrays.asList("Vet'ion", "Calvar'ion", "Skeletal Mystic"));
		aliases.put("spiders", Arrays.asList("Kalrag", "Sarachnis", "Venenatis", "Spindel", "Araxxor", "Araxyte"));
		aliases.put("spiritual creatures", Arrays.asList("Spiritual ranger", "Spiritual mage", "Spiritual warrior"));
		aliases.put("trolls", Arrays.asList("Dad", "Arrg", "Stick", "Kraka", "Pee Hat", "Rock", "Twig", "Berry"));
		aliases.put("tzhaar", Arrays.asList("TzTok-Jad", "TzKal-Zuk"));
		// A venator counts for a vampyres assignment as well as for its own, and shares no whole
		// word with either "vampyres" or "vampyre". The superior, "Blood-starved venator", needs
		// nothing further — the alias is a whole word of it. The dedicated "Venators" assignment
		// reaches both on the singular strip and needs no key of its own.
		aliases.put("vampyres", Arrays.asList("Vyrewatch", "Venator"));
		aliases.put("venenatis", Arrays.asList("Spindel"));
		aliases.put("vet'ion", Arrays.asList("Calvar'ion"));
		aliases.put("warped creatures", Arrays.asList("Warped terrorbird", "Warped tortoise", "Mutated terrorbird", "Mutated tortoise"));
		aliases.put("werewolves", Arrays.asList("Werewolf"));
		aliases.put("wolves", Arrays.asList("Wolf"));
		aliases.put("wyrms", Arrays.asList("Wyrmling", "Strykewyrm"));
		aliases.put("zombies", Arrays.asList("Undead", "Vorkath", "Zogre"));
		TASK_ALIASES = Collections.unmodifiableMap(aliases);
	}

	private SlayerTaskTargets()
	{
	}

	static boolean matches(String taskName, String npcName)
	{
		if (taskName == null || npcName == null)
		{
			return false;
		}

		final String task = normalise(taskName);
		final String npc = normalise(npcName);
		if (task.isEmpty() || npc.isEmpty())
		{
			return false;
		}

		// The name as assigned, before any singularising. Boss assignments are handed out under
		// the boss's own name, and a boss whose name simply ends in an s — Sarachnis, Venenatis,
		// Cerberus, Vardorvis — is not a plural. Stripping first left "sarachni", which matches
		// the NPC called Sarachnis nowhere, so the assignment recognised none of its own kills.
		if (wholeWordMatch(npc, task))
		{
			return true;
		}

		if (wholeWordMatch(npc, task.replaceAll("s$", "")))
		{
			return true;
		}

		for (String alias : TASK_ALIASES.getOrDefault(task, Collections.emptyList()))
		{
			if (wholeWordMatch(npc, alias.toLowerCase(Locale.ROOT)))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Folds the two characters the game spells inconsistently onto the plain ASCII the tables are
	 * written in: a non-breaking space, and the typographic apostrophe that would otherwise make
	 * Vet'ion and Calvar'ion miss their alias key.
	 */
	private static String normalise(String name)
	{
		return name
			.replace('\u00A0', ' ')
			.replace('\u2019', '\'')
			.trim()
			.toLowerCase(Locale.ROOT);
	}

	private static boolean wholeWordMatch(String haystack, String needle)
	{
		if (needle.isEmpty())
		{
			return false;
		}
		final String regex = "(?:\\s|^)" + Pattern.quote(needle) + "(?:\\s|$)";
		return Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(haystack).find();
	}
}
