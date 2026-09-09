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
	private static final Map<String, List<String>> TASK_EXACT_ALIASES;

	static
	{
		final Map<String, List<String>> aliases = new HashMap<>();
		aliases.put("aberrant spectres", Arrays.asList("Spectre"));
		aliases.put("abyssal demons", Arrays.asList("Abyssal Sire", "Respiratory system", "Reanimated abyssal"));
		// Keyed without the article the game titles the boss task with; taskForms() reaches
		// this from "The Abyssal Sire" as well as from a bare "Abyssal Sire".
		aliases.put("abyssal sire", Arrays.asList("Respiratory system"));
		aliases.put("araxytes", Arrays.asList("Araxxor"));
		aliases.put("aviansies", Arrays.asList("Kree'arra", "Flight Kilisa", "Flockleader Geerin", "Wingman Skree"));
		aliases.put("barrows brothers", Arrays.asList(
			"Ahrim", "Dharok", "Guthan", "Karil", "Torag", "Verac"));
		aliases.put("basilisks", Arrays.asList("The Jormungand", "Typhor"));
		aliases.put("bandits", Arrays.asList("Bandit", "Black Heather", "Donny the Lad", "Speedy Keith"));
		aliases.put("bats", Arrays.asList("Death wing"));
		aliases.put("bears", Arrays.asList("Callisto", "Artio"));
		aliases.put("birds", Arrays.asList("Chicken", "Rooster", "Terrorbird", "Seagull", "Vulture", "Duck", "Duckling", "Penguin", "Roc", "Entrana firebird"));
		aliases.put("black demons", Arrays.asList("Demonic gorilla", "Balfrug Kreeyath", "Skotizo", "Porazdir", "Kolodion"));
		aliases.put("black knights", Arrays.asList("Black Knight"));
		aliases.put("blue dragons", Arrays.asList("Vorkath"));
		// The three wilderness bosses each have a singles-plus variant that counts for the same
		// assignment, and the assignment is only ever handed out under the main boss's name. The
		// variants share no whole word with it, so only an alias can connect the two.
		aliases.put("callisto", Arrays.asList("Artio"));
		aliases.put("cave crawlers", Arrays.asList("Chasm crawler"));
		aliases.put("cave horrors", Arrays.asList("Cave abomination"));
		aliases.put("cave kraken", Arrays.asList("Kraken"));
		aliases.put("cave kraken boss", Arrays.asList("Kraken"));
		aliases.put("chaos druids", Arrays.asList("Salarin the twisted"));
		aliases.put("cockatrice", Arrays.asList("Cockathrice"));
		aliases.put("cows", Arrays.asList("Buffalo", "Brutus"));
		aliases.put("crabs", Arrays.asList("Ammonite Crab", "Frost Crab", "King Sand Crab", "Rock Crab", "Giant Rock Crab", "Sand Crab", "Swamp Crab"));
		aliases.put("crazy archaeologists", Arrays.asList("Deranged archaeologist"));
		aliases.put("crawling hands", Arrays.asList("Crushing hand"));
		aliases.put("custodian stalkers", Arrays.asList("Ancient Custodian"));
		// Assignable two ways, and only one of them needs help. A regular "Dagannoth" task already
		// reaches the kings on the whole-word rule, since each is a Dagannoth by name. The boss
		// assignment is handed out as "Dagannoth Kings", which shares no whole word with any of
		// them — "kings" is not what they are called individually. Not in RuneLite's table either.
		aliases.put("dagannoth kings", Arrays.asList("Dagannoth Rex", "Dagannoth Prime", "Dagannoth Supreme"));
		aliases.put("dark beasts", Arrays.asList("Night beast"));
		aliases.put("dark warriors", Arrays.asList("Dark warrior"));
		aliases.put("dogs", Arrays.asList("Jackal", "Temple Guardian", "Shadow Hound"));
		aliases.put("dust devils", Arrays.asList("Choke devil"));
		aliases.put("dwarves", Arrays.asList("Dwarf", "Black Guard"));
		aliases.put("elves", Arrays.asList("Elf", "Iorwerth Warrior", "Iorwerth Archer", "Mourner"));
		aliases.put("fire giants", Arrays.asList("Branda the Fire Queen"));
		aliases.put("fleshcrawlers", Arrays.asList("Flesh crawler"));
		aliases.put("fossil island wyverns", Arrays.asList("Ancient wyvern", "Long-tailed wyvern", "Spitting wyvern", "Taloned wyvern"));
		aliases.put("gargoyles", Arrays.asList("Dusk", "Dawn"));
		aliases.put("ghosts", Arrays.asList("Death wing", "Tortured soul", "Forgotten Soul", "Revenant",
			"Nazastarool", "Tree spirit", "Treus Dayth"));
		aliases.put("goblins", Arrays.asList("Sergeant Strongstack", "Sergeant Grimspike", "Sergeant Steelwill",
			"Mosschin", "Redeyes", "Skoblin", "Snailfeet", "Snothead", "Strongbones", "Wormbrain"));
		aliases.put("greater demons", Arrays.asList("K'ril Tsutsaroth", "Tstanon Karlak", "Skotizo", "Tormented Demon", "Judge of Yama"));
		aliases.put("green dragons", Arrays.asList("Elvarg"));
		aliases.put("grotesque guardians", Arrays.asList("Dusk", "Dawn"));
		aliases.put("hellhounds", Arrays.asList("Cerberus", "Bouncer"));
		aliases.put("hill giants", Arrays.asList("Cyclops", "Reanimated giant", "Obor"));
		aliases.put("ice giants", Arrays.asList("Eldric the Ice King"));
		aliases.put("ice warriors", Arrays.asList("Icelord", "Kamil"));
		aliases.put("infernal mages", Arrays.asList("Malevolent mage"));
		aliases.put("jellies", Arrays.asList("Jelly"));
		aliases.put("jungle horrors", Arrays.asList("Reanimated horror"));
		aliases.put("lava dragons", Arrays.asList("Lava dragon"));
		aliases.put("lesser demons", Arrays.asList("Zakl'n Gritch"));
		aliases.put("lesser nagua", Arrays.asList("Sulphur Nagua", "Frost Nagua", "Earthen Nagua", "Amoxliatl"));
		aliases.put("lizardmen", Arrays.asList("Lizardman"));
		aliases.put("metal dragons", Arrays.asList("Bronze dragon", "Iron Dragon", "Steel dragon", "Mithril dragon", "Adamant dragon", "Rune dragon"));
		aliases.put("monkeys", Arrays.asList("Tortured gorilla", "Demonic gorilla", "Padulah",
			"Oipuis", "Ouhai", "Uodai", "Uyoro"));
		aliases.put("moss giants", Arrays.asList("Bryophyta"));
		aliases.put("mutated zygomites", Arrays.asList("Zygomite", "Fungi"));
		aliases.put("nechryael", Arrays.asList("Nechryarch"));
		aliases.put("ogres", Arrays.asList("Enclave guard", "Mogre", "Ogress", "Skogre", "Zogre", "City guard", "Gorad"));
		aliases.put("pirates", Arrays.asList("Pirate"));
		aliases.put("pyrefiends", Arrays.asList("Pyrelord"));
		// Not in RuneLite's table. Scurrius counts for a rat assignment and shares no word with
		// it, so without this its kills and its drops are both invisible to the task.
		aliases.put("rats", Arrays.asList("Scurrius"));
		aliases.put("scabarites", Arrays.asList("Scarab swarm", "Locust rider", "Scarab mage", "Small Scarab", "Giant Scarab", "Scarabs"));
		aliases.put("scorpions", Arrays.asList("Scorpia", "Lobstrosity", "Giant Lobster",
			"Scorpia's guardian", "Scorpia's offspring"));
		aliases.put("shades", Arrays.asList("Loar", "Phrin", "Riyl", "Asyn", "Fiyr", "Urium"));
		aliases.put("skeletons", Arrays.asList("Vet'ion", "Calvar'ion", "Skeletal Mystic",
			"Mosschin", "Redeyes", "Skoblin", "Snailfeet", "Snothead", "Strongbones",
			"Skeletal miner", "Skogre", "Ulfric", "Undead one", "Nazastarool"));
		aliases.put("spiders", Arrays.asList("Kalrag", "Sarachnis", "Venenatis", "Spindel", "Araxxor", "Araxyte"));
		aliases.put("spiritual creatures", Arrays.asList("Spiritual ranger", "Spiritual mage", "Spiritual warrior"));
		// The named individuals are exact below: each is one whole word, and "Rock" as a
		// whole-word needle also matched Rock Crab, Rock lobster and Rock Golem.
		aliases.put("trolls", Arrays.asList("Chuck up", "Daddy's special water", "The rocks"));
		// The four city TzHaar need no alias: the hyphen reads as a word break, so the assignment
		// matches "TzHaar-Ket" and the rest on its own name. Everything the Fight Caves and the
		// Inferno hold is in the same slayer category and shares no word with it, so each has to be
		// listed. Ordered as they are met.
		aliases.put("tzhaar", Arrays.asList(
			"Tz-Kih", "Tz-Kek", "Tok-Xil", "Yt-MejKot", "Ket-Zek", "Yt-HurKot", "TzTok-Jad",
			"Jal-Nib", "Jal-MejRah", "Jal-Ak", "Jal-AkRek-Mej", "Jal-AkRek-Xil", "Jal-AkRek-Ket",
			"Jal-ImKot", "Jal-Xil", "Jal-Zek", "Jal-MejJak", "JalTok-Jad", "TzKal-Zuk"));
		// A venator counts for a vampyres assignment as well as for its own, and shares no whole
		// word with either "vampyres" or "vampyre". The superior, "Blood-starved venator", needs
		// nothing further — the alias is a whole word of it. The dedicated "Venators" assignment
		// reaches both on the singular strip and needs no key of its own.
		aliases.put("vampyres", Arrays.asList("Vyrewatch", "Venator",
			"Count Draynor", "Damien Leucurte", "Dessous", "Kroy", "Ranis Drakan"));
		aliases.put("venenatis", Arrays.asList("Spindel"));
		aliases.put("vet'ion", Arrays.asList("Calvar'ion"));
		aliases.put("warped creatures", Arrays.asList("Warped terrorbird", "Warped tortoise", "Mutated terrorbird", "Mutated tortoise"));
		aliases.put("werewolves", Arrays.asList("Werewolf"));
		aliases.put("wolves", Arrays.asList("Wolf"));
		aliases.put("wyrms", Arrays.asList("Wyrmling", "Strykewyrm"));
		aliases.put("zombies", Arrays.asList("Undead", "Vorkath", "Zogre",
			"A corpse", "Nazastarool", "Skogre", "Slash Bash", "Sorebones"));
		TASK_ALIASES = Collections.unmodifiableMap(aliases);

		// Names that may only match an NPC called exactly that. Each is a whole word of a
		// longer name belonging to something else, so the whole-word rule is not enough on
		// its own and an alias here would credit kills the assignment never counted.
		final Map<String, List<String>> exact = new HashMap<>();
		// The Ape Atoll monkey guard against the Desert Treasure II boss, which a player may
		// well be killing while holding a monkeys assignment.
		exact.put("monkeys", Arrays.asList("Duke"));
		// Against "Reanimated demon spawn", which carries no slayer category at all.
		exact.put("lesser demons", Arrays.asList("Reanimated demon"));
		// Trolls in the Troll Stronghold are named for what they eat, in one word each.
		exact.put("trolls", Arrays.asList(
			"Dad", "Arrg", "Stick", "Kraka", "Pee Hat", "Rock", "Twig", "Berry"));
		TASK_EXACT_ALIASES = Collections.unmodifiableMap(exact);
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

		// Every spelling the assignment might reach the plugin under. A boss assignment is
		// titled "The Shellbane Gryphon" while the NPC is plainly "Shellbane gryphon", so the
		// article has to come off before anything can match; trying both spellings rather than
		// replacing one with the other means the table does not depend on which the game sends.
		for (String candidate : taskForms(task))
		{
			// The name as assigned, before any singularising. Boss assignments are handed out under
			// the boss's own name, and a boss whose name simply ends in an s — Sarachnis, Venenatis,
			// Cerberus, Vardorvis — is not a plural. Stripping first left "sarachni", which matches
			// the NPC called Sarachnis nowhere, so the assignment recognised none of its own kills.
			if (wholeWordMatch(npc, candidate))
			{
				return true;
			}

			if (wholeWordMatch(npc, candidate.replaceAll("s$", "")))
			{
				return true;
			}

			for (String alias : TASK_ALIASES.getOrDefault(candidate, Collections.emptyList()))
			{
				// Normalised, not merely lowercased: the aliases carry hyphens too, and a needle still
				// spelled "tztok-jad" could never match a haystack folded to "tztok jad".
				if (wholeWordMatch(npc, normalise(alias)))
				{
					return true;
				}
			}

			for (String alias : TASK_EXACT_ALIASES.getOrDefault(candidate, Collections.emptyList()))
			{
				if (npc.equals(normalise(alias)))
				{
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * The assignment as sent, and — for the boss tasks the game titles "The Kalphite Queen",
	 * "The Giant Mole", "The Shellbane Gryphon" and a dozen more — the same name without its
	 * article, which is what the NPC is actually called.
	 *
	 * <p>Only the assignment is stripped, never the NPC name, so nothing widens: an assignment
	 * that does not begin with the article is returned untouched.
	 */
	private static List<String> taskForms(String task)
	{
		return task.startsWith("the ") && task.length() > 4
			? Arrays.asList(task, task.substring(4))
			: Collections.singletonList(task);
	}

	/**
	 * Folds the characters that would otherwise decide whether a kill counts onto the plain ASCII
	 * the tables are written in: a non-breaking space, the typographic apostrophe that would make
	 * Vet'ion and Calvar'ion miss their alias key, and the hyphen.
	 *
	 * <p>A hyphen is a word break in every name that carries one \u2014 "TzHaar-Ket" is a Ket of the
	 * TzHaar \u2014 but the whole-word matcher only ever accepted whitespace, so a TzHaar assignment
	 * failed to recognise the four monsters named directly after it.
	 */
	private static String normalise(String name)
	{
		return name
			.replace('\u00A0', ' ')
			.replace('\u2019', '\'')
			.replace('-', ' ')
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
