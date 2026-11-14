package com.mtr12306.block;

import com.mtr12306.Mtr12306;
import com.mtr12306.item.ConfigToolItem;
import com.mtr12306.net.ModNetworking;
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
import org.jetbrains.annotations.Nullable;

/**
 * 车站大屏：可自由拼接成矩形的多格屏幕。
 * 只由「锚点」（MAIN）方块负责渲染整块画面，其余方块不渲染内容，因此开销与普通方块相同。
 */
public class StationBoardBlock extends BlockWithEntity {
	public static final DirectionProperty FACING = HorizontalFacingBlock.FACING;
	/** 是否为该屏幕的锚点（记录尺寸并负责渲染） */
	public static final BooleanProperty MAIN = BooleanProperty.of("main");

	private static final VoxelShape SHAPE = Block.createCuboidShape(0.0, 0.0, 14.0, 16.0, 16.0, 16.0);

	public StationBoardBlock(final Settings settings) {
		super(settings);
		setDefaultState(getDefaultState().with(FACING, Direction.NORTH).with(MAIN, true));
	}

	@Override
	protected void appendProperties(final StateManager.Builder<Block, BlockState> builder) {
		builder.add(FACING, MAIN);
	}

	@Override
	public BlockState getPlacementState(final ItemPlacementContext ctx) {
		return getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing().getOpposite()).with(MAIN, true);
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

	@Override
	public VoxelShape getCollisionShape(final BlockState state, final BlockView world, final BlockPos pos,
			final ShapeContext context) {
		return SHAPE;
	}

	@Nullable
	@Override
	public BlockEntity createBlockEntity(final BlockPos pos, final BlockState state) {
		return new StationBoardBlockEntity(pos, state);
	}

	@Override
	public void onPlaced(final World world, final BlockPos pos, final BlockState state, @Nullable final LivingEntity placer,
			final ItemStack itemStack) {
		super.onPlaced(world, pos, state, placer, itemStack);
		if (world.isClient() || !(world.getBlockEntity(pos) instanceof StationBoardBlockEntity board)) {
			return;
		}
		board.setAnchor(pos);
		board.markDirty();
	}

	@Override
	public void onStateReplaced(final BlockState state, final World world, final BlockPos pos, final BlockState newState,
			final boolean moved) {
		if (!world.isClient() && !state.isOf(newState.getBlock())) {
			if (world.getBlockEntity(pos) instanceof StationBoardBlockEntity board) {
				board.removeWholeBoard((ServerWorld) world);
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
		if (!(player instanceof ServerPlayerEntity serverPlayer)) {
			return ActionResult.PASS;
		}
		if (!(world.getBlockEntity(pos) instanceof StationBoardBlockEntity board)) {
			return ActionResult.PASS;
		}
		if (player.getStackInHand(hand).getItem() instanceof ConfigToolItem && serverPlayer.hasPermissionLevel(2)) {
			ModNetworking.openBoardConfig(serverPlayer, board);
			return ActionResult.SUCCESS;
		}
		serverPlayer.sendMessage(Text.literal("§e大屏：§f" + board.bindingLabel() + " §7尺寸 §f"
				+ board.width() + "×" + board.height() + " §7（用配置面板右键可调整）"), false);
		return ActionResult.SUCCESS;
	}

	@SuppressWarnings("unused")
	private static void unused() {
		Mtr12306.LOGGER.getName();
	}
}
