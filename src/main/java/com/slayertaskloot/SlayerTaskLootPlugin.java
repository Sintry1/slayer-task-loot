package com.slayertaskloot;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.ChatMessageType;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.GameState;
import net.runelite.api.ItemComposition;
import net.runelite.api.MenuAction;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.WorldView;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GraphicChanged;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.ItemDespawned;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.Skill;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.DBTableID;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.QuantityFormatter;
import net.runelite.client.util.Text;

/**
 * Tracks loot and supply usage for the current slayer assignment, kept separate from the
 * Loot Tracker's lifetime totals so a task can be reviewed without resetting anything.
 *
 * <p>Deliberately standalone: slayer state is read from vanilla varps and loot from
 * {@link ServerNpcLoot}, so there is no {@code @PluginDependency} on the Slayer or Loot
 * Tracker plugins and nothing breaks when either is switched off.
 */
@Slf4j
@PluginDescriptor(
	name = "Slayer Task Loot",
	description = "Tracks loot from the current slayer task separately, with optional net profit after supplies",
	tags = {"slayer", "task", "loot", "drops", "profit", "supplies", "tracker", "gp"}
)
public class SlayerTaskLootPlugin extends Plugin
{
	private static final String KEY_ACTIVE_TASK = "activeTask";
	private static final String KEY_HISTORY = "taskHistory";
	private static final Type HISTORY_TYPE = new TypeToken<List<TaskLootRecord>>(){}.getType();

	/** Interfaces whose item movement can't be consumption, so we re-baseline instead of charging. */
	private static final int[] RESYNC_INTERFACES = {
		InterfaceID.BANKMAIN,
		InterfaceID.BANK_DEPOSITBOX,
		InterfaceID.SHOPMAIN,
		InterfaceID.TRADEMAIN,
		InterfaceID.GE_OFFERS,
		InterfaceID.GE_COLLECT,
		InterfaceID.DEATHKEEP,
		InterfaceID.GRAVESTONE_RETRIEVAL,
		InterfaceID.DEATH_OFFICE,
		InterfaceID.DEATH_COFFER,
		InterfaceID.DEATH_COFFER_SIDE,
		InterfaceID.GRAVESTONE_GENERIC,
	};
	/**
	 * Ticks the supply baseline is held after a death, counted in ticks the supply loop
	 * actually <em>processed</em> rather than in client tick numbers.
	 *
	 * <p>The distinction is the whole point. A respawn ends in a loading screen, and no
	 * {@link GameTick} is delivered while one is up — but the client's tick counter keeps
	 * climbing throughout. A deadline of {@code getTickCount() + n} therefore expired without
	 * the loop ever running inside it, and the first tick after the loading screen diffed the
	 * emptied containers against a baseline taken while the player was still holding everything.
	 * That is the whole inventory billed as supplies, which is what a death lost to a gravestone
	 * looked like in the panel.
	 */
	private static final int DEATH_SETTLE_TICKS = 15;
	/**
	 * Hard ceiling on the settling period, in ticks the supply loop processed, whatever the
	 * player's state.
	 *
	 * <p>{@link #DEATH_SETTLE_TICKS} only counts down while the player reads as alive, which is
	 * a condition that in principle may never arrive — and a hold that never ends is a supply
	 * total silently stuck at zero for the rest of the session, the loudest-costing and
	 * quietest-looking failure this plugin has. Generous enough never to cut a real death's
	 * settling short at ~1 minute of ticks, so reaching it means something else is wrong.
	 */
	private static final int DEATH_SETTLE_MAX_TICKS = 100;
	/** "You are dead" as the game says it — a second arm for the resync alongside ActorDeath. */
	private static final String DEATH_MESSAGE = "Oh dear, you are dead!";

	/** Task id meaning "a boss", from [proc,helper_slayer_current_assignment]. */
	private static final int BOSS_TASK_ID = 98;

	/**
	 * Largest counter drop still counted as kills when an assignment ends. Generous enough to
	 * cover a barrage stack finishing the task, small enough that cancelling with a long way
	 * to go isn't mistaken for combat.
	 */
	private static final int MAX_FINISHING_KILLS = 12;

	/**
	 * Slack in ticks when lining a death or a drop up against a slayer counter change. They
	 * arrive in the same tick in an order the client doesn't control, so an exact match would
	 * be a coin flip.
	 */
	private static final int CREDIT_MATCH_TOLERANCE = 2;
	/** A ground-item click may take several ticks to reach while the player walks to it. */
	private static final int PICKUP_ACTION_EXPIRY_TICKS = 20;

	/** Persist at most this often, in game ticks, so a long task isn't a write per kill. */
	/**
	 * Hard ceiling on the grace replay, whatever combat says — ten minutes.
	 *
	 * <p>Generous because what it bounds is narrow: the replay only reaches back to the first
	 * damage dealt to a <em>task-relevant</em> NPC since the last session ended, and supplies
	 * spent fighting this assignment's monsters belong to this assignment however long it took.
	 * Gathering a barrage stack is the case that needs the room.
	 */
	private static final int MAX_GRACE_TICKS = 1000;

	/**
	 * How far before a hitsplat the engagement is taken to have started.
	 *
	 * <p>A hitsplat is the <em>effect</em>; the supplies were spent making the attack that caused
	 * it, a tick or more earlier while the projectile was still in the air. Anchoring on the
	 * hitsplat alone therefore excludes the very thing it is meant to cover, and does so exactly
	 * once — the first thrown knife of a trip was billed on the tick it left the inventory and
	 * pruned by an anchor set two ticks later. A cannon doesn't show this because its magazine
	 * falls on the same tick the shot is billed.
	 */
	private static final int COMBAT_LEAD_TICKS = 5;

	/** Largest magazine fall still read as shots fired. Above it, an unload or a pickup. */
	private static final int MAX_CANNON_SHOTS_PER_TICK = 2;

	private static final int PERSIST_INTERVAL_TICKS = 10;

	/** Refresh the panel at most this often. Supply charges fire far too fast to rebuild on each. */
	private static final int PANEL_REFRESH_INTERVAL_TICKS = 5;
	private static final Pattern BLOWPIPE_DARTS_PATTERN = Pattern.compile(
		"Darts: (.+) x [0-9,]+\\. Scales: .+\\.");
	private static final Pattern CANNON_LOAD_PATTERN = Pattern.compile(
		"(?i).*load(?:ed)?(?: the cannon)? with ([0-9,]+) (granite )?cannonballs?.*");
	private static final Pattern CANNON_UNLOAD_PATTERN = Pattern.compile(
		"You unload your cannon and receive (Granite cannonball|Cannonball).*");

	private static final int BLOWPIPE_ATTACK_ANIMATION = 5061;
	private static final int TENTACLE_ATTACK_ANIMATION = AnimationID.SLAYER_ABYSSAL_WHIP_ATTACK;
	private static final int VENATOR_ATTACK_ANIMATION = AnimationID.HUMAN_WEAPON_BOW_VENATOR01_SHOOT;

	/** Attacks a whip is worth once a kraken tentacle is attached to it. */
	private static final int TENTACLE_CHARGES = 10_000;

	/**
	 * Percentage of darts a blowpipe shot actually uses up, by ammunition-saving device. Ava's
	 * devices recover blowpipe darts at 60%, 72% and 80% respectively, so what's spent is the
	 * remainder. A quiver upgraded at Ava is treated as an assembler; the varbit says the
	 * upgrade exists but not which device paid for it, and the assembler is the common answer.
	 */
	/**
	 * Ranged weapons a charged quiver spends nothing on: those that pay for their shots from their
	 * own charges, and those whose ammunition the quiver can't store in the first place.
	 */
	private static final Set<Integer> QUIVER_EXEMPT_WEAPONS = new HashSet<>(Arrays.asList(
		ItemID.VENATOR_BOW, ItemID.VENATOR_BOW_ORNAMENT,
		ItemID.TOXIC_BLOWPIPE_LOADED, ItemID.TOXIC_BLOWPIPE_LOADED_ORNAMENT,
		ItemID.BOW_OF_FAERDHINEN, ItemID.BR_BOW_OF_FAERDHINEN,
		ItemID.TONALZTICS_OF_RALOS_CHARGED, ItemID.TONALZTICS_OF_RALOS_UNCHARGED));

	/**
	 * Weapon categories that spend quiver charges: bow and crossbow.
	 *
	 * <p>The effect applies to "arrows and bolts shot from either ammunition slot", so thrown
	 * weapons are out — a knife, dart or blisterwood stake is the weapon and its own ammunition, not
	 * an arrow held in a slot, and the quiver takes nothing for throwing one. Category 19 was
	 * eligible here at first and billed 19 stakes as if they were arrows.
	 */
	private static final Set<Integer> QUIVER_AMMO_CATEGORIES = new HashSet<>(Arrays.asList(3, 5));

	/**
	 * Ranged attack animations, for counting shots.
	 *
	 * <p>Whitelisted rather than taking any animation at all, because being attacked is an animation
	 * too: a bow shot is 426 and the defence animation between shots is 424, and counting both put
	 * the shot count 19% over on a sixteen-shot trip. A weapon whose animation is missing from here
	 * is logged rather than silently skipped, so the gap shows up as a line in the log instead of as
	 * an undercount nobody can explain.
	 */
	private static final Set<Integer> RANGED_ATTACK_ANIMATIONS = new HashSet<>(Arrays.asList(
		// Confirmed in game: a bow and a crossbow.
		AnimationID.HUMAN_BOW,
		AnimationID.XBOWS_HUMAN_FIRE_AND_RELOAD_PVN,
		// The rest of each family. Unconfirmed, but every one is an attack animation, so a wrong
		// guess costs nothing — it is an id that never appears. Missing ones announce themselves in
		// the log. Thrown animations are absent on purpose: those weapons aren't eligible.
		AnimationID.XBOWS_HUMAN_FIRE_AND_RELOAD,
		AnimationID.BALLISTA_ATTACK,
		AnimationID.HUMAN_CROSSBOW));

	private static final int EYE_OF_AYAK_ANIMATION = 12397;

	/**
	 * Elemental tomes, as {tome item id, page item id, spell graphics...}.
	 *
	 * <p>A tome is a shield and has no animation of its own, so a cast is recognised by the spell's
	 * own graphic appearing on the player while the tome is worn — one graphic per spell tier. All
	 * three work identically: 20 charges to a page, one charge to a cast. The fire tome's page is
	 * taken from config instead of this table, since it accepts two.
	 */
	private static final int[][] ELEMENTAL_TOMES = {
		{ItemID.TOME_OF_FIRE, -1, 99, 126, 129, 155, 1464},
		{ItemID.TOME_OF_WATER, ItemID.SOAKED_PAGE, 93, 120, 135, 161, 1458},
		{ItemID.TOME_OF_EARTH, ItemID.SOILED_PAGE, 96, 123, 138, 164, 1461},
	};

	/** Shots per splinter: whether a quiver spends one is a 1-in-3 roll. */
	private static final int SHOTS_PER_SPLINTER = 3;

	/** Scales spent per three attacks: the blowpipe has a 1-in-3 chance to spend none. */
	private static final int SCALES_PER_THREE_SHOTS = 2;

	/**
	 * Attack animations a powered staff casts with. Two of them, because the older staves kept the
	 * generic high-level magic animation when the newer ones were given their own.
	 */
	private static final int POWERED_STAFF_ANIMATION = 1167;
	private static final int POWERED_STAFF_ANIMATION_ALT = 11430;

	/**
	 * What one cast from each powered staff consumes, as component itemId/quantity pairs.
	 *
	 * <p>Per staff rather than per resource, because the same resource is spent at different rates
	 * by different items: the swamp trident pays in Zulrah's scales exactly where the seas trident
	 * pays in coins. Tumeken's shadow is absent because it has its own animation, handled with the
	 * scythe.
	 */
	private static final Map<Integer, int[]> POWERED_STAFF_COSTS = new HashMap<>();

	static
	{
		final int[] seas = {ItemID.CHAOSRUNE, 1, ItemID.DEATHRUNE, 1, ItemID.FIRERUNE, 5, ItemID.COINS, 10};
		final int[] swamp = {ItemID.CHAOSRUNE, 1, ItemID.DEATHRUNE, 1, ItemID.FIRERUNE, 5,
			ItemID.SNAKEBOSS_SCALE, 1};
		for (int id : new int[]{ItemID.TOTS, ItemID.TOTS_CHARGED, ItemID.TOTS_ORN,
			ItemID.TOTS_CHARGED_ORN, ItemID.TOTS_I_CHARGED, ItemID.TOTS_I_CHARGED_ORN})
		{
			POWERED_STAFF_COSTS.put(id, seas);
		}
		for (int id : new int[]{ItemID.TOXIC_TOTS_CHARGED, ItemID.TOXIC_TOTS_CHARGED_ORN,
			ItemID.TOXIC_TOTS_I_CHARGED, ItemID.TOXIC_TOTS_I_CHARGED_ORN})
		{
			POWERED_STAFF_COSTS.put(id, swamp);
		}
		// Two blood runes a cast, not three. The wiki's 20,000 charges from 40,000 runes is the
		// authority here; Supplies Tracker bills three.
		POWERED_STAFF_COSTS.put(ItemID.SANGUINESTI_STAFF, new int[]{ItemID.BLOODRUNE, 2});
		POWERED_STAFF_COSTS.put(ItemID.WARPED_SCEPTRE, new int[]{ItemID.CHAOSRUNE, 2, ItemID.EARTHRUNE, 5});
	}

	private static final int LOSS_WITH_NO_DEVICE = 100;
	private static final int LOSS_WITH_ATTRACTOR = 40;
	private static final int LOSS_WITH_ACCUMULATOR = 28;
	private static final int LOSS_WITH_ASSEMBLER = 20;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ItemManager itemManager;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ConfigManager configManager;

	@Inject
	private SlayerTaskLootConfig config;

	@Inject
	private SupplyTracker supplyTracker;

	@Inject
	private SkillIconManager skillIconManager;

	@Inject
	private Gson gson;

	private SlayerTaskLootPanel panel;
	private NavigationButton navButton;

	private TaskLootRecord activeTask;
	private List<TaskLootRecord> history = new ArrayList<>();

	/** Last observed slayer counter value. -1 until the first read of this login. */
	private int lastSlayerCount = -1;

