package com.slayertaskloot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntFunction;
import org.junit.Test;

/**
 * Dose netting across every potion family a task is likely to involve, rather than the one it was
 * developed against.
 *
 * <p>Prayer potion proves less than it looks. Its name is the plainest shape a dose family can
 * have, and passing on it says nothing about the ones that differ: a hyphen ("Anti-venom"), a
 * trailing plus that another family is a prefix of ("Antidote+" against "Antidote++"), no space
 * before the dose ("Superantipoison"), a space before it and a number inside the base
 * ("Serum 207"), and a sibling separated only by that number ("Serum 208").
 *
 * <p>Ids and names are transcribed from RuneLite's own {@code ItemID} javadoc and
 * {@code item_variations.json}, so a family regrouped upstream fails here rather than quietly
 * ceasing to net in game.
 *
 * <p>What makes this exercise the variation lookup rather than the arithmetic: nothing but the
 * 3-dose container ever drops, so the 1-dose the remainder is repacked into is reachable only
 * through {@link net.runelite.client.game.ItemVariationMapping}. A family missing from that table
 * leaves the remainder with no container to go into.
 */
public class TaskNettingDoseFamiliesTest
{
	/**
	 * The exact prefix the game puts before the dose, then the ids of the 1-, 2-, 3- and 4-dose
	 * containers. Two families really do carry a space there — "Serum 207 (4)" and "Overload (4)"
	 * against "Prayer potion(4)" — so the prefix is stored verbatim rather than synthesised, and
	 * the trailing space in those two rows is deliberate.
	 */
	private static final Object[][] FOUR_DOSE = {
		{"Prayer potion", 143, 141, 139, 2434},
		{"Super restore", 3030, 3028, 3026, 3024},
		{"Saradomin brew", 6691, 6689, 6687, 6685},
		{"Super combat potion", 12701, 12699, 12697, 12695},
		{"Divine super combat potion", 23694, 23691, 23688, 23685},
		{"Ranging potion", 173, 171, 169, 2444},
		{"Divine ranging potion", 23742, 23739, 23736, 23733},
		{"Stamina potion", 12631, 12629, 12627, 12625},
		{"Antidote+", 5949, 5947, 5945, 5943},
		{"Antidote++", 5958, 5956, 5954, 5952},
		{"Anti-venom", 12911, 12909, 12907, 12905},
		{"Anti-venom+", 12919, 12917, 12915, 12913},
		{"Antipoison", 179, 177, 175, 2446},
		{"Superantipoison", 185, 183, 181, 2448},
		{"Super antifire potion", 21987, 21984, 21981, 21978},
		{"Extended super antifire", 22218, 22215, 22212, 22209},
		{"Sanfew serum", 10931, 10929, 10927, 10925},
		{"Serum 207 ", 3414, 3412, 3410, 3408},
		{"Serum 208 ", 3419, 3418, 3417, 3416},
		{"Super energy", 3022, 3020, 3018, 3016},
		{"Super attack", 149, 147, 145, 2436},
		{"Super strength", 161, 159, 157, 2440},
		{"Magic potion", 3046, 3044, 3042, 3040},
		{"Ancient brew", 26346, 26344, 26342, 26340},
		{"Forgotten brew", 27638, 27635, 27632, 27629},
		{"Menaphite remedy", 27211, 27208, 27205, 27202},
		{"Moonlight potion", 29083, 29082, 29081, 29080},
		{"Goading potion", 30146, 30143, 30140, 30137},
		{"Zamorak brew", 193, 191, 189, 2450},
		{"Bastion potion", 22470, 22467, 22464, 22461},
		{"Battlemage potion", 22458, 22455, 22452, 22449},
		{"Blighted super restore", 24605, 24603, 24601, 24598},
		{"Guthix rest", 4423, 4421, 4419, 4417},
		{"Overload ", 11733, 11732, 11731, 11730},
	};

	/** Mixes and other two-dose families, which have no 3- or 4-dose container at all. */
	private static final Object[][] TWO_DOSE = {
		{"Prayer mix", 11467, 11465},
		{"Super restore mix", 11495, 11493},
		{"Antidote+ mix", 11503, 11501},
	};

