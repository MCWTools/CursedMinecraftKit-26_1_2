package khoa.cmk;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceArgument;
import net.minecraft.commands.arguments.blocks.BlockPredicateArgument;
import net.minecraft.commands.arguments.blocks.BlockStateArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.item.component.DeathProtection;
import net.minecraft.world.item.enchantment.Enchantment;

import java.util.Collection;
import java.util.List;

/**
 * All "cursed prop" commands for the pack. Every command edits the
 * ItemStack currently held in the player's main hand by directly setting
 * data components - no mixins needed for any of these.
 *
 * Minecraft 26.x replaced the old int permission levels (0-4) with
 * PermissionCheck/PermissionSet. Commands.LEVEL_GAMEMASTERS is the
 * equivalent of the old "requires op level 2".
 */
public final class CmkCommands {
	private CmkCommands() {}

	public static void register() {
		CommandRegistrationCallback.EVENT.register(CmkCommands::onRegister);
		// One permanent listener drains deferredActions each tick - Fabric
		// API's Event<T> has no unregister, so a fresh per-call listener
		// (as ServerTickEvents.END_SERVER_TICK.register(...) each command
		// invocation would do) leaks forever instead of running once.
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			Runnable action;
			while ((action = deferredActions.poll()) != null) {
				action.run();
			}
		});
	}

	/** Queue of one-shot actions to run on the next server tick. Used
	 * instead of registering a new tick listener per call since Fabric
	 * API's Event<T> can't be unregistered. */
	private static final java.util.Queue<Runnable> deferredActions = new java.util.concurrent.ConcurrentLinkedQueue<>();

	private static void scheduleNextTick(Runnable action) {
		deferredActions.add(action);
	}

	private static boolean isOp(CommandSourceStack src) {
		return Commands.LEVEL_GAMEMASTERS.check(src.permissions());
	}

	private static void onRegister(CommandDispatcher<CommandSourceStack> dispatcher,
			CommandBuildContext registryAccess, Commands.CommandSelection environment) {

		// Every command lives under /cmk <name> so tab-completing "/cmk"
		// lists the whole pack the way other mods' "/modid" roots do.
		// The old bare top-level names (/camouflage, /showtotempop, etc.)
		// are also still registered further down for muscle memory/back
		// compat - both point at the same executor methods.
		var camouflageNode = Commands.literal("camouflage")
			.requires(CmkCommands::isOp)
			.then(Commands.argument("item", StringArgumentType.greedyString())
				.executes(CmkCommands::camouflage));

		var eatableNode = Commands.literal("eatable")
			.requires(CmkCommands::isOp)
			.then(Commands.argument("value", BoolArgumentType.bool())
				.executes(CmkCommands::eatable));

		var actAsTotemNode = Commands.literal("actastotem")
			.requires(CmkCommands::isOp)
			.executes(CmkCommands::actAsTotem);

		var maxStackNode = Commands.literal("maxstack")
			.requires(CmkCommands::isOp)
			.then(Commands.argument("size", IntegerArgumentType.integer(1, 99))
				.executes(CmkCommands::maxStack));

		// Unlike /maxstack (doesn't touch the held count), this strips
		// MAX_DAMAGE/DAMAGE if present (vanilla rejects an item being both
		// damageable and stackable - see ItemStack#validateComponents,
		// "cannot be both damageable and stackable"), and immediately fills
		// the slot to the new max so a normally-unstackable item (tools,
		// etc.) becomes an actual stack of that size right away. Capped at
		// 99 - going higher runs into other vanilla assumptions (inventory
		// sync, other mods reading counts) that misbehave outside that range.
		var stackNode = Commands.literal("stack")
			.requires(CmkCommands::isOp)
			.then(Commands.argument("size", IntegerArgumentType.integer(1, 99))
				.executes(CmkCommands::stack));

		// Sets remaining durability on the held item. If the item has no
		// max damage (unbreakable/non-damageable item), this is a no-op,
		// not an error.
		var durabilityNode = Commands.literal("durability")
			.requires(CmkCommands::isOp)
			.then(Commands.argument("durability", IntegerArgumentType.integer(0))
				.executes(CmkCommands::damagedItem));

		// likeAnvil=true sets DataComponents.CUSTOM_NAME, same component an
		// anvil rename sets - italic tooltip name, colored/italic death
		// messages, everything vanilla does for a custom-named item, for
		// free. likeAnvil=false sets DataComponents.ITEM_NAME instead, which
		// only overrides the plain display name with no italic/custom-name
		// styling (same component /camouflage already uses for its name copy).
		var renameNode = Commands.literal("rename")
			.requires(CmkCommands::isOp)
			.then(Commands.argument("name", StringArgumentType.string())
				.then(Commands.argument("likeAnvil", BoolArgumentType.bool())
					.executes(CmkCommands::rename)));

		// Global (not per-item) toggle - see BlockDestroySpeedMixin. When on,
		// every normally-unbreakable block (bedrock, barrier, etc.) mines at
		// the mixin's configured hardness (0.5, obsidian-tier) instead of
		// being un-diggable.
		var allBedrockBreakableNode = Commands.literal("allbedrockbreakable")
			.requires(CmkCommands::isOp)
			.then(Commands.argument("value", BoolArgumentType.bool())
				.executes(CmkCommands::allBedrockBreakable));

		// Purely visual - broadcasts the same entity status event vanilla
		// uses when a totem of undying triggers (particle burst, the
		// "shhk" pop sound, and the client's totem-pop screen animation),
		// without actually protecting the player from death. The item id
		// only controls which item model shows in the pop animation; the
		// player's actual held item/inventory is never touched.
		var showTotemPopNode = Commands.literal("showtotempop")
			.requires(CmkCommands::isOp)
			.executes(CmkCommands::showTotemPopDefault)
			.then(Commands.argument("item", StringArgumentType.greedyString())
				.executes(CmkCommands::showTotemPop));

		// /killwithmsg <message> - instant, unblockable kill on the caller with
		// a custom death message (chat + death screen). Deliberately has NO
		// isOp requirement so it works in a singleplayer world with cheats off.
		var killWithMsgNode = Commands.literal("killwithmsg")
			.then(Commands.argument("message", StringArgumentType.greedyString())
				.executes(CmkCommands::killWithMsg));

		dispatcher.register(camouflageNode);
		dispatcher.register(eatableNode);
		dispatcher.register(actAsTotemNode);
		dispatcher.register(maxStackNode);
		dispatcher.register(stackNode);
		dispatcher.register(durabilityNode);
		dispatcher.register(renameNode);
		dispatcher.register(allBedrockBreakableNode);
		dispatcher.register(showTotemPopNode);
		dispatcher.register(killWithMsgNode);

		// /cmkfill <from> <to> <block> [mode] [filter]
		// Same argument shape as vanilla /fill (replace/keep/outline/hollow/
		// destroy/strict, replace's optional filter predicate), but every
		// block it actually changes gets snapshotted first so /cmkundofill
		// can reverse it later. Vanilla /fill has no such history, which is
		// the whole reason this exists.
		dispatcher.register(Commands.literal("cmkfill")
			.requires(CmkCommands::isOp)
			.then(Commands.argument("from", BlockPosArgument.blockPos())
				.then(Commands.argument("to", BlockPosArgument.blockPos())
					.then(Commands.argument("block", BlockStateArgument.block(registryAccess))
						.executes(c -> cmkFill(c, FillMode.REPLACE, null))
						.then(Commands.literal("replace")
							.executes(c -> cmkFill(c, FillMode.REPLACE, null))
							.then(Commands.argument("filter", BlockPredicateArgument.blockPredicate(registryAccess))
								.executes(c -> cmkFill(c, FillMode.REPLACE, BlockPredicateArgument.getBlockPredicate(c, "filter")))))
						.then(Commands.literal("keep")
							.executes(c -> cmkFill(c, FillMode.KEEP, null)))
						.then(Commands.literal("outline")
							.executes(c -> cmkFill(c, FillMode.OUTLINE, null)))
						.then(Commands.literal("hollow")
							.executes(c -> cmkFill(c, FillMode.HOLLOW, null)))
						.then(Commands.literal("destroy")
							.executes(c -> cmkFill(c, FillMode.DESTROY, null)))))));

		// /cmkundofill - reverses the most recent /cmkfill this player ran.
		// Never recorded as a new fill entry itself, so repeated undo walks
		// straight back through real history instead of looping on its own
		// output.
		dispatcher.register(Commands.literal("cmkundofill")
			.requires(CmkCommands::isOp)
			.executes(CmkCommands::cmkUndoFill));

		// /cmkredofill - reapplies the most recently undone /cmkfill.
		// Cleared automatically the moment a new /cmkfill runs, same as a
		// normal editor's redo stack.
		dispatcher.register(Commands.literal("cmkredofill")
			.requires(CmkCommands::isOp)
			.executes(CmkCommands::cmkRedoFill));

		// /cmkenchant <targets> <enchantment> [level]
		// A completely separate command from vanilla /enchant - not a mixin
		// on EnchantCommand. Vanilla's /enchant is mixed into by
		// fabric-item-api-v1 (its AllowEnchantingEvent hook on
		// Enchantment#canEnchant), and Mixin only allows one @Redirect per
		// bytecode call site - fighting over that call crashes
		// EnchantCommand's class transform entirely (see the datapack/world
		// load crash from earlier). Writing a fresh command instead of
		// patching the vanilla one sidesteps that fight completely: no
		// conflict is possible because nothing else targets this class.
		//
		// Same shape as vanilla /enchant (same argument types, same
		// ResourceArgument.getEnchantment lookup), but skips every
		// restriction: no level cap, no canEnchant() item-category check,
		// no isEnchantmentCompatible() conflict check. Any enchant on any
		// item at any level.
		dispatcher.register(Commands.literal("cmkenchant")
			.requires(CmkCommands::isOp)
			.then(Commands.argument("targets", EntityArgument.entities())
				.then(Commands.argument("enchantment", ResourceArgument.resource(registryAccess, Registries.ENCHANTMENT))
					.executes(c -> cmkEnchant(c, 1))
					.then(Commands.argument("level", IntegerArgumentType.integer(0))
						.executes(c -> cmkEnchant(c, IntegerArgumentType.getInteger(c, "level")))))));

		// /cmk <subcommand> - the grouping root. Brigadier command nodes are
		// single-parent, so each subcommand needs its own fresh builder
		// instance here rather than reusing the ones registered as top-level
		// commands above (attaching an already-registered node under a
		// second parent silently no-ops instead of aliasing it).
		dispatcher.register(Commands.literal("cmk")
			.then(Commands.literal("camouflage")
				.requires(CmkCommands::isOp)
				.then(Commands.argument("item", StringArgumentType.greedyString())
					.executes(CmkCommands::camouflage)))
			.then(Commands.literal("eatable")
				.requires(CmkCommands::isOp)
				.then(Commands.argument("value", BoolArgumentType.bool())
					.executes(CmkCommands::eatable)))
			.then(Commands.literal("actastotem")
				.requires(CmkCommands::isOp)
				.executes(CmkCommands::actAsTotem))
			.then(Commands.literal("maxstack")
				.requires(CmkCommands::isOp)
				.then(Commands.argument("size", IntegerArgumentType.integer(1, 99))
					.executes(CmkCommands::maxStack)))
			.then(Commands.literal("stack")
				.requires(CmkCommands::isOp)
				.then(Commands.argument("size", IntegerArgumentType.integer(1, 99))
					.executes(CmkCommands::stack)))
			.then(Commands.literal("durability")
				.requires(CmkCommands::isOp)
				.then(Commands.argument("durability", IntegerArgumentType.integer(0))
					.executes(CmkCommands::damagedItem)))
			.then(Commands.literal("rename")
				.requires(CmkCommands::isOp)
				.then(Commands.argument("name", StringArgumentType.string())
					.then(Commands.argument("likeAnvil", BoolArgumentType.bool())
						.executes(CmkCommands::rename))))
			.then(Commands.literal("allbedrockbreakable")
				.requires(CmkCommands::isOp)
				.then(Commands.argument("value", BoolArgumentType.bool())
					.executes(CmkCommands::allBedrockBreakable)))
			.then(Commands.literal("showtotempop")
				.requires(CmkCommands::isOp)
				.executes(CmkCommands::showTotemPopDefault)
				.then(Commands.argument("item", StringArgumentType.greedyString())
					.executes(CmkCommands::showTotemPop)))
			.then(Commands.literal("killwithmsg")
				.then(Commands.argument("message", StringArgumentType.greedyString())
					.executes(CmkCommands::killWithMsg)))
			.then(Commands.literal("fill")
				.requires(CmkCommands::isOp)
				.then(Commands.argument("from", BlockPosArgument.blockPos())
					.then(Commands.argument("to", BlockPosArgument.blockPos())
						.then(Commands.argument("block", BlockStateArgument.block(registryAccess))
							.executes(c -> cmkFill(c, FillMode.REPLACE, null))
							.then(Commands.literal("replace")
								.executes(c -> cmkFill(c, FillMode.REPLACE, null))
								.then(Commands.argument("filter", BlockPredicateArgument.blockPredicate(registryAccess))
									.executes(c -> cmkFill(c, FillMode.REPLACE, BlockPredicateArgument.getBlockPredicate(c, "filter")))))
							.then(Commands.literal("keep")
								.executes(c -> cmkFill(c, FillMode.KEEP, null)))
							.then(Commands.literal("outline")
								.executes(c -> cmkFill(c, FillMode.OUTLINE, null)))
							.then(Commands.literal("hollow")
								.executes(c -> cmkFill(c, FillMode.HOLLOW, null)))
							.then(Commands.literal("destroy")
								.executes(c -> cmkFill(c, FillMode.DESTROY, null)))))))
			.then(Commands.literal("undofill")
				.requires(CmkCommands::isOp)
				.executes(CmkCommands::cmkUndoFill))
			.then(Commands.literal("redofill")
				.requires(CmkCommands::isOp)
				.executes(CmkCommands::cmkRedoFill))
			.then(Commands.literal("enchant")
				.requires(CmkCommands::isOp)
				.then(Commands.argument("targets", EntityArgument.entities())
					.then(Commands.argument("enchantment", ResourceArgument.resource(registryAccess, Registries.ENCHANTMENT))
						.executes(c -> cmkEnchant(c, 1))
						.then(Commands.argument("level", IntegerArgumentType.integer(0))
							.executes(c -> cmkEnchant(c, IntegerArgumentType.getInteger(c, "level"))))))));
	}

	// ---------- helpers ----------

	private static void fail(CommandSourceStack src, String msg) {
		src.sendFailure(Component.literal(msg));
	}

	/** Returns the item held in the caller's main hand, or null (and reports failure) if empty. */
	private static ItemStack requireHeldStack(CommandSourceStack src) throws CommandSyntaxException {
		Player player = src.getPlayerOrException();
		ItemStack stack = player.getMainHandItem();
		if (stack.isEmpty()) {
			fail(src, "You must be holding an item.");
			return null;
		}
		return stack;
	}

	// ---------- /camouflage ----------

	private static int camouflage(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		try {
			ItemStack stack = requireHeldStack(src);
			if (stack == null) return 0;

			String rawId = StringArgumentType.getString(ctx, "item");
			Identifier id = Identifier.tryParse(rawId);
			if (id == null) {
				fail(src, "Invalid item id: " + rawId);
				return 0;
			}

			if (BuiltInRegistries.ITEM.getOptional(id).isEmpty()) {
				fail(src, "Unknown item id: " + id);
				return 0;
			}

			// ITEM_MODEL only changes which model/texture the client renders
			// for this stack. The stack's real registry item, id, and
			// behavior are completely untouched. No resource pack needed.
			stack.set(DataComponents.ITEM_MODEL, id);

			// ITEM_MODEL alone doesn't touch the displayed name - copy the
			// target item's vanilla name too so it reads as fully disguised.
			Item targetItem = BuiltInRegistries.ITEM.getOptional(id).get();
			stack.set(DataComponents.ITEM_NAME, new ItemStack(targetItem).getHoverName());

			src.sendSuccess(() -> Component.literal("Camouflaged as " + id), false);
			return 1;
		} catch (CommandSyntaxException e) {
			fail(src, "This command requires a player.");
			return 0;
		}
	}

	// ---------- /eatable ----------

	private static int eatable(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		try {
			ItemStack stack = requireHeldStack(src);
			if (stack == null) return 0;

			boolean value = BoolArgumentType.getBool(ctx, "value");

			if (value) {
				// FOOD alone is NOT enough - CONSUMABLE is what actually
				// lets the player right-click and play the eat animation.
				stack.set(DataComponents.FOOD, new FoodProperties(
					4,      // nutrition
					0.2f,   // saturation modifier
					false   // canAlwaysEat - true would let you eat while full
				));
				stack.set(DataComponents.CONSUMABLE, new Consumable(
					1.6f,                     // consumeSeconds
					ItemUseAnimation.EAT,
					SoundEvents.GENERIC_EAT,  // already a Holder<SoundEvent>
					true,                     // hasConsumeParticles
					List.of()                 // onConsumeEffects
				));
			} else {
				stack.remove(DataComponents.FOOD);
				stack.remove(DataComponents.CONSUMABLE);
			}

			src.sendSuccess(() -> Component.literal("Eatable set to " + value), false);
			return 1;
		} catch (CommandSyntaxException e) {
			fail(src, "This command requires a player.");
			return 0;
		}
	}

	// ---------- /actastotem ----------

	private static int actAsTotem(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		try {
			ItemStack stack = requireHeldStack(src);
			if (stack == null) return 0;

			// Empty effects list = pop/sound/particle behavior identical to
			// a vanilla totem of undying, just without the regen/fire-resist
			// buffs. Swap in DeathProtection.TOTEM_OF_UNDYING for the full
			// vanilla buff set instead, if you want that too.
			stack.set(DataComponents.DEATH_PROTECTION, new DeathProtection(List.of()));

			src.sendSuccess(() -> Component.literal("This item now acts as a totem."), false);
			return 1;
		} catch (CommandSyntaxException e) {
			fail(src, "This command requires a player.");
			return 0;
		}
	}

	// ---------- /damageditem ----------

	private static int damagedItem(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		try {
			ItemStack stack = requireHeldStack(src);
			if (stack == null) return 0;

			int wantDurability = IntegerArgumentType.getInteger(ctx, "durability");

			Integer maxDamage = stack.get(DataComponents.MAX_DAMAGE);
			if (maxDamage == null || maxDamage <= 0) {
				// Item has no durability bar at all - not an error, just skip.
				src.sendSuccess(() -> Component.literal("This item has no durability, nothing changed."), false);
				return 1;
			}

			int clampedDurability = Math.max(0, Math.min(wantDurability, maxDamage));
			int damage = maxDamage - clampedDurability;
			stack.set(DataComponents.DAMAGE, damage);

			src.sendSuccess(() -> Component.literal(
				"Durability set to " + clampedDurability + "/" + maxDamage
			), false);
			return 1;
		} catch (CommandSyntaxException e) {
			fail(src, "This command requires a player.");
			return 0;
		}
	}

	// ---------- /rename ----------

	private static int rename(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		try {
			ItemStack stack = requireHeldStack(src);
			if (stack == null) return 0;

			String name = StringArgumentType.getString(ctx, "name");
			boolean likeAnvil = BoolArgumentType.getBool(ctx, "likeAnvil");

			if (likeAnvil) {
				// Real anvil-rename component. getStyledHoverName() auto-italicizes
				// whenever CUSTOM_NAME is present, and every vanilla message that
				// reads the item's name (death messages included) picks this up
				// too - no manual color/formatting needed.
				stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
				stack.remove(DataComponents.ITEM_NAME);
			} else {
				// Plain display-name override, no anvil styling - same
				// component /camouflage uses to copy a name across.
				stack.set(DataComponents.ITEM_NAME, Component.literal(name));
				stack.remove(DataComponents.CUSTOM_NAME);
			}

			src.sendSuccess(() -> Component.literal("Renamed to \"" + name + "\" (anvil style: " + likeAnvil + ")"), false);
			return 1;
		} catch (CommandSyntaxException e) {
			fail(src, "This command requires a player.");
			return 0;
		}
	}

	// ---------- /killwithmsg ----------

	private static int killWithMsg(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		try {
			ServerPlayer player = src.getPlayerOrException();
			if (player.isDeadOrDying()) {
				fail(src, "You are already dead.");
				return 0;
			}

			Component message = Component.literal(StringArgumentType.getString(ctx, "message"));
			ServerLevel level = player.level();

			// generic_kill is in bypasses_invulnerability, so creative mode,
			// totems/death protection and Resistance don't apply - same
			// damage type vanilla /kill uses. Only the death message differs.
			DamageSource base = player.damageSources().genericKill();
			DamageSource source = new CmkDeathSource(base.typeHolder(), message);

			player.hurtServer(level, source, Float.MAX_VALUE);

			// Safety net if something (another mod) swallowed the hit: force
			// the death through directly. recordDamage first so the combat
			// tracker has an entry and getDeathMessage() uses our override
			// instead of falling back to the generic message.
			if (!player.isDeadOrDying()) {
				player.getCombatTracker().recordDamage(source, Float.MAX_VALUE);
				player.setHealth(0.0F);
				player.die(source);
			}
			return 1;
		} catch (CommandSyntaxException e) {
			fail(src, "This command requires a player.");
			return 0;
		}
	}

	// ---------- /showtotempop ----------

	/**
	 * Entity status/event id 35 - the same one LivingEntity.checkTotemDeathProtection
	 * broadcasts when a real totem of undying saves the entity. Client-side,
	 * ClientPacketListener handles this by calling findTotem(player), which scans
	 * the player's hands/inventory for an item carrying the DEATH_PROTECTION
	 * component, and passes whatever it finds to GameRenderer.displayItemActivation
	 * (the pop animation) - falling back to a hardcoded Items.TOTEM_OF_UNDYING
	 * ItemStack only if nothing with that component is found.
	 *
	 * That fallback is why just swapping the held item without the component
	 * doesn't work - the animation always renders the actual totem of undying
	 * icon in that case, regardless of what's in hand. Setting DEATH_PROTECTION
	 * on the temp stack is what makes findTotem pick it up and pop the requested
	 * item's own icon instead.
	 */
	private static final byte TOTEM_POP_ENTITY_EVENT = 35;

	private static int showTotemPop(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		try {
			Player player = src.getPlayerOrException();

			String rawId = StringArgumentType.getString(ctx, "item");
			Identifier id = Identifier.tryParse(rawId);
			if (id == null) {
				fail(src, "Invalid item id: " + rawId);
				return 0;
			}

			var itemOptional = BuiltInRegistries.ITEM.getOptional(id);
			if (itemOptional.isEmpty()) {
				fail(src, "Unknown item id: " + id);
				return 0;
			}

			// Needs DEATH_PROTECTION set or findTotem won't pick it up and
			// the client falls back to rendering the real totem icon.
			ItemStack fakeTotemStack = new ItemStack(itemOptional.get());
			fakeTotemStack.set(DataComponents.DEATH_PROTECTION, new DeathProtection(List.of()));

			runTotemPop(player, fakeTotemStack);

			src.sendSuccess(() -> Component.literal("Showed totem pop effect using " + id), false);
			return 1;
		} catch (CommandSyntaxException e) {
			fail(src, "This command requires a player.");
			return 0;
		}
	}

	// ---------- /showtotempop (no item arg) ----------

	/**
	 * No-arg variant: same pop effect, but with the player's real held item
	 * untouched. findTotem finds nothing with DEATH_PROTECTION on the
	 * player (unless they're genuinely holding/wearing a real totem), so
	 * the client falls back to its hardcoded vanilla totem icon - which is
	 * exactly the "normal" totem pop look this variant is meant to give.
	 */
	private static int showTotemPopDefault(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		try {
			Player player = src.getPlayerOrException();
			player.level().broadcastEntityEvent(player, TOTEM_POP_ENTITY_EVENT);
			src.sendSuccess(() -> Component.literal("Showed totem pop effect."), false);
			return 1;
		} catch (CommandSyntaxException e) {
			fail(src, "This command requires a player.");
			return 0;
		}
	}

	/**
	 * Swaps in fakeStack, forces the fake item to actually reach the
	 * player's OWN client (see below for why that step was missing),
	 * broadcasts the pop event, then restores the real item two ticks
	 * later.
	 *
	 * Root cause of "still shows default totem even with an item held":
	 * player.setItemInHand() only rewrites the ItemStack in the server's
	 * copy of the inventory slot. That's enough for OTHER players to see
	 * the change (they get an equipment-sync packet, since the game
	 * assumes it needs to tell viewers what an entity is holding), but it
	 * is NOT enough for the item's OWNER to see it: the local player's
	 * own hotbar/hand slot is client-authoritative for rendering
	 * purposes, and the client only relearns "what's actually in my
	 * hand" from the server via explicit inventory slot-sync packets
	 * (container slot updates), not from the general per-tick equipment
	 * broadcast. Since this command never sent one of those, the caster's
	 * own client kept thinking it was holding the real item (e.g. the
	 * torch) the entire time - so when the entity-event triggered
	 * findTotem() on that same client, it scanned the torch, found no
	 * DEATH_PROTECTION component on it, and fell back to the hardcoded
	 * vanilla totem icon. Other players watching *would* have seen the
	 * correct fake item model, just not the caster themselves.
	 *
	 * Fix: after setting the fake stack, force a full inventory resync to
	 * the caster's own connection (serverPlayer.getInventory().setChanged()
	 * plus containerMenu.broadcastFullState()) so their own client is
	 * guaranteed to have the fake item applied locally before the pop
	 * event fires.
	 */
	private static void runTotemPop(Player player, ItemStack fakeStack) {
		ItemStack realHeldStack = player.getMainHandItem();

		player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, fakeStack);

		if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
			// Forces an immediate slot-sync packet to the player's own
			// connection so THEIR client also learns about the fake item,
			// not just other players watching them.
			serverPlayer.getInventory().setChanged();
			serverPlayer.containerMenu.broadcastFullState();
		}

		player.level().broadcastEntityEvent(player, TOTEM_POP_ENTITY_EVENT);

		scheduleNextTick(() -> scheduleNextTick(() -> {
			if (!player.isRemoved()) {
				player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, realHeldStack);
				if (player instanceof net.minecraft.server.level.ServerPlayer sp) {
					sp.getInventory().setChanged();
					sp.containerMenu.broadcastFullState();
				}
			}
		}));
	}

	// ---------- /cmkfill / /cmkundofill / /cmkredofill ----------

	private enum FillMode { REPLACE, KEEP, OUTLINE, HOLLOW, DESTROY }

	/** Vanilla /fill's own safety cap on volume, mirrored here so /cmkfill
	 * can't be used to silently freeze the server on a huge region. */
	private static final int MAX_FILL_VOLUME = 32768;

	private static int cmkFill(CommandContext<CommandSourceStack> ctx, FillMode mode,
			java.util.function.Predicate<net.minecraft.world.level.block.state.pattern.BlockInWorld> filter) throws CommandSyntaxException {
		CommandSourceStack src = ctx.getSource();
		net.minecraft.server.level.ServerLevel level = src.getLevel();

		BlockPos from = BlockPosArgument.getLoadedBlockPos(ctx, "from");
		BlockPos to = BlockPosArgument.getLoadedBlockPos(ctx, "to");
		net.minecraft.commands.arguments.blocks.BlockInput input = BlockStateArgument.getBlock(ctx, "block");

		int minX = Math.min(from.getX(), to.getX());
		int minY = Math.min(from.getY(), to.getY());
		int minZ = Math.min(from.getZ(), to.getZ());
		int maxX = Math.max(from.getX(), to.getX());
		int maxY = Math.max(from.getY(), to.getY());
		int maxZ = Math.max(from.getZ(), to.getZ());

		long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
		if (volume > MAX_FILL_VOLUME) {
			fail(src, "Area too large (" + volume + " blocks, max " + MAX_FILL_VOLUME + ").");
			return 0;
		}

		List<CmkFillHistory.BlockChange> changes = new java.util.ArrayList<>();

		for (int x = minX; x <= maxX; x++) {
			for (int y = minY; y <= maxY; y++) {
				for (int z = minZ; z <= maxZ; z++) {
					BlockPos pos = new BlockPos(x, y, z);

					boolean isEdge = mode == FillMode.HOLLOW || mode == FillMode.OUTLINE
						? (x == minX || x == maxX || y == minY || y == maxY || z == minZ || z == maxZ)
						: false;

					// KEEP: only touch currently-air positions.
					if (mode == FillMode.KEEP && !level.isEmptyBlock(pos)) continue;
					// OUTLINE: only the shell, interior untouched.
					if (mode == FillMode.OUTLINE && !isEdge) continue;
					// HOLLOW: shell gets the given block, interior gets air.
					// DESTROY: break the existing block (drops items) before placing.

					net.minecraft.world.level.block.state.BlockState oldState = level.getBlockState(pos);

					net.minecraft.commands.arguments.blocks.BlockInput toPlace = input;
					if (mode == FillMode.HOLLOW && !isEdge) {
						toPlace = new net.minecraft.commands.arguments.blocks.BlockInput(
							net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),
							java.util.Collections.emptySet(), null);
					}

					if (mode == FillMode.REPLACE && filter != null) {
						net.minecraft.world.level.block.state.pattern.BlockInWorld blockInWorld =
							new net.minecraft.world.level.block.state.pattern.BlockInWorld(level, pos, true);
						if (!filter.test(blockInWorld)) continue;
					}

					if (mode == FillMode.DESTROY) {
						level.destroyBlock(pos, true);
					}

					boolean placed = toPlace.place(level, pos, 2);
					if (!placed) continue;

					net.minecraft.world.level.block.state.BlockState newState = level.getBlockState(pos);
					if (newState.equals(oldState)) continue;

					changes.add(new CmkFillHistory.BlockChange(pos.immutable(), oldState, newState));
				}
			}
		}

		if (changes.isEmpty()) {
			fail(src, "No blocks changed.");
			return 0;
		}

		java.util.UUID playerId;
		try {
			playerId = src.getPlayerOrException().getUUID();
			CmkFillHistory.recordFill(playerId, new CmkFillHistory.FillSnapshot(changes));
		} catch (CommandSyntaxException ignored) {
			// Console/command-block sender - fill still applies, just not undoable.
		}

		final int count = changes.size();
		src.sendSuccess(() -> Component.literal("Filled " + count + " blocks (undoable with /cmkundofill)"), true);
		return count;
	}

	/** Re-applies every recorded block change from a snapshot, without recording a new history entry. */
	private static void applySnapshot(net.minecraft.server.level.ServerLevel level, CmkFillHistory.FillSnapshot snapshot) {
		for (CmkFillHistory.BlockChange change : snapshot.changes()) {
			level.setBlock(change.pos(), change.newState(), 2);
		}
	}

	private static int cmkUndoFill(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		try {
			Player player = src.getPlayerOrException();
			java.util.UUID playerId = player.getUUID();

			CmkFillHistory.FillSnapshot snapshot = CmkFillHistory.popUndo(playerId);
			if (snapshot == null) {
				fail(src, "Nothing to undo.");
				return 0;
			}

			// Apply the inverse (new->old) directly via setBlock - this is
			// NOT routed through recordFill, so it never becomes a new
			// undoable fill entry itself. It's pushed onto the redo stack
			// as-is (original old/new pair) so /cmkredofill can reapply
			// the forward direction later.
			applySnapshot(src.getLevel(), CmkFillHistory.inverse(snapshot));
			CmkFillHistory.pushRedo(playerId, snapshot);

			final int count = snapshot.changes().size();
			src.sendSuccess(() -> Component.literal("Undid fill (" + count + " blocks restored)"), true);
			return count;
		} catch (CommandSyntaxException e) {
			fail(src, "This command requires a player.");
			return 0;
		}
	}

	private static int cmkRedoFill(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		try {
			Player player = src.getPlayerOrException();
			java.util.UUID playerId = player.getUUID();

			CmkFillHistory.FillSnapshot snapshot = CmkFillHistory.popRedo(playerId);
			if (snapshot == null) {
				fail(src, "Nothing to redo.");
				return 0;
			}

			// Re-apply the original forward direction, then push it back
			// onto the undo stack (not through recordFill) so undo/redo
			// can keep toggling back and forth without ever duplicating
			// or looping the history.
			applySnapshot(src.getLevel(), snapshot);
			CmkFillHistory.pushUndo(playerId, snapshot);

			final int count = snapshot.changes().size();
			src.sendSuccess(() -> Component.literal("Redid fill (" + count + " blocks reapplied)"), true);
			return count;
		} catch (CommandSyntaxException e) {
			fail(src, "This command requires a player.");
			return 0;
		}
	}

	// ---------- /cmkenchant ----------

	private static int cmkEnchant(CommandContext<CommandSourceStack> ctx, int level) throws CommandSyntaxException {
		CommandSourceStack src = ctx.getSource();
		Collection<? extends Entity> targets = EntityArgument.getEntities(ctx, "targets");
		Holder<Enchantment> enchantmentHolder = ResourceArgument.getEnchantment(ctx, "enchantment");

		int success = 0;
		for (Entity entity : targets) {
			if (!(entity instanceof LivingEntity target)) {
				if (targets.size() == 1) {
					fail(src, entity.getName().getString() + " is not a living entity.");
				}
				continue;
			}
			ItemStack item = target.getMainHandItem();
			if (item.isEmpty()) {
				if (targets.size() == 1) {
					fail(src, target.getName().getString() + " has no item in its main hand.");
				}
				continue;
			}

			// No getMaxLevel() check, no canEnchant() item-category check,
			// no isEnchantmentCompatible() conflict check - straight to
			// applying it. ItemStack#enchant itself only clamps at whatever
			// ItemEnchantments.Mutable's ceiling is (see
			// ItemEnchantmentsMutableLevelCapMixin - 65536 by default).
			item.enchant(enchantmentHolder, level);
			success++;
		}

		if (success == 0) {
			fail(src, "Nothing happened.");
			return 0;
		}

		final int finalLevel = level;
		if (targets.size() == 1) {
			src.sendSuccess(() -> Component.translatable("commands.enchant.success.single",
				Enchantment.getFullname(enchantmentHolder, finalLevel), targets.iterator().next().getDisplayName()), true);
		} else {
			src.sendSuccess(() -> Component.translatable("commands.enchant.success.multiple",
				Enchantment.getFullname(enchantmentHolder, finalLevel), targets.size()), true);
		}
		return success;
	}

	// ---------- /allbedrockbreakable ----------

	private static int allBedrockBreakable(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		boolean value = BoolArgumentType.getBool(ctx, "value");
		CmkState.allBedrockBreakable = value;
		src.sendSuccess(() -> Component.literal("All bedrock-hardness blocks breakable: " + value + " (hardness 0.5)"), true);
		return 1;
	}

	// ---------- /stack ----------

	private static int stack(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		try {
			ItemStack stack = requireHeldStack(src);
			if (stack == null) return 0;

			int size = IntegerArgumentType.getInteger(ctx, "size");

			// Damageable + stackable(>1) is rejected by vanilla's own
			// validateComponents check, so drop durability data first -
			// this is what actually turns a normally-unstackable tool/weapon
			// into something that can hold a real stack.
			stack.remove(DataComponents.MAX_DAMAGE);
			stack.remove(DataComponents.DAMAGE);

			stack.set(DataComponents.MAX_STACK_SIZE, size);
			stack.setCount(size);

			src.sendSuccess(() -> Component.literal("Max stack size set to " + size + " and slot filled to " + size), false);
			return 1;
		} catch (CommandSyntaxException e) {
			fail(src, "This command requires a player.");
			return 0;
		}
	}

	// ---------- /maxstack ----------

	private static int maxStack(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		try {
			ItemStack stack = requireHeldStack(src);
			if (stack == null) return 0;

			int size = IntegerArgumentType.getInteger(ctx, "size");
			stack.set(DataComponents.MAX_STACK_SIZE, size);

			src.sendSuccess(() -> Component.literal("Max stack size set to " + size), false);
			return 1;
		} catch (CommandSyntaxException e) {
			fail(src, "This command requires a player.");
			return 0;
		}
	}

}
