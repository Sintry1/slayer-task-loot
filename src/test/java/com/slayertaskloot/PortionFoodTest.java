package com.slayertaskloot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntFunction;
import org.junit.Test;

/**
 * Food eaten in halves and slices, which is counted in portions for the same reason a potion is
 * counted in doses.
 *
 * <p>The bug this covers was reported from an Araxxor task: three wild pies eaten, and the panel
 * showed the three pies that dropped at their full price in Drops with "Half a wild pie x3" at
 * 0 gp in Supplies. Both halves of that come from counting the pie in items. Eating a whole pie
 * is one pie out and one half in — equal item counts, which the transformation guard reads as a
 * trident being recharged rather than as food being eaten, so the bite isn't billed at all; the
 * last bite then bills the untradeable half at the only price it has, which is nothing.
 */
public class PortionFoodTest
{
	private static final int WILD_PIE = 7208;
	private static final int HALF_A_WILD_PIE = 7210;

	private static final Map<Integer, String> NAME_MAP = new HashMap<>();
	private static final IntFunction<String> NAMES =
		id -> NAME_MAP.getOrDefault(id, "Item #" + id);

	static
	{
		NAME_MAP.put(WILD_PIE, "Wild pie");
		NAME_MAP.put(HALF_A_WILD_PIE, "Half a wild pie");
	}

	@Test
	public void piesPizzasAndCakesAreCountedInThePartsTheyAreEatenIn()
	{
		assertEquals(2, SupplyTracker.portionCount("Wild pie"));
		assertEquals(1, SupplyTracker.portionCount("Half a wild pie"));
		// The article follows the word that precedes it, and one family really does take "an".
		assertEquals(1, SupplyTracker.portionCount("Half an admiral pie"));
		assertEquals(2, SupplyTracker.portionCount("Summer pie"));
		assertEquals(2, SupplyTracker.portionCount("Plain pizza"));
		assertEquals(1, SupplyTracker.portionCount("1/2 plain pizza"));
		assertEquals(3, SupplyTracker.portionCount("Cake"));
		assertEquals(2, SupplyTracker.portionCount("2/3 cake"));
		assertEquals(1, SupplyTracker.portionCount("Slice of cake"));
		assertEquals(3, SupplyTracker.portionCount("Chocolate cake"));
		assertEquals(2, SupplyTracker.portionCount("2/3 chocolate cake"));
		assertEquals(1, SupplyTracker.portionCount("Chocolate slice"));
	}

	/** Anything eaten in one bite is counted in items, and most food is. */
	@Test
	public void foodEatenInOneBiteIsNotPortioned()
	{
		assertEquals(0, SupplyTracker.portionCount("Shark"));
		assertEquals(0, SupplyTracker.portionCount("Anglerfish"));
		assertEquals(0, SupplyTracker.portionCount("Cooked karambwan"));
		// Wide enough to catch any cake would catch this one, which is a single bite of poison.
		assertEquals(0, SupplyTracker.portionCount("Dwarven rock cake"));
		assertEquals(0, SupplyTracker.portionCount("Prayer potion(4)"));
		assertEquals(0, SupplyTracker.portionCount(""));
		assertEquals(0, SupplyTracker.portionCount(null));
	}

	/**
	 * Both halves of a pie resolve to the same family, which is what puts them in one row and
	 * lets the whole pie's price cover the bite.
	 */
	@Test
	public void bothHalvesShareTheWholeFoodAsTheirFamily()
	{
		assertEquals("wild pie", SupplyTracker.doseBaseName("Wild pie"));
		assertEquals("wild pie", SupplyTracker.doseBaseName("Half a wild pie"));
		assertEquals("cake", SupplyTracker.doseBaseName("2/3 cake"));
		assertEquals("cake", SupplyTracker.doseBaseName("Slice of cake"));
		assertEquals("chocolate cake", SupplyTracker.doseBaseName("Chocolate slice"));
		assertNull(SupplyTracker.doseBaseName("Shark"));
		// Doses are untouched by any of this.
		assertEquals("Prayer potion", SupplyTracker.doseBaseName("Prayer potion(4)"));
		assertEquals(4, SupplyTracker.unitsPerItem("Prayer potion(4)"));
		assertEquals(2, SupplyTracker.unitsPerItem("Wild pie"));
		assertEquals(0, SupplyTracker.unitsPerItem("Shark"));
	}

	/**
	 * The reported case, netted: three wild pies dropped and all three eaten leaves nothing in
	 * either column, where before it left the drops at full price and a 0 gp row beneath them.
	 */
	@Test
	public void piesEatenCancelAgainstThePiesThatDropped()
	{
		final Map<Integer, SupplyEntry> supplies = new LinkedHashMap<>();
		supplies.put(WILD_PIE, new SupplyEntry(6, 2004L, true));

		final TaskNetting.Result result = TaskNetting.apply(
			Map.of(WILD_PIE, 3), Map.of(WILD_PIE, 2004L), supplies, NAMES);

		assertFalse(result.getLootQuantities().containsKey(WILD_PIE));
		assertFalse(result.getSupplies().containsKey(WILD_PIE));
	}

	/**
	 * A pie and a half eaten out of three dropped leaves a pie and a half, expressed as the
	 * containers the player is actually holding.
	 */
	@Test
	public void theRemainderIsRepackedIntoWholePiesAndHalves()
	{
		final Map<Integer, SupplyEntry> supplies = new LinkedHashMap<>();
		supplies.put(WILD_PIE, new SupplyEntry(3, 1002L, true));

		final TaskNetting.Result result = TaskNetting.apply(
			Map.of(WILD_PIE, 3), Map.of(WILD_PIE, 2004L), supplies, NAMES);

		assertEquals(Integer.valueOf(1), result.getLootQuantities().get(WILD_PIE));
		assertEquals(Integer.valueOf(1), result.getLootQuantities().get(HALF_A_WILD_PIE));
		assertEquals(Long.valueOf(668L), result.getLootValues().get(WILD_PIE));
		assertEquals(Long.valueOf(334L), result.getLootValues().get(HALF_A_WILD_PIE));
		assertFalse(result.getSupplies().containsKey(WILD_PIE));
	}
}