	/**
	 * NPC names established as targets of the current assignment, learned by seeing them die
	 * on a tick the slayer counter moved. Lets a drop that arrives long after the kill still
	 * be recognised, without a hardcoded task-to-monster table.
	 */
	private final Set<String> confirmedTargets = new HashSet<>();

	/** Distinct recent NPC deaths, keyed by NPC index so barrage kills never collapse together. */
	private final Map<Integer, RecentDeath> recentDeaths = new HashMap<>();
	private final Map<Integer, Integer> creditedNpcDeathTicks = new HashMap<>();

	/** Loot awaiting attribution, held one tick so the counter change has time to land. */
	private final Deque<PendingLoot> pendingLoot = new ArrayDeque<>();
	private final Deque<PendingCollectedDrop> pendingCollectedDrops = new ArrayDeque<>();
	private final Deque<InventoryGain> pendingInventoryGains = new ArrayDeque<>();
	private final Deque<PendingPickup> pendingPickups = new ArrayDeque<>();
	private final Map<Integer, Integer> pickupInventoryIgnores = new HashMap<>();
	private final Map<Integer, Integer> alchemyCollectionIgnores = new HashMap<>();
	private Map<Integer, Integer> collectionInventorySnapshot;
	private boolean collectionInventoryDirty;

	/** Supply charges made while no session was open, replayed if one opens within the grace window. */
	private final Deque<BufferedCharge> graceBuffer = new ArrayDeque<>();

	// Memoised slayer DB resolution, keyed on the varps it derives from. See resolveTaskName().
	private int cachedTaskId = -1;
	private int cachedBossId = -1;
	private String cachedTaskName;
	private int cachedAreaId = Integer.MIN_VALUE;
	private String cachedTaskLocation;

	/** Rendered history, rebuilt only when history changes rather than on every refresh. */
	private List<TaskView> historyViews = Collections.emptyList();
	private boolean historyViewsDirty = true;

	/**
	 * Whether the stored snapshot has already been read for the account currently logged in.
	 *
	 * <p>{@link GameState#LOGGED_IN} is not the login event it reads as: it fires again after
	 * every loading screen — a teleport, a dungeon entrance, a region boundary. Reloading there
	 * replaced the live record with the last persisted copy and closed its sessions underneath
	 * a plugin that still believed one was open, which stopped the clock for good.
	 */
	private boolean loaded;

	private boolean sessionOpen;
	private String activeSessionId;
	private String lastCreditSessionId;
	private int lastCreditTick = -1;

	/** Tick the counter reached zero, or -1. Keeps a finished task open for its late loot. */
	private int taskEndTick = -1;
	private boolean supplyResyncPending;
	/** Ticks of settling left after a death, or -1 when no death is being waited out. */
	private int deathSettleTicks = -1;
	/** Ticks the current settling period may still be held for at most. See DEATH_SETTLE_MAX_TICKS. */
	private int deathSettleTicksRemaining;
	private boolean persistDirty;
	private boolean panelDirty;
	private int lastWeaponUsageTick = -1;
	private int lastWeaponUsageAnimation = -1;
	private long scytheVialRemainder;
	private int loadedBlowpipeDartId = -1;
	private int blowpipeDartRemainder;
	private int blowpipeScaleRemainder;
	private int quiverShotTick = -1;
	private int quiverSplinterRemainder;
	private final Map<Integer, Long> tomeRemainders = new HashMap<>();
	private int specialEnergyLastSeen = -1;
	private long tentacleRemainder;

	/**
	 * First tick the player damaged a task-relevant NPC since the grace buffer was last emptied,
	 * or -1 if they haven't yet.
	 *
	 * <p>Deliberately not "the current unbroken fight". Gathering a stack to barrage is combat
	 * with gaps in it — run, aggro, run — and anchoring on the latest streak collapsed the window
	 * back to the configured default every time the player moved, which is what lost a trip's
	 * worth of thrown knives. There is nothing to guess here either: the anchor is the first blow
	 * of the engagement, not a threshold for when one fight becomes two.
	 */
	private int combatStartTick = -1;

	/** Previous magazine reading, so a cannon firing can be seen. -1 until the first tick. */
	private int lastCannonballCount = -1;

