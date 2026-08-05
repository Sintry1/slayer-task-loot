package com.slayertaskloot;

import net.runelite.api.gameval.ItemID;

/** Which dart a toxic blowpipe is loaded with. */
public enum BlowpipeDart
{
	AUTOMATIC("Automatic", -1),
	BRONZE("Bronze dart", ItemID.BRONZE_DART),
	IRON("Iron dart", ItemID.IRON_DART),
	STEEL("Steel dart", ItemID.STEEL_DART),
	BLACK("Black dart", ItemID.BLACK_DART),
	MITHRIL("Mithril dart", ItemID.MITHRIL_DART),
	ADAMANT("Adamant dart", ItemID.ADAMANT_DART),
	RUNE("Rune dart", ItemID.RUNE_DART),
	AMETHYST("Amethyst dart", ItemID.AMETHYST_DART),
	DRAGON("Dragon dart", ItemID.DRAGON_DART);

	private final String label;
	private final int itemId;

	BlowpipeDart(String label, int itemId)
	{
		this.label = label;
		this.itemId = itemId;
	}

	/** The dart's item id, or -1 when the dart is to be inferred from chat instead. */
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
