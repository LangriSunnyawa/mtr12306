package com.mtr12306.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.StateManager;
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
import org.jetbrains.annotations.Nullable;

/**
 * 售票机：模型沿用 MTR 的 ticket_machine。
 * 右键打开界面：购买车票（需要 mtr12306 账号）与打印报销凭证。
 */
public class TicketMachineBlock extends BlockWithEntity {
	public static final DirectionProperty FACING = HorizontalFacingBlock.FACING;
	private static final VoxelShape SHAPE = Block.createCuboidShape(0.0, 0.0, 4.0, 16.0, 16.0, 16.0);

	public TicketMachineBlock(final Settings settings) {
		super(settings);
		setDefaultState(getDefaultState().with(FACING, Direction.NORTH));
	}

	@Override
	protected void appendProperties(final StateManager.Builder<Block, BlockState> builder) {
		builder.add(FACING);
	}

	@Override
	public BlockState getPlacementState(final ItemPlacementContext ctx) {
		return getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing().getOpposite());
	}

	@Override
	public BlockRenderType getRenderType(final BlockState state) {
		return BlockRenderType.MODEL;
	}

	@Override
	public VoxelShape getOutlineShape(final BlockState state, final BlockView world, final BlockPos pos,
			final ShapeContext context) {
		return SHAPE;
	}

	@Nullable
	@Override
	public BlockEntity createBlockEntity(final BlockPos pos, final BlockState state) {
		return new TicketMachineBlockEntity(pos, state);
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
		if (!(world.getBlockEntity(pos) instanceof TicketMachineBlockEntity machine)) {
			return ActionResult.PASS;
		}
		com.mtr12306.net.ModNetworking.openMachine(serverPlayer, pos, machine.stationId(serverWorld, pos));
		serverPlayer.sendMessage(Text.literal("§7售票机 · 可购票与打印报销凭证（需账号）"), true);
		return ActionResult.SUCCESS;
	}
}
