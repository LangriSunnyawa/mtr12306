package com.mtr12306.block;

import com.mtr12306.item.ConfigToolItem;
import com.mtr12306.net.ModNetworking;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
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

/** 广播器：绑定方式与闸机/大屏一致（车站 + 可多选检票口），自动播报正在检票的车次。 */
public class BroadcasterBlock extends BlockWithEntity {
	public static final DirectionProperty FACING = HorizontalFacingBlock.FACING;
	private static final VoxelShape SHAPE = Block.createCuboidShape(5.0, 12.0, 5.0, 11.0, 16.0, 11.0);

	public BroadcasterBlock(final Settings settings) {
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
		return new BroadcasterBlockEntity(pos, state);
	}

	@Nullable
	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(final World world, final BlockState state,
			final BlockEntityType<T> type) {
		if (world.isClient()) {
			return null;
		}
		return (tickWorld, tickPos, tickState, blockEntity) -> {
			if (blockEntity instanceof BroadcasterBlockEntity broadcaster && tickWorld instanceof ServerWorld serverWorld) {
				broadcaster.serverTick(serverWorld, tickPos);
			}
		};
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
		if (!(world.getBlockEntity(pos) instanceof BroadcasterBlockEntity broadcaster)) {
			return ActionResult.PASS;
		}
		if (player.getStackInHand(hand).getItem() instanceof ConfigToolItem && serverPlayer.hasPermissionLevel(2)) {
			ModNetworking.openBroadcastConfig(serverPlayer, broadcaster);
			return ActionResult.SUCCESS;
		}
		serverPlayer.sendMessage(Text.literal("§e广播器：§f" + broadcaster.bindingLabel()), false);
		return ActionResult.SUCCESS;
	}
}
