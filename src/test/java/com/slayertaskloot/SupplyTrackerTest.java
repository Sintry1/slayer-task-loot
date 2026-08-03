package com.slayertaskloot;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

/**
 * Covers the dose-suffix parsing that decides which items get folded into one family.
 *
 * <p>A false positive here would misprice a supply by up to 4x, so the parenthesised names
 * that <em>aren't</em> doses matter as much as the ones that are.
 */
public class SupplyTrackerTest
{
	@Test
	public void parsesDoseSuffixes()
	{
		assertEquals(4, SupplyTracker.doseCount("Prayer potion(4)"));
		assertEquals(3, SupplyTracker.doseCount("Prayer potion(3)"));
		assertEquals(1, SupplyTracker.doseCount("Super restore(1)"));
		assertEquals(4, SupplyTracker.doseCount("Saradomin brew(4)"));
		assertEquals(2, SupplyTracker.doseCount("Stamina potion(2)"));
	}

	@Test
	public void ignoresParenthesesThatAreNotDoses()
	{
		// Imbued rings, tablets and clue tiers all end in brackets without being dosed.
		assertEquals(0, SupplyTracker.doseCount("Ring of the gods (i)"));
		assertEquals(0, SupplyTracker.doseCount("Teleport to house (tablet)"));
		assertEquals(0, SupplyTracker.doseCount("Clue scroll (easy)"));
		assertEquals(0, SupplyTracker.doseCount("Prayer cape(t)"));
		assertEquals(0, SupplyTracker.doseCount("Crystal helm (inactive)"));
	}

	@Test
	public void ignoresNamesWithNoSuffix()
	{
		assertEquals(0, SupplyTracker.doseCount("Shark"));
		assertEquals(0, SupplyTracker.doseCount("Vial"));
		assertEquals(0, SupplyTracker.doseCount("Adamant bolts"));
		assertEquals(0, SupplyTracker.doseCount(""));
	}

	@Test
	public void handlesMalformedSuffixes()
	{
		// An empty or unbalanced bracket must not be read as a dose count.
		assertEquals(0, SupplyTracker.doseCount("Weird item()"));
		assertEquals(0, SupplyTracker.doseCount(")"));
		assertEquals(0, SupplyTracker.doseCount("(4)"));
		assertEquals(0, SupplyTracker.doseCount("Mixed(4a)"));
	}
}
