package com.slayertaskloot;

import net.runelite.api.gameval.ItemID;

/** Which ammunition a dwarf multicannon is loaded with. */
public enum CannonballType
{
	AUTOMATIC("Automatic", -1),
	CANNONBALL("Cannonball", ItemID.MCANNONBALL),
	GRANITE_CANNONBALL("Granite cannonball", ItemID.GRANITE_CANNONBALL);

	private final String label;
	private final int itemId;

	CannonballType(String label, int itemId)
	{
		this.label = label;
		this.itemId = itemId;
	}

	/** The ammunition's item id, or -1 when the type is to be inferred from chat instead. */
	int getItemId()
	{
		return itemId;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
