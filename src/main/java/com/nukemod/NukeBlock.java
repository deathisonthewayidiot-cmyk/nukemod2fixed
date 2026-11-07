package com.nukemod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.BlockHitResult;

/**
 * A nuclear bomb block.
 *
 * Arm it with flint and steel, a fire charge, or a redstone signal. After a
 * 10 second countdown it detonates: see {@link NukeBlast} for the spherical crater,
 * shockwave and mushroom cloud. Respects the "TNT explodes" game rule.
 */
public class NukeBlock extends Block {
	public static final BooleanProperty ARMED = BooleanProperty.create("armed");

	/** Countdown in ticks (20 ticks = 1 second). */
	public static final int FUSE_TICKS = 200;

	public NukeBlock(Properties properties) {
		super(properties);
		this.registerDefaultState(this.defaultBlockState().setValue(ARMED, false));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(ARMED);
	}

	/** Starts the countdown. Returns true if the nuke was armed by this call. */
	private static boolean arm(Level level, BlockPos pos, BlockState state) {
		if (!(level instanceof ServerLevel serverLevel) || state.getValue(ARMED)) {
			return false;
		}
		if (!serverLevel.getGameRules().get(GameRules.TNT_EXPLODES)) {
			return false;
		}
		serverLevel.setBlock(pos, state.setValue(ARMED, true), Block.UPDATE_ALL);
		serverLevel.scheduleTick(pos, state.getBlock(), FUSE_TICKS);
		return true;
	}

	// --- Ignition: flint and steel / fire charge ---------------------------------------------

	@Override
	public InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hitResult) {
		if (!stack.is(Items.FLINT_AND_STEEL) && !stack.is(Items.FIRE_CHARGE)) {
			return super.useItemOn(stack, state, level, pos, player, hand, hitResult);
		}
		if (level.isClientSide()) {
			return InteractionResult.SUCCESS;
		}
		if (state.getValue(ARMED)) {
			return InteractionResult.PASS;
		}
		if (arm(level, pos, state)) {
			if (!player.getAbilities().instabuild) {
				if (stack.is(Items.FLINT_AND_STEEL)) {
					stack.hurtAndBreak(1, player, hand.asEquipmentSlot());
				} else {
					stack.shrink(1);
				}
			}
			player.sendOverlayMessage(Component.literal("NUKE ARMED - detonation in " + (FUSE_TICKS / 20) + " seconds. RUN."));
			return InteractionResult.SUCCESS;
		}
		return InteractionResult.PASS;
	}

	// --- Ignition: redstone -----------------------------------------------------------------

	@Override
	protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
		if (!oldState.is(state.getBlock()) && level.hasNeighborSignal(pos)) {
			arm(level, pos, state);
		}
	}

	@Override
	protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock, Orientation orientation, boolean movedByPiston) {
		if (level.hasNeighborSignal(pos)) {
			arm(level, pos, state);
		}
	}

	// --- Detonation -------------------------------------------------------------------------

	@Override
	protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
		if (!state.getValue(ARMED)) {
			return;
		}
		NukeBlast.start(level, pos);
	}
}