	@Provides
	SlayerTaskLootConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(SlayerTaskLootConfig.class);
	}

	@Override
	protected void startUp()
	{
		final String formattedExclusions = DropExclusions.formatPerTask(config.taskExcludedDrops());
		if (!formattedExclusions.equals(config.taskExcludedDrops()))
		{
			configManager.setConfiguration(SlayerTaskLootConfig.GROUP, "taskExcludedDrops",
				formattedExclusions);
		}
		panel = new SlayerTaskLootPanel(this, config, itemManager);
		navButton = NavigationButton.builder()
			.tooltip("Slayer Task Loot")
			.icon(buildNavIcon())
			.priority(7)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		// Enabling the plugin mid-session means no LOGGED_IN event is coming, so load now.
		clientThread.invokeLater(() ->
		{
			if (client.getGameState() == GameState.LOGGED_IN)
			{
				load();
				loaded = true;
				applyChargeConfig();
				supplyTracker.resync();
				resyncCollectionInventory();
				updateTask();
			}
		});
	}

	@Override
	protected void shutDown()
	{
		// Synchronously, and before anything is cleared. Deferring this to the client thread
		// would run it after the fields below are nulled and persist an empty task, throwing
		// away an assignment in progress every time the plugin is toggled off. Writing RS
		// profile config off the client thread is safe — it uses a cached profile key.
		closeSession("PLUGIN_STOPPED");
		persist();

		clientToolbar.removeNavigation(navButton);
		panel = null;
		navButton = null;
		activeTask = null;
		history = new ArrayList<>();
		lastSlayerCount = -1;
		lastCreditTick = -1;
		loaded = false;
		sessionOpen = false;
		activeSessionId = null;
		lastCreditSessionId = null;
		persistDirty = false;
		panelDirty = false;
		taskEndTick = -1;
		deathSettleTicks = -1;
		confirmedTargets.clear();
		recentDeaths.clear();
		creditedNpcDeathTicks.clear();
		pendingLoot.clear();
		pendingCollectedDrops.clear();
		pendingInventoryGains.clear();
		pendingPickups.clear();
		pickupInventoryIgnores.clear();
		alchemyCollectionIgnores.clear();
		collectionInventorySnapshot = null;
		collectionInventoryDirty = false;
		clearGraceBuffer();
		quiverShotTick = -1;
		specialEnergyLastSeen = -1;
		lastCannonballCount = -1;
		supplyTracker.clear();
	}

	// ------------------------------------------------------------------
	// Slayer task state
	// ------------------------------------------------------------------

	@SuppressWarnings("unused")
	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		final int varpId = event.getVarpId();
		if (varpId == VarPlayerID.SLAYER_COUNT
			|| varpId == VarPlayerID.SLAYER_TARGET
			|| varpId == VarPlayerID.SLAYER_AREA
			|| varpId == VarPlayerID.SLAYER_COUNT_ORIGINAL
			|| event.getVarbitId() == VarbitID.SLAYER_TARGET_BOSSID)
		{
			updateTask();
		}
	}

	/**
	 * Reconciles our view of the assignment against the game's varps. Mirrors the approach
	 * in the built-in Slayer plugin, which is the only place this resolution is documented.
	 */
	private void updateTask()
	{
		final int amount = client.getVarpValue(VarPlayerID.SLAYER_COUNT);

		if (amount <= 0)
		{
			// Completion is a transition from a live counter to zero, never the first thing
			// seen. Without that distinction a counter still reading zero at login would end a
			// task that is merely being loaded, and archive it on the next varp.
			if (lastSlayerCount < 0)
			{
				return;
			}

			if (activeTask != null && !activeTask.isCompleted())
			{
				// Credit the kill that finished the assignment, then stop. Archiving waits
				// for the tick loop, because that final drop is still pending attribution.
				creditTaskEnd();
				activeTask.setEndedAt(System.currentTimeMillis());
				taskEndTick = client.getTickCount();
				closeSession();
				markDirty();
			}
			lastSlayerCount = amount;
			return;
		}

		final String taskName = resolveTaskName();
		if (taskName == null)
		{
			// DB rows aren't populated yet. Leave lastSlayerCount alone so no counter change
			// is missed; a later varp change brings us back here.
			return;
		}

		final String taskLocation = resolveTaskLocation();
		final int initialAmount = client.getVarpValue(VarPlayerID.SLAYER_COUNT_ORIGINAL);
		if (initialAmount <= 0)
		{
			// Not populated yet, the same situation as an unresolved task name above. It reads
			// zero for a moment at login, after the counter itself is already live, and taking
			// that at face value was destructive rather than merely wrong: the size is part of
			// assignment identity, so a stored task compared against zero looked like a
			// different assignment, and startTask() archived the real record and replaced it
			// with an empty one. Seen in the log as a pair of lines a fraction of a second
			// apart — "Started tracking task: 0x Smoke Devils" then "112x Smoke Devils".
			return;
		}

		// Identity has to be settled before any kill is credited. A new assignment whose size
		// is smaller than what was left on the previous one — a Turael skip — otherwise looks
		// like the counter dropping by dozens, and would bank that as a burst of kills against
		// the task being replaced.
		final boolean sameAssignment = activeTask != null
			&& !activeTask.isCompleted()
			&& Objects.equals(taskName, activeTask.getTaskName())
			&& Objects.equals(taskLocation, activeTask.getTaskLocation())
			&& initialAmount == activeTask.getInitialAmount()
			// A counter that rises is always a fresh assignment; it never climbs within one.
			// This catches back-to-back identical tasks of identical size, which every other
			// field above would match. Skipped on the first read after login, where there is
			// no baseline and the stored task should simply resume.
			&& !(lastSlayerCount >= 0 && amount > lastSlayerCount);

		if (!sameAssignment)
		{
			startTask(taskName, taskLocation, initialAmount);
		}
		else
		{
			creditIfKill(amount);
		}

		lastSlayerCount = amount;
	}

	/**
	 * Credits kills for a mid-task drop in the slayer counter — the one hard signal this
	 * plugin rests on, since the counter only moves for kills that counted toward the task.
	 *
	 * <p>Deliberately uncapped. Barraging a stack in the Catacombs can take the counter down
	 * by nine in a single change, and rejecting that as implausible would throw away both the
	 * kills and every drop that came with them. A drop landing on a value above zero is always
	 * kills: reassignment is caught by the identity check before this runs, and cancelling
	 * always lands on zero, never on a positive number.
	 */
	private void creditIfKill(int amount)
	{
		if (lastSlayerCount <= 0 || amount >= lastSlayerCount)
		{
			return;
		}

		creditCounterMovement(lastSlayerCount - amount);
	}

	/**
	 * Handles the counter reaching zero, which is either the finishing kill or a cancelled
	 * assignment. The counter alone can't tell them apart, so the two consequences are
	 * separated rather than gambling on one answer:
	 *
	 * <ul>
	 *   <li><b>Loot</b> is always credited for the tick. Getting this wrong on a completion
	 *   would lose the finishing drop, and it costs nothing on a cancellation — you're stood
	 *   at a slayer master, where nothing is dropping.</li>
	 *   <li><b>The kill count</b> only moves when the drop is small enough to be kills, so
	 *   cancelling with forty left doesn't book forty kills against an abandoned task.</li>
	 * </ul>
	 */
	private void creditTaskEnd()
	{
		if (lastSlayerCount <= 0)
		{
			return;
		}

		if (lastSlayerCount <= MAX_FINISHING_KILLS)
		{
			creditCounterMovement(lastSlayerCount);
		}
		else
		{
			log.debug("Counter dropped {} straight to zero — cancelled, not counted as kills",
				lastSlayerCount);
		}
	}

	/**
	 * Resolves the assignment name, memoised on the varps it derives from.
	 *
	 * <p>The cache is the point, not an optimisation: this runs from the {@code SLAYER_COUNT}
	 * handler, so without it every single kill would trigger a fresh scan of the slayer task
	 * table. Barraging a stack multiplies that by the number of NPCs dying at once, which is
	 * exactly how a plugin ends up stuttering on multi-kills.
	 */
	@Nullable
	private String resolveTaskName()
	{
		final int taskId = client.getVarpValue(VarPlayerID.SLAYER_TARGET);
		final int bossId = client.getVarbitValue(VarbitID.SLAYER_TARGET_BOSSID);

		if (taskId == cachedTaskId && bossId == cachedBossId && cachedTaskName != null)
		{
			return cachedTaskName;
		}

		final String resolved = lookUpTaskName(taskId, bossId);
		if (resolved != null)
		{
			// Only a hit is cached. A miss means the DB rows aren't populated yet, and caching
			// that would pin the failure in place instead of retrying on the next varp change.
			cachedTaskId = taskId;
			cachedBossId = bossId;
			cachedTaskName = resolved;
		}
		return resolved;
	}

	@Nullable
	private String lookUpTaskName(int taskId, int bossId)
	{
		int taskRow;
		if (taskId == BOSS_TASK_ID)
		{
			final List<Integer> bossRows = client.getDBRowsByValue(
				DBTableID.SlayerTaskSublist.ID,
				DBTableID.SlayerTaskSublist.COL_TASK_SUBTABLE_ID,
				0,
				bossId);
			if (bossRows.isEmpty())
			{
				return null;
			}
			taskRow = (Integer) client.getDBTableField(bossRows.get(0), DBTableID.SlayerTaskSublist.COL_TASK, 0)[0];
		}
		else
		{
			final List<Integer> taskRows = client.getDBRowsByValue(
				DBTableID.SlayerTask.ID, DBTableID.SlayerTask.COL_ID, 0, taskId);
			if (taskRows.isEmpty())
			{
				return null;
			}
			taskRow = taskRows.get(0);
		}

		final String name = (String) client.getDBTableField(taskRow, DBTableID.SlayerTask.COL_NAME_UPPERCASE, 0)[0];
		return name == null || name.isEmpty()
			? null
			// Only the first character is touched. Lowercasing the rest would mangle names
			// that carry their own capitalisation, e.g. "TzHaar-Ket" or "Kree'arra".
			: name.substring(0, 1).toUpperCase() + name.substring(1);
	}

	/** Memoised for the same reason as {@link #resolveTaskName()} — it runs per kill too. */
	@Nullable
	private String resolveTaskLocation()
	{
		final int areaId = client.getVarpValue(VarPlayerID.SLAYER_AREA);

		if (areaId == cachedAreaId)
		{
			return cachedTaskLocation;
		}

		if (areaId <= 0)
		{
			// A master that assigns no area. Safe to cache: there is no lookup to retry.
			cachedAreaId = areaId;
			cachedTaskLocation = null;
			return null;
		}

		final List<Integer> areaRows = client.getDBRowsByValue(
			DBTableID.SlayerArea.ID, DBTableID.SlayerArea.COL_AREA_ID, 0, areaId);
		if (areaRows.isEmpty())
		{
			return null;
		}

		final String resolved = (String) client.getDBTableField(
			areaRows.get(0), DBTableID.SlayerArea.COL_AREA_NAME_IN_HELPER, 0)[0];

		if (resolved != null)
		{
			cachedAreaId = areaId;
			cachedTaskLocation = resolved;
		}
		return resolved;
	}

	private void startTask(String taskName, @Nullable String taskLocation, int initialAmount)
	{
		archiveActiveTask();

		activeTask = new TaskLootRecord(taskName, taskLocation, initialAmount, System.currentTimeMillis());
		sessionOpen = false;
		activeSessionId = null;
		lastCreditSessionId = null;
		lastCreditTick = -1;
		clearGraceBuffer();
		// Anything still awaiting attribution belonged to the assignment just archived, and
		// must not land on this one.
		pendingLoot.clear();
		pendingCollectedDrops.clear();
		pendingInventoryGains.clear();
		pendingPickups.clear();
		pickupInventoryIgnores.clear();
		alchemyCollectionIgnores.clear();
		resyncCollectionInventory();
		lastCreditTick = -1;
		taskEndTick = -1;
		// Targets are per assignment: what counted for the last task says nothing about this one.
		confirmedTargets.clear();
		recentDeaths.clear();
		creditedNpcDeathTicks.clear();
		supplyTracker.resync();
		markDirty();

		log.debug("Started tracking task: {}x {} at {}", initialAmount, taskName, taskLocation);
		pushToPanel();
	}

	/** Moves the finished assignment into history, trimming to the configured size. */
	private void archiveActiveTask()
	{
		if (activeTask == null)
		{
			return;
		}

		if (activeTask.getEndedAt() == 0)
		{
			// Abandoned rather than completed — a new task was handed out first.
			activeTask.setEndedAt(System.currentTimeMillis());
		}

		final boolean worthKeeping = !activeTask.getItems().isEmpty()
			|| activeTask.getKills() > 0
			|| activeTask.getSupplyCost() > 0;

		if (worthKeeping)
		{
			history.add(0, activeTask);
			historyViewsDirty = true;
		}

		trimHistory();
		closeSession("ASSIGNMENT_CHANGED");
		activeTask = null;
		taskEndTick = -1;
		markDirty();
	}

	private void trimHistory()
	{
		final int max = config.historySize();
		while (history.size() > max)
		{
			history.remove(history.size() - 1);
			historyViewsDirty = true;
		}
	}

	// ------------------------------------------------------------------
	// Kill credit and sessions
	// ------------------------------------------------------------------

	/**
	 * Banks counter movement as kills, then tries to attribute it to deaths the client saw.
	 *
	 * <p>The order matters. The counter is exact — it moves once per kill toward the assignment
	 * and for nothing else — so the kill is recorded first and unconditionally. Matching it to a
	 * {@link RecentDeath} is a separate, best-effort question about <em>which session</em> the
	 * kill and its drops belong to, and it is allowed to fail: an uncredited death token survives
	 * only {@link #CREDIT_MATCH_TOLERANCE} ticks, so a despawn observed a few ticks either side of
	 * the varp change finds nothing to pair with.
	 *
	 * <p>Previously the attribution decided both, and a failed match discarded the kill outright
	 * with no retry and no reconciliation against the counter — a task the game reported as 311
	 * kills showed 253.
	 */
	private void creditCounterMovement(int progress)
	{
		lastCreditTick = client.getTickCount();
		log.debug("Slayer assignment progressed by {}", progress);
		if (activeTask != null)
		{
			activeTask.addCounterKills(progress);
			markDirty();
		}
		confirmRecentDeaths(lastCreditTick, progress);
	}

	/**
	 * Opens an on-task session, replaying any supply spend from the grace window so that
	 * teleports out and pre-fight boosts land on the task rather than being discarded.
	 */
	private void openSession()
	{
		if (sessionOpen)
		{
			return;
		}

		sessionOpen = true;
		activeSessionId = activeTask.openNewSession(System.currentTimeMillis(), false).getSessionId();

		log.debug("Session {} open, replayed {} gp of grace-window supplies",
			activeTask.getSessions(), replayGraceBuffer(activeSessionId));
	}

	private void closeSession()
	{
		closeSession("CLOSED");
	}

	private void closeSession(String reason)
	{
		if (sessionOpen && activeTask != null && activeSessionId != null)
		{
			activeTask.closeSession(activeSessionId, System.currentTimeMillis(), reason);
		}
		sessionOpen = false;
		activeSessionId = null;
		clearGraceBuffer();
	}

	// ------------------------------------------------------------------
	// Loot
	// ------------------------------------------------------------------

	@SuppressWarnings("unused")
	@Subscribe
	public void onServerNpcLoot(ServerNpcLoot event)
	{
		if (activeTask == null)
		{
			return;
		}

		final NPCComposition composition = event.getComposition();
		final String name = composition.getName() == null
			? "Unknown"
			: Text.removeTags(composition.getName());

		// LootManager reuses and clears the backing list immediately after posting this event.
		// Always take our own copy before buffering it for end-of-tick attribution.
		pendingLoot.add(new PendingLoot(
			client.getTickCount(), -1, name, event.getItems(), LootSource.SERVER, activeSessionId));
	}

	@SuppressWarnings("unused")
	@Subscribe
	public void onNpcLootReceived(NpcLootReceived event)
	{
		if (activeTask == null || event.getNpc() == null)
		{
			return;
		}

		final String name = event.getNpc().getName() == null
			? "Unknown"
			: Text.removeTags(event.getNpc().getName());
		pendingLoot.add(new PendingLoot(
			client.getTickCount(), event.getNpc().getIndex(), name,
			event.getItems(), LootSource.GROUND, activeSessionId));
	}

	/**
	 * Attributes buffered loot once its tick has fully elapsed. Waiting a tick means the
	 * order in which the loot and the counter change arrive within a tick doesn't matter.
	 */
	private void resolvePendingLoot()
	{
		final int now = client.getTickCount();
		final Set<PendingLoot> resolved = new HashSet<>();

		for (PendingLoot loot : pendingLoot)
		{
			if (resolved.contains(loot)
				|| loot.tick > now - CREDIT_MATCH_TOLERANCE)
			{
				continue;
			}

			PendingLoot counterpart = null;
			for (PendingLoot candidate : pendingLoot)
			{
				if (candidate != loot
					&& !resolved.contains(candidate)
					&& candidate.source != loot.source
					&& Math.abs(candidate.tick - loot.tick) <= CREDIT_MATCH_TOLERANCE
					&& loot.npcName.equals(candidate.npcName))
				{
					counterpart = candidate;
					break;
				}
			}

			// Pair one report from each RuneLite source by NPC and timing, even when the
			// two APIs disagree about the item payload. Matching remains one-to-one, so
			// two simultaneous barrage kills remain two independent pairs.
			final PendingLoot accepted = counterpart != null && counterpart.source == LootSource.SERVER
				? counterpart
				: loot;
			final String killSessionId = confirmLootDeath(accepted);

			if (activeTask != null && isCreditedDrop(accepted))
			{
				final String sessionId = killSessionId != null
					? killSessionId
					: (accepted.sessionId != null ? accepted.sessionId : lastCreditSessionId);
				for (ItemStack item : accepted.items)
				{
					final int itemId = itemManager.canonicalize(item.getId());
					// Exclusions are presentation/accounting filters, not data-loss rules. Keep
					// recording the drop so including it again restores the complete task total.
					if (config.lootMode() == LootMode.DROPPED)
					{
						activeTask.addItem(sessionId, itemId, item.getQuantity(),
							(long) itemManager.getItemPrice(itemId) * item.getQuantity());
					}
					else
					{
						pendingCollectedDrops.add(new PendingCollectedDrop(
							accepted.tick, sessionId, itemId, item.getQuantity(),
							itemManager.getItemPrice(itemId)));
					}
				}
				markDirty();
				log.debug("Credited {} {} drop to task", accepted.source, accepted.npcName);
			}

			resolved.add(loot);
			if (counterpart != null)
			{
				resolved.add(counterpart);
			}
		}

		pendingLoot.removeAll(resolved);
	}

	/**
	 * Decides whether a drop belongs to the task.
	 *
	 * <p>Two cases, because not every monster drops its loot as it dies:
	 *
	 * <ul>
	 *   <li><b>Dropped on death</b> — the drop lands within a tick or two of the counter
	 *   moving. Credited outright; nothing else needs to be known about it.</li>
	 *   <li><b>Collected afterwards</b> — Araxxor and friends leave loot to be picked up, so
	 *   the drop can arrive long after the counter moved. Credited only if it came from a
	 *   monster already confirmed as a target of this assignment.</li>
	 * </ul>
	 *
	 * <p>That name check is what keeps the late window honest. Credit is recorded per tick,
	 * not per NPC — the counter says a task kill happened, never which monster it was — so a
	 * window that trusted timing alone would sweep up any drop that happened to land inside
	 * it, including from something that had nothing to do with the task.
	 */
	private boolean isCreditedDrop(PendingLoot loot)
	{
		if (lastCreditTick < 0)
		{
			return false;
		}

		final int sinceCredit = loot.tick - lastCreditTick;

		// A drop marginally ahead of the counter change is still that kill's drop.
		if (sinceCredit < -CREDIT_MATCH_TOLERANCE)
		{
			return false;
		}

		if (sinceCredit <= CREDIT_MATCH_TOLERANCE
			&& SlayerTaskTargets.matches(activeTask.getTaskName(), loot.npcName))
		{
			return true;
		}

		return sinceCredit <= toTicks(config.lootCreditWindow())
			&& confirmedTargets.contains(loot.npcName);
	}

	/**
	 * Notes an NPC death so it can be matched against a counter change.
	 *
	 * <p>Reconciliation is deferred to the tick loop rather than done here, because the death
	 * and the counter change land in the same tick in an order we don't control.
	 */
	@SuppressWarnings("unused")
	@Subscribe
	public void onNpcDespawned(NpcDespawned event)
	{
		if (activeTask == null)
		{
			return;
		}

		final NPC npc = event.getNpc();
		if (npc.isDead())
		{
			noteNpcDeath(npc);
		}
	}

	private void noteNpcDeath(NPC npc)
	{
		if (npc.getName() == null || activeTask == null
			|| !SlayerTaskTargets.matches(activeTask.getTaskName(), npc.getName()))
		{
			return;
		}

		final int tick = client.getTickCount();
		final int npcIndex = npc.getIndex();
		final Integer creditedTick = creditedNpcDeathTicks.get(npcIndex);
		if (creditedTick != null && tick - creditedTick <= 20)
		{
			return;
		}
		final RecentDeath existing = recentDeaths.get(npcIndex);
		if (existing != null && Math.abs(existing.tick - tick) <= CREDIT_MATCH_TOLERANCE)
		{
			return;
		}

		final boolean interactedWithPlayer = npc.getInteracting() == client.getLocalPlayer()
			|| (client.getLocalPlayer() != null && client.getLocalPlayer().getInteracting() == npc);
		final RecentDeath death = new RecentDeath(
			npcIndex, Text.removeTags(npc.getName()), tick, interactedWithPlayer);
		recentDeaths.put(npcIndex, death);
		if (activeTask.getTaskLocation() == null && interactedWithPlayer)
		{
			creditEligibleDeath(death);
		}
	}

	/** Promotes anything that died alongside a counter change to a confirmed task target. */
	private void creditEligibleDeath(RecentDeath death)
	{
		if (death.credited || activeTask == null
			|| (activeTask.isCompleted() && taskEndTick >= 0
				&& Math.abs(death.tick - taskEndTick) > CREDIT_MATCH_TOLERANCE))
		{
			return;
		}

		death.credited = true;
		creditedNpcDeathTicks.put(death.npcIndex, death.tick);
		lastCreditTick = death.tick;
		openSession();
		lastCreditSessionId = activeSessionId;
		death.sessionId = activeSessionId;
		activeTask.addKills(activeSessionId, 1);
		confirmedTargets.add(death.npcName);
		markDirty();
		if (activeTask.isCompleted())
		{
			closeSession("ASSIGNMENT_COMPLETED");
		}
	}

	private void confirmRecentDeaths(int creditTick, int progress)
	{
		int remaining = Math.max(progress, 1);
		for (RecentDeath death : recentDeaths.values())
		{
			if (remaining > 0 && !death.credited
				&& death.playerRelated
				&& Math.abs(death.tick - creditTick) <= CREDIT_MATCH_TOLERANCE)
			{
				creditEligibleDeath(death);
				remaining--;
			}
		}
	}

	@Nullable
	private String confirmLootDeath(PendingLoot loot)
	{
		if (activeTask == null
			|| !SlayerTaskTargets.matches(activeTask.getTaskName(), loot.npcName))
		{
			return null;
		}

		RecentDeath matched = null;
		if (loot.npcIndex >= 0)
		{
			final RecentDeath indexed = recentDeaths.get(loot.npcIndex);
			if (indexed != null && indexed.npcName.equals(loot.npcName)
				&& loot.tick - indexed.tick >= -CREDIT_MATCH_TOLERANCE
				&& loot.tick - indexed.tick <= toTicks(config.lootCreditWindow()))
			{
				matched = indexed;
			}
		}
		for (RecentDeath death : recentDeaths.values())
		{
			if (matched == null && !death.lootClaimed && death.npcName.equals(loot.npcName)
				&& loot.tick - death.tick >= -CREDIT_MATCH_TOLERANCE
				&& loot.tick - death.tick <= toTicks(config.lootCreditWindow())
				&& (matched == null || death.tick < matched.tick))
			{
				matched = death;
			}
		}
		if (matched != null)
		{
			if (!matched.credited)
			{
				creditEligibleDeath(matched);
			}
			matched.lootClaimed = true;
			return matched.sessionId;
		}

		// NpcLootReceived identifies the concrete NPC. If its death callback was missed,
		// create exactly one indexed death token here. ServerNpcLoot has no NPC identity and
		// therefore must never manufacture a physical kill on its own.
		if (loot.npcIndex >= 0)
		{
			final RecentDeath lootDeath = new RecentDeath(
				loot.npcIndex, loot.npcName, loot.tick, true);
			recentDeaths.put(loot.npcIndex, lootDeath);
			creditEligibleDeath(lootDeath);
			lootDeath.lootClaimed = true;
			return lootDeath.sessionId;
		}
		return loot.sessionId != null ? loot.sessionId : lastCreditSessionId;
	}

	private void reconcileTargets(int now)
	{
		for (Iterator<Map.Entry<Integer, RecentDeath>> it = recentDeaths.entrySet().iterator(); it.hasNext(); )
		{
			final RecentDeath death = it.next().getValue();

			if (!death.credited && death.playerRelated && lastCreditTick >= 0
				&& Math.abs(death.tick - lastCreditTick) <= CREDIT_MATCH_TOLERANCE)
			{
				creditEligibleDeath(death);
			}
			final int retention = death.credited
				? toTicks(config.lootCreditWindow())
				: CREDIT_MATCH_TOLERANCE;
			if (death.tick < now - retention)
			{
				// Credited deaths remain as one-per-NPC loot tokens for the entire late window.
				it.remove();
			}
		}
		creditedNpcDeathTicks.entrySet().removeIf(entry -> entry.getValue() < now - 20);
	}

	// ------------------------------------------------------------------
	// Supplies
	// ------------------------------------------------------------------

	@SuppressWarnings("unused")
	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		// Supplies aren't scheduled from here: the diff runs every tick regardless. See
		// processSupplies().
		if (event.getContainerId() == InventoryID.INV)
		{
			collectionInventoryDirty = true;
		}
	}

	@SuppressWarnings("unused")
	@Subscribe
	public void onItemDespawned(ItemDespawned event)
	{
		if (activeTask == null || config.lootMode() != LootMode.COLLECTED
			|| event.getTile() == null || event.getItem() == null)
		{
			return;
		}

		final int now = client.getTickCount();
		final int rawItemId = event.getItem().getId();
		final int itemId = itemManager.canonicalize(rawItemId);
		final int sceneX = event.getTile().getSceneLocation().getX();
		final int sceneY = event.getTile().getSceneLocation().getY();
		for (Iterator<PendingPickup> it = pendingPickups.iterator(); it.hasNext(); )
		{
			final PendingPickup pickup = it.next();
			if (pickup.tick < now - PICKUP_ACTION_EXPIRY_TICKS)
			{
				it.remove();
				continue;
			}
			if (pickup.rawItemId != rawItemId || pickup.sceneX != sceneX || pickup.sceneY != sceneY)
			{
				continue;
			}

			final int quantity = event.getItem().getQuantity();
			if (quantity > 0)
			{
				// A despawn at the exact tile the player clicked is the collection signal for
				// sacks, barrels, bonecrushers, and other destinations that bypass INV.
				pendingInventoryGains.add(new InventoryGain(now, itemId, quantity));
				pickupInventoryIgnores.merge(itemId, quantity, Integer::sum);
			}
			it.remove();
			break;
		}
	}

	@SuppressWarnings("unused")
	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		final String option = Text.removeTags(event.getMenuOption());
		final String target = Text.removeTags(event.getMenuTarget());
		if ("Take".equalsIgnoreCase(option) && isGroundItemAction(event.getMenuAction())
			&& activeTask != null && config.lootMode() == LootMode.COLLECTED)
		{
			final int worldViewId = event.getMenuEntry().getWorldViewId();
			final WorldView worldView = client.getWorldView(worldViewId);
			final int plane = worldView == null ? -1 : worldView.getPlane();
			final int quantity = groundItemQuantity(
				worldView, plane, event.getParam0(), event.getParam1(), event.getId());
			// Some menu entries do not expose a usable world view. Still retain the Take
			// action so ItemDespawned can confirm it; one is only a scene-check fallback.
			pendingPickups.add(new PendingPickup(client.getTickCount(), worldViewId, plane,
				event.getId(), itemManager.canonicalize(event.getId()), Math.max(quantity, 1),
				event.getParam0(), event.getParam1()));
		}

		if (isRemoteStorageAction(option) && !isResyncInterfaceOpen())
		{
			// Soul bearers and similar remote-deposit items transfer inventory to storage.
			// Re-baseline on the resulting tick so the transfer is never billed as usage.
			supplyResyncPending = true;
		}

		if (isAlchemyCast(option, target))
		{
			int itemId = event.getItemId();
			if (itemId <= 0 && event.getWidget() != null)
			{
				itemId = event.getWidget().getItemId();
			}
			if (itemId <= 0)
			{
				final net.runelite.api.ItemContainer inventory =
					client.getItemContainer(InventoryID.INV);
				final net.runelite.api.Item item = inventory == null
					? null
					: inventory.getItem(event.getParam0());
				itemId = item == null ? -1 : item.getId();
			}
			if (itemId > 0)
			{
				supplyTracker.ignoreRemoval(itemId, 1);
				if (target.toLowerCase(java.util.Locale.ROOT).contains("high level alchemy"))
				{
					final int coins = itemManager.getItemComposition(itemId).getHaPrice();
					if (coins > 0)
					{
						alchemyCollectionIgnores.merge(ItemID.COINS, coins, Integer::sum);
					}
				}
			}
		}

		if ("Drop".equalsIgnoreCase(option)
			|| "Bury".equalsIgnoreCase(option)
			|| "Scatter".equalsIgnoreCase(option))
		{
			final net.runelite.api.ItemContainer inventory =
				client.getItemContainer(InventoryID.INV);
			final net.runelite.api.Item item = inventory == null
				? null
				: inventory.getItem(event.getParam0());
			if (item != null && item.getId() > 0 && item.getQuantity() > 0)
			{
				// Drop removes the whole selected stack; bury/scatter consumes one item per
				// click, but neither is a task supply cost that should net against its loot.
				final int ignored = "Drop".equalsIgnoreCase(option) ? item.getQuantity() : 1;
				supplyTracker.ignoreRemoval(item.getId(), ignored);
			}
		}
	}

	private static boolean isGroundItemAction(MenuAction action)
	{
		return action == MenuAction.GROUND_ITEM_FIRST_OPTION
			|| action == MenuAction.GROUND_ITEM_SECOND_OPTION
			|| action == MenuAction.GROUND_ITEM_THIRD_OPTION
			|| action == MenuAction.GROUND_ITEM_FOURTH_OPTION
			|| action == MenuAction.GROUND_ITEM_FIFTH_OPTION;
	}

	private static int groundItemQuantity(@Nullable WorldView worldView, int plane,
		int sceneX, int sceneY, int itemId)
	{
		if (worldView == null || plane < 0 || sceneX < 0 || sceneY < 0
			|| sceneX >= worldView.getSizeX() || sceneY >= worldView.getSizeY())
		{
			return -1;
		}
		final Tile tile = worldView.getScene().getTiles()[plane][sceneX][sceneY];
		if (tile == null)
		{
			return -1;
		}

		int quantity = 0;
		for (TileItem item : tile.getGroundItems())
		{
			if (item.getId() == itemId)
			{
				quantity += item.getQuantity();
			}
		}
		return quantity;
	}

	private void resolvePendingPickupFallbacks(int now)
	{
		for (Iterator<PendingPickup> it = pendingPickups.iterator(); it.hasNext(); )
		{
			final PendingPickup pickup = it.next();
			if (pickup.tick < now - PICKUP_ACTION_EXPIRY_TICKS)
			{
				it.remove();
				continue;
			}
			// Give the normal ItemDespawned callback the current tick first. The scene check
			// is a fallback for automatic-container updates that don't produce that sequence.
			if (pickup.tick >= now)
			{
				continue;
			}

			final int current = groundItemQuantity(client.getWorldView(pickup.worldViewId),
				pickup.plane, pickup.sceneX, pickup.sceneY, pickup.rawItemId);
			final int removed = removedGroundQuantity(pickup.initialQuantity, current);
			if (removed > 0)
			{
				pendingInventoryGains.add(new InventoryGain(now, pickup.itemId, removed));
				pickupInventoryIgnores.merge(pickup.itemId, removed, Integer::sum);
				it.remove();
			}
		}
	}

	static int removedGroundQuantity(int initial, int current)
	{
		return initial > 0 && current >= 0 ? Math.max(0, initial - current) : 0;
	}

	/** Soul bearer uses Fill/Bank-All; other portable storage items commonly use Deposit. */
	static boolean isRemoteStorageAction(String option)
	{
		return "Fill".equalsIgnoreCase(option)
			|| "Deposit".equalsIgnoreCase(option)
			|| "Bank".equalsIgnoreCase(option)
			|| "Bank-All".equalsIgnoreCase(option);
	}

	static boolean isAlchemyCast(String option, String target)
	{
		if (!"Cast".equalsIgnoreCase(option) || target == null)
		{
			return false;
		}
		final String lower = target.toLowerCase(java.util.Locale.ROOT);
		return lower.contains("high level alchemy") || lower.contains("low level alchemy");
	}

	@SuppressWarnings("unused")
	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		if (event.getActor() == client.getLocalPlayer())
		{
			// Losing an inventory is not a supply cost.
			noteLocalDeath();
			return;
		}

		// Second source for target confirmation alongside onNpcDespawned. Bosses with scripted
		// death sequences don't always despawn in a state that reads as dead, and missing the
		// death would mean their collected loot never gets recognised.
		if (activeTask != null && event.getActor() instanceof NPC)
		{
			noteNpcDeath((NPC) event.getActor());
		}
	}

	@SuppressWarnings("unused")
	@Subscribe
	public void onAnimationChanged(AnimationChanged event)
	{
		// Not gated on an open session. Attribution is recordSupplyCharge()'s job — it buffers
		// into the grace window when no session is open yet, and credits the last session when
		// the kill that closed the task is what spent this. Deciding here instead threw both
		// away: the first fight of every session is fought before the first kill opens one.
		if (!config.trackSupplies() || event.getActor() != client.getLocalPlayer())
		{
			return;
		}

		final int animation = client.getLocalPlayer().getAnimation();
		final int tick = client.getTickCount();
		if (tick == lastWeaponUsageTick && animation == lastWeaponUsageAnimation)
		{
			return;
		}

		final int weaponId = equippedWeaponId();
		SupplyCharge charge = SupplyCharge.EMPTY;
		if (animation == 8056 && isScythe(weaponId))
		{
			// A hundred attacks to a vial. The vial is billed smoothly, ~1/100 of its price per
			// swing, so the running cost tracks each attack instead of jumping every hundredth one,
			// and it is listed at the hundredth of a vial the swing actually spent. Listing it in
			// whole vials instead meant it appeared in the breakdown only on every hundredth swing
			// — so a task of forty scythe swings showed blood runes and no vial at all, and read as
			// the vial not being tracked when its cost had been in the total all along.
			final long vialNumerator = itemManager.getItemPrice(ItemID.VIAL_BLOOD) + scytheVialRemainder;
			final long vialCost = vialNumerator / 100L;
			scytheVialRemainder = vialNumerator % 100L;

			charge = supplyTracker.chargedItemUse(weaponId, vialCost,
				Collections.singletonMap(ItemID.VIAL_BLOOD, 1), ItemID.BLOODRUNE, 2);
		}
		else if (animation == 9493 && weaponId == ItemID.TUMEKENS_SHADOW)
		{
			charge = supplyTracker.chargedItemUse(weaponId, 0,
				ItemID.SOULRUNE, 2, ItemID.CHAOSRUNE, 5);
		}

		if (!charge.isEmpty())
		{
			recordSupplyCharge(charge);
			lastWeaponUsageTick = tick;
			lastWeaponUsageAnimation = animation;
		}
	}

	/**
	 * Holds the supply baseline across a death, however long the death sequence takes.
	 *
	 * <p>Armed from two independent signals — {@link ActorDeath} and the death chat message —
	 * because a missed arm bills the entire lost inventory as supplies, and the two cost nothing
	 * together. Both fire at the <em>start</em> of the sequence, several ticks before the
	 * containers actually empty, which is why what they arm is a settling period rather than a
	 * one-shot resync.
	 */
	private void noteLocalDeath()
	{
		supplyResyncPending = true;
		deathSettleTicks = DEATH_SETTLE_TICKS;
		deathSettleTicksRemaining = DEATH_SETTLE_MAX_TICKS;
	}

	@SuppressWarnings("unused")
	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE
			&& event.getType() != ChatMessageType.SPAM)
		{
			return;
		}

		final String message = Text.removeTags(event.getMessage());
		if (DEATH_MESSAGE.equals(message))
		{
			noteLocalDeath();
			return;
		}

		// The magazine's size is a varp the supply diff reads directly; only the kind of
		// ammunition in it has to be learned from a message.
		if (config.cannonballType() == CannonballType.AUTOMATIC)
		{
			final Matcher cannonLoad = CANNON_LOAD_PATTERN.matcher(message);
			if (cannonLoad.matches())
			{
				supplyTracker.setLoadedCannonballId(cannonLoad.group(2) == null
					? ItemID.MCANNONBALL : ItemID.GRANITE_CANNONBALL);
			}
			final Matcher cannonUnload = CANNON_UNLOAD_PATTERN.matcher(message);
			if (cannonUnload.matches())
			{
				supplyTracker.setLoadedCannonballId("Cannonball".equals(cannonUnload.group(1))
					? ItemID.MCANNONBALL : ItemID.GRANITE_CANNONBALL);
			}
		}

		final Matcher darts = BLOWPIPE_DARTS_PATTERN.matcher(message);
		if (darts.find())
		{
			loadedBlowpipeDartId = dartId(darts.group(1));
		}
		else if (message.startsWith("Darts: None. Scales:"))
		{
			loadedBlowpipeDartId = -1;
		}
		else if (config.eyeOfAyakCharge() == EyeOfAyakCharge.AUTOMATIC)
		{
			if (message.equals("Eye of Ayak has been charged with demon tears"))
			{
				supplyTracker.setEyeUsesTears(true);
			}
			else if (message.equals("Eye of Ayak has been charged with runes"))
			{
				supplyTracker.setEyeUsesTears(false);
			}
		}
	}

	/**
	 * Applies the configured charge sources for items that can hold more than one.
	 *
	 * <p>Each of these is otherwise learned from a chat message, which only says anything at the
	 * moment the item is filled. A player who charges an eye of ayak or loads a cannon outside
	 * the session — or before enabling the plugin — leaves that inference with nothing to go on,
	 * and it then quietly bills the wrong resource for the whole trip. Setting it explicitly is
	 * the fix; on {@code AUTOMATIC} nothing is imposed and the messages keep deciding.
	 */
	private void applyChargeConfig()
	{
		if (config.eyeOfAyakCharge() != EyeOfAyakCharge.AUTOMATIC)
		{
			supplyTracker.setEyeUsesTears(config.eyeOfAyakCharge() == EyeOfAyakCharge.DEMON_TEARS);
		}
		if (config.cannonballType() != CannonballType.AUTOMATIC)
		{
			supplyTracker.setLoadedCannonballId(config.cannonballType().getItemId());
		}
	}

	/** The configured dart, or whatever the blowpipe's last check message reported. */
	private int effectiveDartId()
	{
		return config.blowpipeDart() == BlowpipeDart.AUTOMATIC
			? loadedBlowpipeDartId
			: config.blowpipeDart().getItemId();
	}

	/**
	 * Charges per-attack consumption for weapons whose cost can't be read off the carried
	 * snapshot.
	 *
	 * <p>Counted from the attack animation restarting rather than from the item's charge varbit.
	 * A fast weapon's animation is never cleared between attacks, so the animation's frame
	 * returning to zero is what marks one attack; {@link #lastWeaponUsageTick} then keeps a
	 * single attack from also being billed by {@link #onAnimationChanged}.
	 */
	private void processRepeatingWeaponUsage(int now)
	{
		// See onAnimationChanged: no session gate here either. Every branch below already routes
		// through recordSupplyCharge(), which is what knows where a charge belongs.
		if (!config.trackSupplies() || client.getLocalPlayer() == null
			|| client.getLocalPlayer().getAnimationFrame() != 0
			|| now == lastWeaponUsageTick)
		{
			return;
		}

		final int animation = client.getLocalPlayer().getAnimation();
		final int weaponId = equippedWeaponId();

		if (animation == BLOWPIPE_ATTACK_ANIMATION && isBlowpipe(weaponId))
		{
			noteWeaponUsage(now, animation);
			chargeBlowpipeUse(weaponId);
		}
		else if (animation == TENTACLE_ATTACK_ANIMATION && isTentacle(weaponId))
		{
			noteWeaponUsage(now, animation);
			// A tentacle is a whip with a kraken tentacle attached, good for 10,000 attacks. At
			// the end of them it reverts to the kraken tentacle and the whip is gone, so the whip
			// is what 10,000 attacks actually cost. Fractional gp is carried between hits so a
			// long task doesn't round every attack down to nothing.
			final long numerator = itemManager.getItemPrice(ItemID.ABYSSAL_WHIP) + tentacleRemainder;
			final long cost = numerator / TENTACLE_CHARGES;
			tentacleRemainder = numerator % TENTACLE_CHARGES;
			if (cost > 0)
			{
				recordSupplyCharge(supplyTracker.chargedItemUse(weaponId, cost));
			}
		}
		else if ((animation == POWERED_STAFF_ANIMATION || animation == POWERED_STAFF_ANIMATION_ALT)
			&& POWERED_STAFF_COSTS.containsKey(weaponId))
		{
			noteWeaponUsage(now, animation);
			recordSupplyCharge(
				supplyTracker.chargedItemUse(weaponId, 0, POWERED_STAFF_COSTS.get(weaponId)));
		}
		else if (animation == EYE_OF_AYAK_ANIMATION && weaponId == ItemID.EYE_OF_AYAK)
		{
			noteWeaponUsage(now, animation);
			recordSupplyCharge(supplyTracker.isEyeUsingTears()
				? supplyTracker.chargedItemUse(weaponId, 0, ItemID.DEMON_TEAR, 1)
				: supplyTracker.chargedItemUse(weaponId, 0, ItemID.DEATHRUNE, 2, ItemID.CHAOSRUNE, 1));
		}
		else if (animation == VENATOR_ATTACK_ANIMATION && isVenatorBow(weaponId))
		{
			noteWeaponUsage(now, animation);
			// One charge, one ancient essence, per shot.
			recordSupplyCharge(supplyTracker.chargedItemUse(weaponId, 0, ItemID.ANCIENT_ESSENCE, 1));
		}
	}

	/**
	 * Bills the sunfire splinters a charged quiver spends, counted per shot.
	 *
	 * <p>A splinter is a 1-in-3 roll per shot, so shots are what has to be counted. An earlier
	 * version counted the quiver's ammunition falling instead, which is wrong by roughly the
	 * ammunition-saving rate: measured in game, twelve shots spent four charges but only one arrow,
	 * because an assembler puts nearly all of it back. Ammunition consumed and shots fired are
	 * different quantities and only the second one drives the roll.
	 *
	 * <p>A shot is the attack animation restarting, as elsewhere in this class. Movement doesn't
	 * register — walking and running are pose animations, not this field — so an animating player
	 * holding a bow is attacking with it, give or take the occasional bite of food.
	 *
	 * <p>Eligibility is a weapon category that fires quiver-storable ammunition, minus the weapons
	 * that are exempt: the ones paying out of their own charges, and the ones whose ammunition the
	 * quiver can't hold at all.
	 */
	private void processQuiverSplinters(int now)
	{
		if (!config.trackSupplies() || now == quiverShotTick || client.getLocalPlayer() == null
			|| client.getLocalPlayer().getAnimation() == -1
			|| client.getLocalPlayer().getAnimationFrame() != 0)
		{
			return;
		}

		final int animation = client.getLocalPlayer().getAnimation();

		// Only a splinter-charged quiver spends them. A blessed one is permanently lit and an
		// uncharged one has nothing in it.
		final int cape = equippedCapeId();
		if (cape != ItemID.DIZANAS_QUIVER_CHARGED && cape != ItemID.DIZANAS_QUIVER_CHARGED_TROUVER)
		{
			return;
		}

		final int category = client.getVarbitValue(VarbitID.COMBAT_WEAPON_CATEGORY);
		if (!QUIVER_AMMO_CATEGORIES.contains(category)
			|| QUIVER_EXEMPT_WEAPONS.contains(equippedWeaponId()))
		{
			return;
		}

		if (!RANGED_ATTACK_ANIMATIONS.contains(animation))
		{
			return;
		}

		quiverShotTick = now;
		quiverSplinterRemainder++;
		final int splinters = quiverSplinterRemainder / SHOTS_PER_SPLINTER;
		quiverSplinterRemainder %= SHOTS_PER_SPLINTER;
		if (splinters > 0)
		{
			recordSupplyCharge(
				supplyTracker.chargedItemUse(cape, 0, ItemID.SUNFIRESPLINTER, splinters));
		}
	}

	/**
	 * Bills a page fraction each time a spell is cast with a matching elemental tome equipped.
	 *
	 * <p>One cast spends one of the tome's charges and a page is worth twenty of them, so a
	 * twentieth of a page is billed per cast and the remainder carried. Supplies Tracker bills a
	 * whole page per cast, which overstates it twentyfold.
	 */
	@SuppressWarnings("unused")
	@Subscribe
	public void onGraphicChanged(GraphicChanged event)
	{
		if (!config.trackSupplies() || event.getActor() != client.getLocalPlayer())
		{
			return;
		}

		final int shield = equippedShieldId();
		for (int[] tome : ELEMENTAL_TOMES)
		{
			if (tome[0] != shield)
			{
				continue;
			}

			boolean cast = false;
			for (int g = 2; g < tome.length; g++)
			{
				if (client.getLocalPlayer().hasSpotAnim(tome[g]))
				{
					cast = true;
					break;
				}
			}
			if (!cast)
			{
				return;
			}

			// Only the fire tome takes two kinds of page, so only it needs asking about.
			final int pageId = tome[1] < 0 ? config.tomePage().getItemId() : tome[1];
			final long carried = tomeRemainders.getOrDefault(tome[0], 0L);
			final long numerator = itemManager.getItemPrice(pageId) + carried;
			tomeRemainders.put(tome[0], numerator % TomePage.CHARGES_PER_PAGE);
			final long cost = numerator / TomePage.CHARGES_PER_PAGE;
			if (cost > 0)
			{
				recordSupplyCharge(supplyTracker.chargedItemUse(tome[0], cost));
			}
			return;
		}
	}

	private int equippedShieldId()
	{
		final net.runelite.api.ItemContainer worn = client.getItemContainer(InventoryID.WORN);
		if (worn == null)
		{
			return -1;
		}
		final net.runelite.api.Item[] items = worn.getItems();
		final int slot = EquipmentInventorySlot.SHIELD.getSlotIdx();
		return slot < items.length ? items[slot].getId() : -1;
	}

	/**
	 * Bills the eye of ayak's special attack, which the animation path can't be trusted to see.
	 *
	 * <p>Soul Rend is a slower attack from a charged weapon, and nothing says it is exempt from
	 * spending a charge — so assuming it is free would undercount every spec. Its animation id
	 * isn't published anywhere available here, so the spend of special energy is used as the
	 * signal instead, which is the same event by definition.
	 *
	 * <p>This is safe whichever animation the spec plays. If it plays the ordinary attack
	 * animation, {@link #processRepeatingWeaponUsage} has already billed this tick and marked it,
	 * and this skips. If it plays its own, nothing billed and this covers it. Neither case can
	 * double-bill, so the animation id never has to be established.
	 */
	private void processSpecialAttackCharges(int now)
	{
		final int energy = client.getVarpValue(VarPlayerID.SA_ENERGY);
		final int previous = specialEnergyLastSeen;
		specialEnergyLastSeen = energy;

		if (previous < 0 || energy >= previous || !config.trackSupplies()
			|| now == lastWeaponUsageTick)
		{
			return;
		}

		final int weaponId = equippedWeaponId();
		if (weaponId != ItemID.EYE_OF_AYAK)
		{
			return;
		}

		noteWeaponUsage(now, -1);
		recordSupplyCharge(supplyTracker.isEyeUsingTears()
			? supplyTracker.chargedItemUse(weaponId, 0, ItemID.DEMON_TEAR, 1)
			: supplyTracker.chargedItemUse(weaponId, 0, ItemID.DEATHRUNE, 2, ItemID.CHAOSRUNE, 1));
	}

	private void noteWeaponUsage(int tick, int animation)
	{
		lastWeaponUsageTick = tick;
		lastWeaponUsageAnimation = animation;
	}

	/**
	 * Bills one blowpipe attack: the darts it wore out and the scales it burned.
	 *
	 * <p>Both are fractional per shot — darts by the ammunition-saving device, scales by the
	 * blowpipe's own 1-in-3 chance to spend nothing — so each is accumulated and spent whole. The
	 * attack is always recorded even when it rounded to nothing, so the row's use count is the
	 * number of attacks rather than the number that happened to tip a counter over.
	 */
	private void chargeBlowpipeUse(int weaponId)
	{
		blowpipeDartRemainder += dartLossPercent();
		final int darts = blowpipeDartRemainder / 100;
		blowpipeDartRemainder %= 100;

		blowpipeScaleRemainder += SCALES_PER_THREE_SHOTS;
		final int scales = blowpipeScaleRemainder / 3;
		blowpipeScaleRemainder %= 3;

		final int dartId = effectiveDartId();
		recordSupplyCharge(dartId > 0
			? supplyTracker.chargedItemUse(weaponId, 0,
				dartId, darts, ItemID.SNAKEBOSS_SCALE, scales)
			: supplyTracker.chargedItemUse(weaponId, 0, ItemID.SNAKEBOSS_SCALE, scales));
	}

	private int dartLossPercent()
	{
		final int cape = equippedCapeId();

		switch (cape)
		{
			// ANMA_* are Animal Magnetism's rewards: the attractor and the accumulator.
			case ItemID.ANMA_30_REWARD:
				return LOSS_WITH_ATTRACTOR;
			case ItemID.ANMA_50_REWARD:
				return LOSS_WITH_ACCUMULATOR;
			case ItemID.AVAS_ASSEMBLER:
			case ItemID.AVAS_ASSEMBLER_TROUVER:
			case ItemID.AVAS_ASSEMBLER_MASORI:
			case ItemID.AVAS_ASSEMBLER_MASORI_TROUVER:
			case ItemID.SKILLCAPE_MAX_ASSEMBLER:
			case ItemID.SKILLCAPE_MAX_ASSEMBLER_TROUVER:
			case ItemID.SKILLCAPE_MAX_ASSEMBLER_MASORI:
			case ItemID.SKILLCAPE_MAX_ASSEMBLER_MASORI_TROUVER:
				return LOSS_WITH_ASSEMBLER;
			default:
				break;
		}

		// A quiver saves nothing on its own — the effect is added by taking it to Ava with a
		// device, which sets an account-wide varbit rather than changing the quiver's item id.
		// Every quiver the player owns gains it at once, so the worn id can't answer this.
		if (isDizanasQuiver(cape))
		{
			// The varbit names the device, in the order Ava hands them out. Observed as 3 on an
			// assembler-upgraded quiver; 1 and 2 follow from there. Anything else unrecognised is
			// read as the assembler, being both the best and the common case.
			switch (client.getVarbitValue(VarbitID.DIZANAS_QUIVER_AMMO_SAVE))
			{
				case 0:
					return LOSS_WITH_NO_DEVICE;
				case 1:
					return LOSS_WITH_ATTRACTOR;
				case 2:
					return LOSS_WITH_ACCUMULATOR;
				default:
					return LOSS_WITH_ASSEMBLER;
			}
		}

		return LOSS_WITH_NO_DEVICE;
	}

	private int equippedCapeId()
	{
		final net.runelite.api.ItemContainer worn = client.getItemContainer(InventoryID.WORN);
		if (worn == null)
		{
			return -1;
		}
		final net.runelite.api.Item[] items = worn.getItems();
		final int slot = EquipmentInventorySlot.CAPE.getSlotIdx();
		return slot < items.length ? items[slot].getId() : -1;
	}

	/**
	 * Whether a cape-slot item is a quiver. Broken and mangled variants are left out — they
	 * can't be worn, so they can't be here.
	 */
	private static boolean isDizanasQuiver(int itemId)
	{
		return itemId == ItemID.DIZANAS_QUIVER_UNCHARGED
			|| itemId == ItemID.DIZANAS_QUIVER_UNCHARGED_TROUVER
			|| itemId == ItemID.DIZANAS_QUIVER_CHARGED
			|| itemId == ItemID.DIZANAS_QUIVER_CHARGED_TROUVER
			|| itemId == ItemID.DIZANAS_QUIVER_INFINITE
			|| itemId == ItemID.DIZANAS_QUIVER_INFINITE_TROUVER;
	}

	static int dartId(String name)
	{
		switch (name.trim().toLowerCase())
		{
			case "bronze dart": return ItemID.BRONZE_DART;
			case "iron dart": return ItemID.IRON_DART;
			case "steel dart": return ItemID.STEEL_DART;
			case "black dart": return ItemID.BLACK_DART;
			case "mithril dart": return ItemID.MITHRIL_DART;
			case "adamant dart": return ItemID.ADAMANT_DART;
			case "rune dart": return ItemID.RUNE_DART;
			case "amethyst dart": return ItemID.AMETHYST_DART;
			case "dragon dart": return ItemID.DRAGON_DART;
			default: return -1;
		}
	}

	private int equippedWeaponId()
	{
		final net.runelite.api.ItemContainer worn = client.getItemContainer(InventoryID.WORN);
		if (worn == null)
		{
			return -1;
		}
		final net.runelite.api.Item[] items = worn.getItems();
		final int slot = EquipmentInventorySlot.WEAPON.getSlotIdx();
		return slot < items.length ? items[slot].getId() : -1;
	}

	private static boolean isScythe(int itemId)
	{
		return itemId == ItemID.SCYTHE_OF_VITUR
			|| itemId == ItemID.SCYTHE_OF_VITUR_OR
			|| itemId == ItemID.SCYTHE_OF_VITUR_BL;
	}

	private static boolean isBlowpipe(int itemId)
	{
		return itemId == ItemID.TOXIC_BLOWPIPE_LOADED
			|| itemId == ItemID.TOXIC_BLOWPIPE_LOADED_ORNAMENT;
	}

	private static boolean isTentacle(int itemId)
	{
		return itemId == ItemID.ABYSSAL_TENTACLE || itemId == ItemID.LEAGUE_3_WHIP_TENTACLE;
	}

	/** Only the charged forms; an uncharged venator bow can't be fired. */
	private static boolean isVenatorBow(int itemId)
	{
		return itemId == ItemID.VENATOR_BOW || itemId == ItemID.VENATOR_BOW_ORNAMENT;
	}

	/**
	 * Diffs what the player is carrying, every tick.
	 *
	 * <p>Deliberately unconditional. This used to run only on ticks flagged by an item container
	 * change or by one of a hardcoded list of charge varbits, and that scheduling was the source
	 * of every "supply X isn't tracked" report: the rune pouch wasn't on the list at all, and a
	 * charge held in a varbit is only reliably observable by reading its value, not by waiting
	 * for an event naming it. A resource that nothing flagged was billed late, in a lump, on the
	 * next unrelated inventory change — or erased, if that change happened to be a bank visit.
	 *
	 * <p>The saving was never worth it. The diff is a ~60 entry map against the previous one, and
	 * item names are only resolved for entries that actually changed, so an idle tick does almost
	 * nothing.
	 */
	private void processSupplies()
	{
		if (deathSettleTicks >= 0)
		{
			supplyResyncPending = false;
			supplyTracker.resync();
			// The countdown only runs while the player is alive again. Everything between the
			// killing blow and the respawn — the death animation, the teleport, the loading
			// screen that suppresses this loop entirely — is spent with the counter untouched,
			// so the settling period is 15 ticks of a player standing in Lumbridge rather than
			// 15 ticks of wall clock that the loop may never have seen.
			final Player local = client.getLocalPlayer();
			if (local != null && !local.isDead())
			{
				deathSettleTicks--;
			}
			if (--deathSettleTicksRemaining <= 0)
			{
				// The alive-again condition never arrived. Charging again from a fresh baseline
				// is the safe way to be wrong here: at worst one death's aftermath goes unbilled,
				// against a supply total stuck at zero indefinitely.
				log.debug("Death settling hit its ceiling with the player still reading as dead");
				deathSettleTicks = -1;
			}
			return;
		}

		if (supplyResyncPending)
		{
			supplyResyncPending = false;
			supplyTracker.resync();
			return;
		}

		if (!config.trackSupplies())
		{
			// Feature off: don't diff anything. The snapshot is deliberately left stale and
			// gets rebaselined when the setting is turned back on, so nothing consumed while
			// off is billed retroactively.
			return;
		}

		if (isResyncInterfaceOpen())
		{
			// Banking, trading or shopping — re-baseline so restocking isn't charged.
			supplyTracker.resync();
			return;
		}

		recordSupplyCharge(supplyTracker.charge());
	}

	private void recordSupplyCharge(SupplyCharge charge)
	{
		if (charge.isEmpty() || activeTask == null)
		{
			return;
		}
		if (sessionOpen)
		{
			activeTask.addSupplyCharge(activeSessionId, charge);
			markDirty();
			return;
		}
		if (activeTask.isCompleted())
		{
			// The kill that finishes an assignment closes its session during packet processing,
			// before the tick loop reads what that kill cost. Dropping the charge there would
			// omit the supplies spent on every final kill. Credited to the session the kill
			// belongs to, and bounded to the same tick tolerance used to line up its drop, so
			// restocking after the task ends still isn't billed to it.
			if (lastCreditSessionId != null && taskEndTick >= 0
				&& client.getTickCount() - taskEndTick <= CREDIT_MATCH_TOLERANCE)
			{
				activeTask.addSupplyCharge(lastCreditSessionId, charge);
				markDirty();
			}
			return;
		}
		if (config.graceWindow() > 0)
		{
			// Might belong to a session that hasn't opened yet — hold it and decide later.
			graceBuffer.add(new BufferedCharge(client.getTickCount(), charge));
		}
	}

	private void processCollectedLoot()
	{
		if (config.lootMode() != LootMode.COLLECTED)
		{
			pendingCollectedDrops.clear();
			pendingInventoryGains.clear();
			pendingPickups.clear();
			pickupInventoryIgnores.clear();
			alchemyCollectionIgnores.clear();
			collectionInventoryDirty = false;
			return;
		}

		final int now = client.getTickCount();
		// Rune-pouch quantities are varbits and produce no INV container event. Diff the
		// combined carried snapshot every tick so automatic pouch pickup is observable.
		collectionInventoryDirty = false;
		final Map<Integer, Integer> current = takeInventorySnapshot();
		if (collectionInventorySnapshot != null && !isResyncInterfaceOpen())
		{
			for (Map.Entry<Integer, Integer> entry : current.entrySet())
			{
				int gained = entry.getValue()
					- collectionInventorySnapshot.getOrDefault(entry.getKey(), 0);
				gained = applyPickupInventoryIgnore(
					pickupInventoryIgnores, entry.getKey(), gained);
				gained = applyPickupInventoryIgnore(
					alchemyCollectionIgnores, entry.getKey(), gained);
				if (gained > 0)
				{
					confirmPickupReachedInventory(entry.getKey());
					pendingInventoryGains.add(new InventoryGain(now, entry.getKey(), gained));
				}
			}
		}
		else
		{
			pendingInventoryGains.clear();
		}
		collectionInventorySnapshot = current;

		// Item-container changes and item despawns from the same server update have both
		// arrived before GameTick. Any unused suppression belongs to a direct-to-container
		// pickup and must not hide a later, unrelated inventory gain.
		pickupInventoryIgnores.clear();
		alchemyCollectionIgnores.clear();

		final int collectionRetention = Math.max(
			CREDIT_MATCH_TOLERANCE, toTicks(config.lootCreditWindow()));
		for (Iterator<InventoryGain> gains = pendingInventoryGains.iterator(); gains.hasNext(); )
		{
			final InventoryGain gain = gains.next();
			for (Iterator<PendingCollectedDrop> drops = pendingCollectedDrops.iterator();
				drops.hasNext() && gain.quantity > 0; )
			{
				final PendingCollectedDrop drop = drops.next();
				if (drop.itemId != gain.itemId)
				{
					continue;
				}

				final int matched = Math.min(gain.quantity, drop.quantity);
				if (activeTask != null)
				{
					// Collected-mode exclusions are also retained invisibly for later inclusion.
					activeTask.addItem(drop.sessionId, drop.itemId, matched,
						(long) drop.unitPrice * matched);
					markDirty();
				}
				gain.quantity -= matched;
				drop.quantity -= matched;
				if (drop.quantity == 0)
				{
					drops.remove();
				}
			}
			if (gain.quantity == 0 || gain.tick < now - collectionRetention)
			{
				gains.remove();
			}
		}

		pendingCollectedDrops.removeIf(drop -> drop.tick < now - collectionRetention);
		pendingPickups.removeIf(pickup -> pickup.tick < now - PICKUP_ACTION_EXPIRY_TICKS);
	}

	/** Prevents one successful ground pickup being counted once by INV and once by its despawn. */
	static int applyPickupInventoryIgnore(Map<Integer, Integer> ignores, int itemId, int gained)
	{
		if (gained <= 0)
		{
			return gained;
		}
		final int ignored = ignores.getOrDefault(itemId, 0);
		if (ignored <= 0)
		{
			return gained;
		}

		final int absorbed = Math.min(ignored, gained);
		if (absorbed == ignored)
		{
			ignores.remove(itemId);
		}
		else
		{
			ignores.put(itemId, ignored - absorbed);
		}
		return gained - absorbed;
	}

	private void confirmPickupReachedInventory(int itemId)
	{
		for (Iterator<PendingPickup> it = pendingPickups.iterator(); it.hasNext(); )
		{
			if (it.next().itemId == itemId)
			{
				it.remove();
				return;
			}
		}
	}

	private Map<Integer, Integer> takeInventorySnapshot()
	{
		final Map<Integer, Integer> snapshot = new HashMap<>();
		final net.runelite.api.ItemContainer inventory = client.getItemContainer(InventoryID.INV);
		if (inventory != null)
		{
			for (net.runelite.api.Item item : inventory.getItems())
			{
				if (item.getId() > 0 && item.getQuantity() > 0)
				{
					snapshot.merge(itemManager.canonicalize(item.getId()),
						item.getQuantity(), Integer::sum);
				}
			}
		}
		// Treat inventory and rune pouch as one carried collection. Moving runes between
		// them nets to zero, while automatic ground pickup into the pouch is a real gain.
		supplyTracker.takeRunePouchSnapshot().forEach(
			(itemId, quantity) -> snapshot.merge(itemId, quantity, Integer::sum));
		return snapshot;
	}

	private void resyncCollectionInventory()
	{
		collectionInventorySnapshot = takeInventorySnapshot();
		collectionInventoryDirty = false;
		pendingInventoryGains.clear();
		pickupInventoryIgnores.clear();
		alchemyCollectionIgnores.clear();
	}

	/**
	 * Whether item movement right now is something other than consumption.
	 *
	 * <p>Asked of the client every time rather than remembered from {@link WidgetLoaded} /
	 * {@link WidgetClosed}. Tracking it in a set made a missed close event permanent and total:
	 * the group stayed in the set, {@link #processSupplies()} re-baselined on every tick instead
	 * of charging, and supplies read as a flat 0 gp for the rest of the client's life — a task
	 * over which 26 doses of potion were drunk showed "-0 gp" until RuneLite was restarted.
	 * Nothing here has to be remembered, so nothing here can go stale.
	 *
	 * <p>The {@code isHidden} check is the point. A widget object outlives the interface that
	 * built it, so a group the player opened once is non-null from then on — testing only for
	 * null is what the closed bank of an hour ago looks like. This is the same open-test
	 * RuneLite's own bank plugin uses.
	 *
	 * <p>Must run on the client thread, which both callers do.
	 */
	private boolean isResyncInterfaceOpen()
	{
		for (int groupId : RESYNC_INTERFACES)
		{
			final Widget widget = client.getWidget(groupId, 0);
			if (widget != null && !widget.isHidden())
			{
				return true;
			}
		}
		return false;
	}

	// ------------------------------------------------------------------
	// Tick loop
	// ------------------------------------------------------------------

	@SuppressWarnings("unused")
	@Subscribe
	public void onGameTick(GameTick event)
	{
		final int now = client.getTickCount();
		processCannonCombat(now);
		processRepeatingWeaponUsage(now);
		processSpecialAttackCharges(now);
		processQuiverSplinters(now);

		// Kill credit for this tick is already registered by onVarbitChanged, which fires
		// during packet processing, so the session state below is up to date.
		reconcileTargets(now);
		resolvePendingLoot();
		resolvePendingPickupFallbacks(now);
		processCollectedLoot();
		processSupplies();

		if (sessionOpen && activeTask != null)
		{
			if (!activeTask.tickSession(activeSessionId))
			{
				// The record says that session is closed while we still think it's running.
				// Left alone the clock stops permanently, because openSession() sees
				// sessionOpen and returns without opening anything — which is how a reload
				// underneath an open session read as a frozen timer. Reopen the session if it
				// still exists, and otherwise let the next kill start a fresh one.
				log.debug("Session {} closed under an open tracker, reopening", activeSessionId);
				if (activeSessionId == null
					|| activeTask.resumeSession(activeSessionId, System.currentTimeMillis()) == null)
				{
					sessionOpen = false;
					activeSessionId = null;
				}
				markDirty();
			}
			// The panel shows elapsed time off this counter, so an open session is always
			// behind the live state even when nothing else happened. Without this the clock
			// only moved when a kill or a drop happened to mark the panel dirty, and read as
			// frozen between them. The existing throttle keeps this to one rebuild per 3s.
			panelDirty = true;

			if (lastCreditTick >= 0 && now - lastCreditTick > toTicks(config.sessionTimeout() * 60))
			{
				log.debug("Session idle for {} minutes, closing", config.sessionTimeout());
				closeSession("IDLE_TIMEOUT");
				markDirty();
			}
		}

		pruneGraceBuffer(now);

		// A finished assignment is held open until its late-loot window has run out, not just
		// until the tick after the last kill. Archiving the moment the counter hits zero would
		// mean a boss whose loot has to be collected — Araxxor, say — loses its final drop,
		// because there'd be no active task left for it to land on by the time it arrives.
		if (activeTask != null && activeTask.isCompleted() && pendingLoot.isEmpty()
			&& taskEndTick >= 0 && now - taskEndTick > toTicks(config.lootCreditWindow()))
		{
			archiveActiveTask();
			pushToPanel();
		}

		// Both are throttled rather than run on every change: firing ammunition charges
		// supplies every few ticks, and neither a config write nor a full panel rebuild is
		// worth doing at that rate.
		if (panelDirty && now % PANEL_REFRESH_INTERVAL_TICKS == 0)
		{
			panelDirty = false;
			pushToPanel();
		}

		if (persistDirty && now % PERSIST_INTERVAL_TICKS == 0)
		{
			persist();
		}
	}

	/** Flags that the stored snapshot and the panel are both behind the live state. */
	private void markDirty()
	{
		persistDirty = true;
		panelDirty = true;
	}

	/**
	 * Notes damage the player dealt to something this assignment cares about, so the grace window
	 * can cover the whole of the fight that produced the first kill rather than a fixed number of
	 * seconds before it.
	 *
	 * <p>Restricted to task-relevant NPCs on purpose. Any-combat would let ten minutes of unbroken
	 * fighting against something else — a raid, another boss — carry the window back to its ceiling
	 * and bill all of it to the task the moment one on-task kill finally landed. Damage taken is not
	 * counted either, since a hitsplat on the player doesn't say what put it there.
	 */
	@SuppressWarnings("unused")
	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		if (!event.getHitsplat().isMine() || !(event.getActor() instanceof NPC))
		{
			return;
		}
		if (isTaskRelevant(((NPC) event.getActor()).getName()))
		{
			noteCombat(client.getTickCount());
		}
	}

	/**
	 * Whether an NPC is one this assignment counts, by the same rule the loot credit uses: already
	 * confirmed by a counter change, or matching the assignment name and its aliases. The alias
	 * table is what makes Araxxor count on an araxyte task, which is the case this exists for.
	 */
	private boolean isTaskRelevant(@Nullable String npcName)
	{
		if (activeTask == null || npcName == null)
		{
			return false;
		}
		final String name = Text.removeTags(npcName);
		return confirmedTargets.contains(name)
			|| SlayerTaskTargets.matches(activeTask.getTaskName(), name);
	}

	/**
	 * A cannon firing is the player fighting, even when they never attack anything themselves.
	 *
	 * <p>The magazine is a varp of the player's own cannon, so nobody else's can move it. It can't
	 * say what was being shot at, though, so unlike {@link #onHitsplatApplied} this can't be
	 * restricted to task-relevant targets — the ceiling on the replay is what bounds it. A fall of
	 * more than a shot or two is an unload or a pickup rather than firing, and isn't combat.
	 */
	private void processCannonCombat(int now)
	{
		final int balls = client.getVarpValue(VarPlayerID.ROCKTHROWER);
		final int previous = lastCannonballCount;
		lastCannonballCount = balls;
		if (previous > balls && previous - balls <= MAX_CANNON_SHOTS_PER_TICK)
		{
			noteCombat(now);
		}
	}

	private void noteCombat(int now)
	{
		if (combatStartTick < 0)
		{
			combatStartTick = Math.max(0, now - COMBAT_LEAD_TICKS);
		}
	}

	/**
	 * Earliest tick a buffered charge may still be replayed from.
	 *
	 * <p>The configured window measures backwards from now, which asks the wrong question when
	 * fights differ in length: Araxxor takes about a minute where a regular araxyte takes ten or
	 * twenty seconds, and on the same assignment the supplies that paid for the kill shouldn't
	 * depend on which one was chosen. So the fight itself extends the window when it is longer —
	 * never shortens it — and a hard ceiling stops an unbroken run of combat reaching back forever.
	 *
	 * <p>A grace window of zero means what it says and is left alone; nothing is buffered at all in
	 * that case.
	 */
	static int graceCutoff(int now, int graceTicks, int combatStartTick, int maxTicks)
	{
		if (graceTicks <= 0)
		{
			return now;
		}

		int cutoff = now - graceTicks;
		if (combatStartTick >= 0 && combatStartTick < cutoff)
		{
			cutoff = combatStartTick;
		}
		return Math.max(cutoff, now - maxTicks);
	}

	private int graceCutoff()
	{
		return graceCutoff(client.getTickCount(), toTicks(config.graceWindow()),
			combatStartTick, MAX_GRACE_TICKS);
	}

	/**
	 * Credits everything the grace window still covers to the session just opened, and empties the
	 * buffer either way.
	 *
	 * @return the value replayed, for logging
	 */
	private long replayGraceBuffer(String sessionId)
	{
		final int cutoff = graceCutoff();
		long replayed = 0;
		for (BufferedCharge charge : graceBuffer)
		{
			if (charge.tick >= cutoff)
			{
				activeTask.addSupplyCharge(sessionId, charge.charge);
				replayed += charge.charge.getTotal();
			}
		}
		clearGraceBuffer();
		return replayed;
	}

	/**
	 * Empties the buffer and forgets where the engagement started, so the next one anchors itself
	 * rather than inheriting an anchor from work already accounted for.
	 */
	private void clearGraceBuffer()
	{
		graceBuffer.clear();
		combatStartTick = -1;
	}

	private void pruneGraceBuffer(int now)
	{
		final int cutoff = graceCutoff();
		while (!graceBuffer.isEmpty() && graceBuffer.peekFirst().tick < cutoff)
		{
			graceBuffer.removeFirst();
		}
	}

	private static int toTicks(int seconds)
	{
		// One game tick is 600ms.
		return (seconds * 1000) / 600;
	}

	// ------------------------------------------------------------------
	// Lifecycle and persistence
	// ------------------------------------------------------------------

	@SuppressWarnings("unused")
	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		final GameState state = event.getGameState();

		if (state == GameState.LOGGED_IN)
		{
			// LOGGED_IN is not the login event its name suggests: it fires again after every
			// loading screen — a teleport, a dungeon entrance, a region boundary. Everything
			// below belongs to an actual login, and running it on a region change did real
			// damage. load() replaced the live record with the last persisted copy, discarding
			// up to a persist interval of loot, and closed its sessions underneath a plugin
			// that still believed one was open — after which tickSession() no-oped and
			// openSession() returned early on sessionOpen, so the clock stopped for good. The
			// resyncs were just as wrong: re-baselining across a teleport means the runes that
			// paid for it are never billed, which is exactly what the grace window exists to
			// catch.
			if (loaded)
			{
				return;
			}
			loaded = true;

			load();

			// A task that finished before logging out has no loot still to arrive, and its
			// late-loot window can't span a session. Close it rather than leaving a finished
			// assignment presented as the current one.
			if (activeTask != null && activeTask.isCompleted())
			{
				archiveActiveTask();
			}

			// Carried items aren't readable until now, and anything that changed while
			// logged out must not be charged.
			applyChargeConfig();
			supplyTracker.resync();
			resyncCollectionInventory();
			updateTask();
		}
		else if (state == GameState.LOGIN_SCREEN || state == GameState.HOPPING)
		{
			loaded = false;
			closeSession(state == GameState.HOPPING ? "WORLD_HOP" : "LOGOUT");
			persist();
			// Tick counts don't carry across a session, so anchors measured in ticks are
			// meaningless now. A new kill re-establishes them.
			lastCreditTick = -1;
			lastCreditSessionId = null;
			taskEndTick = -1;
			// The counter is re-read on login; treat the next read as a fresh baseline so
			// logging in never looks like a burst of credited kills.
			lastSlayerCount = -1;
			quiverShotTick = -1;
			specialEnergyLastSeen = -1;
			// Tick-count anchors, so meaningless once the count restarts.
			lastCannonballCount = -1;
			deathSettleTicks = -1;
			supplyTracker.clear();
			pendingLoot.clear();
			recentDeaths.clear();
			creditedNpcDeathTicks.clear();
		}
	}

	@SuppressWarnings("unused")
	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!SlayerTaskLootConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}

		// Config changes arrive from the Swing thread, and rebuilding the panel resolves item
		// names and prices through ItemManager, so hop across before touching game state.
		final boolean trim = "historySize".equals(event.getKey());
		final boolean suppliesToggled = "trackSupplies".equals(event.getKey());
		final boolean lootModeChanged = "lootMode".equals(event.getKey());
		clientThread.invokeLater(() ->
		{
			applyChargeConfig();
			// Stored tasks are rendered once and reused, on the assumption that only the active
			// task changes between refreshes. Settings break that assumption: "Net matching
			// drops" and "Track supplies and profit" both change how every past task reads, and
			// without this the history kept showing the old view until something else happened
			// to rebuild it — which makes the setting look like it does nothing.
			historyViewsDirty = true;
			if (trim)
			{
				trimHistory();
				persist();
			}
			if (suppliesToggled)
			{
				// Rebaseline on the way back in. Without this, everything used while tracking
				// was off would land on the task the moment it's switched on.
				//
				// Only the supply baseline. Collection tracking is independent of this setting,
				// and resyncing it here cleared pendingInventoryGains — so toggling a supply
				// setting silently dropped any loot picked up but not yet matched to its drop,
				// in Collected mode only.
				supplyTracker.resync();
			}
			if (lootModeChanged)
			{
				pendingCollectedDrops.clear();
				resyncCollectionInventory();
			}
			pushToPanel();
		});
	}

	private void persist()
	{
		try
		{
			configManager.setRSProfileConfiguration(SlayerTaskLootConfig.GROUP, KEY_ACTIVE_TASK,
				activeTask == null ? "" : gson.toJson(activeTask));
			configManager.setRSProfileConfiguration(SlayerTaskLootConfig.GROUP, KEY_HISTORY,
				gson.toJson(history, HISTORY_TYPE));
			persistDirty = false;
		}
		catch (Exception ex)
		{
			log.warn("Failed to persist task loot", ex);
		}
	}

	private void load()
	{
		final String activeJson = configManager.getRSProfileConfiguration(
			SlayerTaskLootConfig.GROUP, KEY_ACTIVE_TASK);
		final String historyJson = configManager.getRSProfileConfiguration(
			SlayerTaskLootConfig.GROUP, KEY_HISTORY);

		try
		{
			activeTask = activeJson == null || activeJson.isEmpty()
				? null
				: gson.fromJson(activeJson, TaskLootRecord.class);
			if (activeTask != null)
			{
				activeTask.getAssignmentId();
				activeTask.closeOpenSessions(System.currentTimeMillis(), "CLIENT_RESTART");
			}
		}
		catch (JsonSyntaxException ex)
		{
			log.warn("Discarding malformed active task", ex);
			activeTask = null;
		}

		try
		{
			final List<TaskLootRecord> stored = historyJson == null || historyJson.isEmpty()
				? null
				: gson.fromJson(historyJson, HISTORY_TYPE);
			history = stored == null ? new ArrayList<>() : stored;
		}
		catch (JsonSyntaxException ex)
		{
			log.warn("Discarding malformed task history", ex);
			history = new ArrayList<>();
		}

		historyViewsDirty = true;
		trimHistory();
		pushToPanel();
	}

	// ------------------------------------------------------------------
	// Panel actions
	// ------------------------------------------------------------------

	void resetActiveTask()
	{
		clientThread.invokeLater(() ->
		{
			if (activeTask == null)
			{
				return;
			}
			final TaskLootRecord previous = activeTask;
			closeSession("RESET");
			activeTask = new TaskLootRecord(
				previous.getTaskName(),
				previous.getTaskLocation(),
				previous.getInitialAmount(),
				System.currentTimeMillis());
			lastCreditTick = -1;
			supplyTracker.resync();
			persist();
			pushToPanel();
		});
	}

	void startNewSession()
	{
		clientThread.invokeLater(() ->
		{
			if (activeTask == null || activeTask.isCompleted() || sessionOpen)
			{
				return;
			}

			sessionOpen = true;
			activeSessionId = activeTask.openNewSession(System.currentTimeMillis(), true).getSessionId();
			lastCreditSessionId = activeSessionId;
			lastCreditTick = client.getTickCount();
			// Replayed, not discarded. Starting the session by hand used to throw the grace
			// window away and re-baseline on top, so everything spent getting to the first kill
			// — cannonballs above all, which fire long before the counter moves — was lost. The
			// button says when the session starts, not that the run-up was free.
			replayGraceBuffer(activeSessionId);
			markDirty();
			pushToPanel();
		});
	}

	void endCurrentSession()
	{
		clientThread.invokeLater(() ->
		{
			if (!sessionOpen)
			{
				return;
			}
			closeSession("MANUAL");
			markDirty();
			pushToPanel();
		});
	}

	void resumeLastSession()
	{
		if (activeTask != null && activeTask.latestClosedSession() != null)
		{
			resumeSession(activeTask.latestClosedSession().getSessionId());
		}
	}

	void resumeSession(String sessionId)
	{
		clientThread.invokeLater(() ->
		{
			if (activeTask == null || activeTask.isCompleted() || sessionOpen)
			{
				return;
			}

			final TaskSession previous = activeTask.resumeSession(sessionId, System.currentTimeMillis());
			if (previous == null)
			{
				return;
			}

			activeSessionId = previous.getSessionId();
			lastCreditSessionId = activeSessionId;
			sessionOpen = true;
			lastCreditTick = client.getTickCount();
			// Same as startNewSession(): resuming by hand is still a session opening, and the
			// grace window belongs to it.
			replayGraceBuffer(activeSessionId);
			markDirty();
			pushToPanel();
		});
	}

	void mergeSessions(String sourceId, String targetId)
	{
		clientThread.invokeLater(() ->
		{
			if (activeTask == null || sessionOpen || sourceId == null || targetId == null
				|| sourceId.equals(targetId))
			{
				return;
			}

			if (activeTask.mergeSessions(targetId, sourceId))
			{
				markDirty();
				persist();
				pushToPanel();
			}
		});
	}

	void poolHistoryIntoCurrent(String sourceAssignmentId)
	{
		clientThread.invokeLater(() ->
		{
			final TaskLootRecord source = findHistoryTask(sourceAssignmentId);
			if (activeTask == null || activeTask.isCompleted() || source == null
				|| !sameTask(activeTask, source))
			{
				return;
			}

			activeTask.mergeRecord(source);
			history.remove(source);
			historyViewsDirty = true;
			markDirty();
			persist();
			pushToPanel();
		});
	}

	/**
	 * Pools every other history record of the same assignment into this one.
	 *
	 * <p>Merging was one record at a time, chosen from a dialog. Combining a dozen trips of the
	 * same monster meant a dozen round trips through it, and the choice was rarely a real one:
	 * the records offered were already filtered to those that match, so picking between them
	 * only decided the order they arrived in.
	 */
	void mergeAllHistoryTasks(String targetAssignmentId)
	{
		clientThread.invokeLater(() ->
		{
			final TaskLootRecord target = findHistoryTask(targetAssignmentId);
			if (target == null)
			{
				return;
			}

			// Snapshot first: mergeRecord() folds each source into the target and the source is
			// then dropped, so iterating history itself while removing from it would skip entries.
			final List<TaskLootRecord> sources = new ArrayList<>();
			for (TaskLootRecord candidate : history)
			{
				if (candidate != target && sameTask(target, candidate))
				{
					sources.add(candidate);
				}
			}
			if (sources.isEmpty())
			{
				return;
			}

			for (TaskLootRecord source : sources)
			{
				target.mergeRecord(source);
			}
			history.removeAll(sources);
			historyViewsDirty = true;
			markDirty();
			persist();
			pushToPanel();
			log.debug("Merged {} history records into {}", sources.size(), target.getTaskName());
		});
	}

	void deleteHistoryTask(String assignmentId)
	{
		clientThread.invokeLater(() ->
		{
			final TaskLootRecord task = findHistoryTask(assignmentId);
			if (task != null)
			{
				history.remove(task);
				historyViewsDirty = true;
				persist();
				pushToPanel();
			}
		});
	}

	@Nullable
	private TaskLootRecord findHistoryTask(String assignmentId)
	{
		for (TaskLootRecord task : history)
		{
			if (task.getAssignmentId().equals(assignmentId))
			{
				return task;
			}
		}
		return null;
	}

	/**
	 * Whether two records are the same assignment for the purpose of combining them by hand.
	 *
	 * <p>The monster alone, deliberately. Location is part of assignment <em>identity</em> — it
	 * is what stops two consecutive tasks being read as one — but it isn't what makes two
	 * records worth pooling. The same creature killed in a different place is the same creature,
	 * and combining them is only ever the user asking for it explicitly.
	 */
	private static boolean sameTask(TaskLootRecord left, TaskLootRecord right)
	{
		return Objects.equals(left.getTaskName(), right.getTaskName());
	}

	void clearHistory()
	{
		clientThread.invokeLater(() ->
		{
			history = new ArrayList<>();
			historyViewsDirty = true;
			persist();
			pushToPanel();
		});
	}

	// ------------------------------------------------------------------
	// Panel plumbing
	// ------------------------------------------------------------------

	/**
	 * Resolves names and prices here on the client thread, then hands the panel immutable
	 * views so it never reads game state off the Swing thread.
	 */
	private void pushToPanel()
	{
		if (panel == null)
		{
			return;
		}

		if (client.getGameState() != GameState.LOGGED_IN && activeTask == null && history.isEmpty())
		{
			return;
		}

		final TaskView current = activeTask == null ? null : toView(activeTask);

		// Only the active task changes between refreshes. Re-resolving every stored task's
		// item names and prices on each pass would mean thousands of lookups a few times a
		// second once the history is full, for output that is byte-for-byte identical.
		if (historyViewsDirty)
		{
			final List<TaskView> rebuilt = new ArrayList<>(history.size());
			for (TaskLootRecord record : history)
			{
				rebuilt.add(toView(record));
			}
			historyViews = Collections.unmodifiableList(rebuilt);
			historyViewsDirty = false;
		}

		final List<TaskView> past = historyViews;

		SwingUtilities.invokeLater(() ->
		{
			if (panel != null)
			{
				panel.display(current, past);
			}
		});
	}

	private TaskView toView(TaskLootRecord record)
	{
		final List<TaskView.LootRow> rows = new ArrayList<>();
		long lootValue = 0;
		long supplyValue = record.getSupplyCost();
		Map<Integer, Integer> displayedItems = record.getItems();
		Map<Integer, Long> displayedItemValues = new LinkedHashMap<>();
		final Map<Integer, Long> recordedItemValues = record.getItemValues();
		for (Map.Entry<Integer, Integer> entry : displayedItems.entrySet())
		{
			displayedItemValues.put(entry.getKey(), recordedItemValues.getOrDefault(
				entry.getKey(), (long) itemManager.getItemPrice(entry.getKey()) * entry.getValue()));
		}
		Map<Integer, SupplyEntry> displayedSupplies = new LinkedHashMap<>(record.getSupplies());
		for (Iterator<Map.Entry<Integer, Integer>> it = displayedItems.entrySet().iterator();
			it.hasNext(); )
		{
			final Map.Entry<Integer, Integer> entry = it.next();
			if (isDropExcluded(record.getTaskName(), entry.getKey()))
			{
				it.remove();
				displayedItemValues.remove(entry.getKey());
			}
		}
		for (Iterator<Map.Entry<Integer, SupplyEntry>> it =
			displayedSupplies.entrySet().iterator(); it.hasNext(); )
		{
			final Map.Entry<Integer, SupplyEntry> entry = it.next();
			if (supplyTracker.isLootOnlyItem(entry.getKey()))
			{
				// Older snapshots may already contain prayer remains or ensouled heads as
				// supplies. Hide that invalid charge as well as preventing new ones.
				supplyValue -= entry.getValue().getValue();
				it.remove();
			}
		}

		if (config.trackSupplies() && config.netMatchingDrops())
		{
			final long grossBreakdown = displayedSupplies.values().stream()
				.mapToLong(SupplyEntry::getValue).sum();
			final TaskNetting.Result net = TaskNetting.apply(
				displayedItems, displayedItemValues, displayedSupplies);
			displayedItems = net.getLootQuantities();
			displayedItemValues = net.getLootValues();
			displayedSupplies = net.getSupplies();
			final long netBreakdown = displayedSupplies.values().stream()
				.mapToLong(SupplyEntry::getValue).sum();
			// Preserve any legacy/unitemized supply value outside the visible breakdown.
			supplyValue -= grossBreakdown - netBreakdown;
		}

		for (Map.Entry<Integer, Integer> entry : displayedItems.entrySet())
		{
			final int itemId = entry.getKey();
			final int quantity = entry.getValue();
			final long value = displayedItemValues.getOrDefault(itemId, 0L);
			lootValue += value;

			rows.add(new TaskView.LootRow(itemId, itemName(itemId), quantity, value, ""));
		}

		rows.sort(Comparator.comparingLong(TaskView.LootRow::getValue).reversed());

		final List<TaskView.LootRow> supplyRows = new ArrayList<>();
		for (Map.Entry<Integer, SupplyEntry> entry : displayedSupplies.entrySet())
		{
			final int itemId = entry.getKey();
			final SupplyEntry supply = entry.getValue();

			// Dose families are stored under their largest variant, so the name still carries
			// the "(4)" suffix. Drop it — the row already says the count is in doses.
			String name = itemName(itemId);
			if (supply.isDoseBased())
			{
				final int open = name.lastIndexOf('(');
				if (open > 0)
				{
					name = name.substring(0, open).trim();
				}
			}

			final List<String> breakdown = new ArrayList<>();
			supply.getComponentHundredths().forEach((componentId, hundredths) -> breakdown.add(
				itemName(componentId) + " x" + componentQuantity(hundredths)));

			supplyRows.add(new TaskView.LootRow(
				itemId, name, supply.getQuantity(), supply.getValue(),
				supply.isDoseBased() ? "doses" : "",
				Collections.unmodifiableList(breakdown)));
		}

		supplyRows.sort(Comparator.comparingLong(TaskView.LootRow::getValue).reversed());

		final List<TaskView.SessionView> sessionViews = new ArrayList<>();
		int sessionNumber = 1;
		for (TaskSession session : record.getTaskSessions())
		{
			sessionViews.add(new TaskView.SessionView(
				session.getSessionId(), sessionNumber++, session.getOnTaskTicks(), session.isOpen()));
		}
		final List<TaskView.ExcludedDrop> excludedDrops = new ArrayList<>();
		for (String item : DropExclusions.globalEntries(config.globalExcludedDrops()))
		{
			excludedDrops.add(new TaskView.ExcludedDrop(item, true));
		}
		for (String item : DropExclusions.entriesForTask(
			config.taskExcludedDrops(), record.getTaskName()))
		{
			excludedDrops.add(new TaskView.ExcludedDrop(item, false));
		}

		return new TaskView(
			record.getAssignmentId(),
			record.getTaskName(),
			record.getTaskLocation(),
			record.getKills(),
			record.getAttributedKills(),
			record.getInitialAmount(),
			record.getStartedAt(),
			record.getEndedAt(),
			record.isCompleted(),
			lootValue,
			supplyValue,
			record.getSessions(),
			record.getOnTaskTicks(),
			record == activeTask && sessionOpen,
			record == activeTask && !sessionOpen && record.latestClosedSession() != null,
			Collections.unmodifiableList(rows),
			Collections.unmodifiableList(supplyRows),
			Collections.unmodifiableList(sessionViews),
			Collections.unmodifiableList(excludedDrops));
	}

	private boolean isDropExcluded(String taskName, int itemId)
	{
		return DropExclusions.isExcluded(config.globalExcludedDrops(),
			config.taskExcludedDrops(), taskName, itemName(itemId));
	}

	void excludeDropForTask(String taskName, String itemName)
	{
		configManager.setConfiguration(SlayerTaskLootConfig.GROUP, "taskExcludedDrops",
			DropExclusions.addForTask(config.taskExcludedDrops(), taskName, itemName));
	}

	void excludeDropFromAllTasks(String itemName)
	{
		configManager.setConfiguration(SlayerTaskLootConfig.GROUP, "globalExcludedDrops",
			DropExclusions.addGlobal(config.globalExcludedDrops(), itemName));
	}

	void includeDropForTask(String taskName, String itemName)
	{
		configManager.setConfiguration(SlayerTaskLootConfig.GROUP, "taskExcludedDrops",
			DropExclusions.removeForTask(config.taskExcludedDrops(), taskName, itemName));
	}

	void includeDropForAllTasks(String itemName)
	{
		configManager.setConfiguration(SlayerTaskLootConfig.GROUP, "globalExcludedDrops",
			DropExclusions.removeGlobal(config.globalExcludedDrops(), itemName));
	}

	/**
	 * Renders a component quantity held in hundredths of an item.
	 *
	 * <p>Whole quantities keep the abbreviated form the rest of the panel uses — "Blood rune x1.2K"
	 * — since that is what a long task's rune count looks like. A part-used component drops to
	 * plain decimals instead, because a fraction is small by definition and "x0.4" abbreviates to
	 * nothing useful. Two decimals is what one scythe swing needs to be visible at all: "x0.01".
	 */
	static String componentQuantity(int hundredths)
	{
		if (hundredths % SupplyCharge.COMPONENT_SCALE == 0)
		{
			return QuantityFormatter.quantityToStackSize(hundredths / SupplyCharge.COMPONENT_SCALE);
		}
		return String.format(java.util.Locale.ROOT,
			hundredths % 10 == 0 ? "%.1f" : "%.2f",
			hundredths / (double) SupplyCharge.COMPONENT_SCALE);
	}

	private String itemName(int itemId)
	{
		try
		{
			final ItemComposition comp = itemManager.getItemComposition(itemId);
			if (comp != null && comp.getName() != null && !comp.getName().isEmpty())
			{
				return comp.getName();
			}
		}
		catch (Exception ex)
		{
			// Fall back to the id; a missing composition shouldn't blank the panel.
		}
		return "Item #" + itemId;
	}

	private BufferedImage buildNavIcon()
	{
		return skillIconManager.getSkillImage(Skill.SLAYER, true);
	}

	// ------------------------------------------------------------------
	// Buffered state
	// ------------------------------------------------------------------

	/** Loot held for one tick while we wait to see whether the kill was credited. */
	private static final class PendingLoot
	{
		private final int tick;
		private final int npcIndex;
		private final String npcName;
		private final Collection<ItemStack> items;
		private final LootSource source;
		private final String sessionId;

		private PendingLoot(int tick, int npcIndex, String npcName, Collection<ItemStack> items,
			LootSource source, String sessionId)
		{
			this.tick = tick;
			this.npcIndex = npcIndex;
			this.npcName = npcName;
			this.items = copyLoot(items);
			this.source = source;
			this.sessionId = sessionId;
		}

	}

	/** Snapshot event-owned loot before RuneLite reuses or clears its backing collection. */
	static List<ItemStack> copyLoot(Collection<ItemStack> items)
	{
		return new ArrayList<>(items);
	}

	private enum LootSource
	{
		SERVER,
		GROUND
	}

	/** Supply spend from before a session opened, replayable within the grace window. */
	private static final class BufferedCharge
	{
		private final int tick;
		private final SupplyCharge charge;

		private BufferedCharge(int tick, SupplyCharge charge)
		{
			this.tick = tick;
			this.charge = charge;
		}
	}

	private static final class RecentDeath
	{
		private final int npcIndex;
		private final String npcName;
		private final int tick;
		private final boolean playerRelated;
		private boolean credited;
		private boolean lootClaimed;
		private String sessionId;

		private RecentDeath(int npcIndex, String npcName, int tick, boolean playerRelated)
		{
			this.npcIndex = npcIndex;
			this.npcName = npcName;
			this.tick = tick;
			this.playerRelated = playerRelated;
		}
	}

	private static final class PendingCollectedDrop
	{
		private final int tick;
		private final String sessionId;
		private final int itemId;
		private int quantity;
		private final int unitPrice;

		private PendingCollectedDrop(int tick, String sessionId, int itemId, int quantity,
			int unitPrice)
		{
			this.tick = tick;
			this.sessionId = sessionId;
			this.itemId = itemId;
			this.quantity = quantity;
			this.unitPrice = unitPrice;
		}
	}

	private static final class InventoryGain
	{
		private final int tick;
		private final int itemId;
		private int quantity;

		private InventoryGain(int tick, int itemId, int quantity)
		{
			this.tick = tick;
			this.itemId = itemId;
			this.quantity = quantity;
		}
	}

	/** A local Take action awaiting the matching removal of that ground stack. */
	private static final class PendingPickup
	{
		private final int tick;
		private final int worldViewId;
		private final int plane;
		private final int rawItemId;
		private final int itemId;
		private final int initialQuantity;
		private final int sceneX;
		private final int sceneY;

		private PendingPickup(int tick, int worldViewId, int plane, int rawItemId, int itemId,
			int initialQuantity, int sceneX, int sceneY)
		{
			this.tick = tick;
			this.worldViewId = worldViewId;
			this.plane = plane;
			this.rawItemId = rawItemId;
			this.itemId = itemId;
			this.initialQuantity = initialQuantity;
			this.sceneX = sceneX;
			this.sceneY = sceneY;
		}
	}
}