	private static final Map<Integer, String> NAME_MAP = new HashMap<>();
	private static final IntFunction<String> NAMES =
		id -> NAME_MAP.getOrDefault(id, "Item #" + id);

	static
	{
		for (Object[] family : FOUR_DOSE)
		{
			name(family, 4);
		}
		for (Object[] family : TWO_DOSE)
		{
			name(family, 2);
		}
	}

	private static void name(Object[] family, int largest)
	{
		for (int dose = 1; dose <= largest; dose++)
		{
			NAME_MAP.put((Integer) family[dose], family[0] + "(" + dose + ")");
		}
	}

	/**
	 * A 3-dose drops and two of its doses are drunk, charged under the 4-dose container the way
	 * {@code SupplyTracker} stores it. One dose is left, so Drops must read a 1-dose — a container
	 * that never dropped — and the supply row must go entirely.
	 */
	@Test
	public void everyFourDoseFamilyCancelsAcrossVariantsAndRepacksTheRemainder()
	{
		for (Object[] family : FOUR_DOSE)
		{
			final String base = ((String) family[0]).trim();
			final int one = (Integer) family[1];
			final int three = (Integer) family[3];
			final int four = (Integer) family[4];

			final Map<Integer, SupplyEntry> supplies = new LinkedHashMap<>();
			supplies.put(four, new SupplyEntry(2, 200L, true));
			final TaskNetting.Result result = TaskNetting.apply(
				Map.of(three, 1), Map.of(three, 300L), supplies, NAMES);

			assertFalse(base, result.getLootQuantities().containsKey(three));
			assertEquals(base, Integer.valueOf(1), result.getLootQuantities().get(one));
			assertEquals(base, Long.valueOf(100L), result.getLootValues().get(one));
			assertFalse(base, result.getSupplies().containsKey(four));
		}
	}

	/** The same one container down: a 2-dose drops and a single dose is drunk. */
	@Test
	public void everyTwoDoseFamilyCancelsAndRepacksTheRemainder()
	{
		for (Object[] family : TWO_DOSE)
		{
			final String base = ((String) family[0]).trim();
			final int one = (Integer) family[1];
			final int two = (Integer) family[2];

			final Map<Integer, SupplyEntry> supplies = new LinkedHashMap<>();
			supplies.put(two, new SupplyEntry(1, 100L, true));
			final TaskNetting.Result result = TaskNetting.apply(
				Map.of(two, 1), Map.of(two, 200L), supplies, NAMES);

			assertEquals(base, Integer.valueOf(1), result.getLootQuantities().get(one));
			assertEquals(base, Long.valueOf(100L), result.getLootValues().get(one));
			assertFalse(base, result.getSupplies().containsKey(two));
		}
	}

	/**
	 * Families whose names are prefixes of one another must never pool. Both pairs are real: the
	 * antidotes differ by a single trailing character, and the serums only by the number carried
	 * inside the base name.
	 */
	@Test
	public void familiesWithPrefixNamesNeverCancelAgainstEachOther()
	{
		assertNoCrossCancel("Antidote+", "Antidote++");
		assertNoCrossCancel("Antidote++", "Antidote+");
		assertNoCrossCancel("Serum 207", "Serum 208");
	}

	private static void assertNoCrossCancel(String droppedBase, String drunkBase)
	{
		final int dropped = (Integer) family(droppedBase)[4];
		final int drunk = (Integer) family(drunkBase)[4];

		final Map<Integer, SupplyEntry> supplies = new LinkedHashMap<>();
		supplies.put(drunk, new SupplyEntry(4, 400L, true));
		final TaskNetting.Result result = TaskNetting.apply(
			Map.of(dropped, 1), Map.of(dropped, 400L), supplies, NAMES);

		final String message = droppedBase + " dropped against " + drunkBase + " drunk";
		assertEquals(message, Integer.valueOf(1), result.getLootQuantities().get(dropped));
		assertEquals(message, 4, result.getSupplies().get(drunk).getQuantity());
	}

	private static Object[] family(String base)
	{
		for (Object[] candidate : FOUR_DOSE)
		{
			if (base.equals(((String) candidate[0]).trim()))
			{
				return candidate;
			}
		}
		throw new AssertionError("no such family: " + base);
	}
}
