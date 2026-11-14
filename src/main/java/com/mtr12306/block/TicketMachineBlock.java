package com.mtr12306.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldView;
import org.jetbrains.annotations.Nullable;

/**
 * 售票机（两格高，国铁自助售票机样式）。
 * 下半格方块实体保存所属车站；右键打开购票 / 报销凭证界面。
 */
public class TicketMachineBlock extends BlockWithEntity {
	public static final DirectionProperty FACING = HorizontalFacingBlock.FACING;
	public static final BooleanProperty UPPER = BooleanProperty.of("upper");

	private static final VoxelShape LOWER = Block.createCuboidShape(0.0, 0.0, 3.0, 16.0, 16.0, 16.0);
	private static final VoxelShape UPPER_SHAPE = Block.createCuboidShape(0.0, 0.0, 3.0, 16.0, 16.0, 16.0);

	public TicketMachineBlock(final Settings settings) {
		super(settings);
		setDefaultState(getDefaultState().with(FACING, Direction.NORTH).with(UPPER, false));
	}

	@Override
	protected void appendProperties(final StateManager.Builder<Block, BlockState> builder) {
		builder.add(FACING, UPPER);
	}

	@Override
	public BlockState getPlacementState(final ItemPlacementContext ctx) {
		return getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing().getOpposite()).with(UPPER, false);
	}

	@Override
	public boolean canPlaceAt(final BlockState state, final WorldView world, final BlockPos pos) {
		if (state.get(UPPER)) {
			return world.getBlockState(pos.down()).isOf(this);
		}
		return world.getBlockState(pos.up()).isAir() || world.getBlockState(pos.up()).isOf(this);
	}

	@Override
	public BlockRenderType getRenderType(final BlockState state) {
		return BlockRenderType.MODEL;
	}

	@Override
	public VoxelShape getOutlineShape(final BlockState state, final BlockView world, final BlockPos pos,
			final ShapeContext context) {
		return state.get(UPPER) ? UPPER_SHAPE : LOWER;
	}

	@Nullable
	@Override
	public BlockEntity createBlockEntity(final BlockPos pos, final BlockState state) {
		// 只有下半格带方块实体
		return state.get(UPPER) ? null : new TicketMachineBlockEntity(pos, state);
	}

	@Override
	public void onPlaced(final World world, final BlockPos pos, final BlockState state, @Nullable final LivingEntity placer,
			final ItemStack itemStack) {
		super.onPlaced(world, pos, state, placer, itemStack);
		if (world.isClient() || state.get(UPPER)) {
			return;
		}
		if (world.getBlockState(pos.up()).isAir()) {
			world.setBlockState(pos.up(), state.with(UPPER, true), Block.NOTIFY_ALL);
		}
	}

	@Override
	public void onStateReplaced(final BlockState state, final World world, final BlockPos pos, final BlockState newState,
			final boolean moved) {
		if (!world.isClient() && !state.isOf(newState.getBlock()) && !newState.isOf(this)) {
			final BlockPos other = state.get(UPPER) ? pos.down() : pos.up();
			if (world.getBlockState(other).isOf(this)) {
				world.setBlockState(other, net.minecraft.block.Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
			}
		}
		super.onStateReplaced(state, world, pos, newState, moved);
	}

	@Override
	public ActionResult onUse(final BlockState state, final World world, final BlockPos pos, final PlayerEntity player,
			final Hand hand, final BlockHitResult hit) {
		if (world.isClient()) {
			return ActionResult.SUCCESS;
		}
		if (!(player instanceof ServerPlayerEntity serverPlayer) || !(world instanceof ServerWorld serverWorld)) {
			return ActionResult.PASS;
		}
		final BlockPos lowerPos = state.get(UPPER) ? pos.down() : pos;
		if (!(world.getBlockEntity(lowerPos) instanceof TicketMachineBlockEntity machine)) {
			return ActionResult.PASS;
		}
		com.mtr12306.net.ModNetworking.openMachine(serverPlayer, lowerPos, machine.stationId(serverWorld, lowerPos));
		serverPlayer.sendMessage(Text.literal("§7售票机 · 可购票与打印报销凭证（需账号）"), true);
		return ActionResult.SUCCESS;
	}
}
