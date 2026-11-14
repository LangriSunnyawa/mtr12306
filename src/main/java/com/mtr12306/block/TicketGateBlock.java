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
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * 检票闸机（模型沿用 MTR ticket barrier）。
 *
 * <p>通行方式参考 Metropolis 的闸机：<b>右键闸机</b>才会检票放行；关门时是实心方块，
 * 因此无法"一直往前走卡进去"；放行后闸门打开数秒供通过。</p>
 */
public class TicketGateBlock extends BlockWithEntity {
	public static final DirectionProperty FACING = HorizontalFacingBlock.FACING;

	public enum GateState implements StringIdentifiable {
		CLOSED("closed"), PENDING("pending"), OPEN("open");

		private final String id;

		GateState(final String id) {
			this.id = id;
		}

		@Override
		public String asString() {
			return id;
		}
	}

	public enum GateKind implements StringIdentifiable {
		ENTRANCE("entrance"), EXIT("exit");

		private final String id;

		GateKind(final String id) {
			this.id = id;
		}

		@Override
		public String asString() {
			return id;
		}
	}

	public static final EnumProperty<GateState> OPEN = EnumProperty.of("open", GateState.class);
	public static final EnumProperty<GateKind> KIND = EnumProperty.of("kind", GateKind.class);

	/** 机柜（南北向摆放时位于 X 两侧） */
	private static final VoxelShape CABINETS_NS = Voxels.union(
			Block.createCuboidShape(0.0, 0.0, 4.0, 4.0, 15.0, 12.0),
			Block.createCuboidShape(12.0, 0.0, 4.0, 16.0, 15.0, 12.0));
	/** 机柜（东西向摆放时位于 Z 两侧） */
	private static final VoxelShape CABINETS_EW = Voxels.union(
			Block.createCuboidShape(4.0, 0.0, 0.0, 12.0, 15.0, 4.0),
			Block.createCuboidShape(4.0, 0.0, 12.0, 12.0, 15.0, 16.0));
	/** 闸板（关门时横挡通道） */
	private static final VoxelShape PANEL_NS = Block.createCuboidShape(4.0, 5.0, 7.6, 12.0, 13.0, 8.4);
	private static final VoxelShape PANEL_EW = Block.createCuboidShape(7.6, 5.0, 4.0, 8.4, 13.0, 12.0);
	/** 底座 */
	private static final VoxelShape BASE_NS = Block.createCuboidShape(0.0, 0.0, 4.0, 16.0, 2.0, 12.0);
	private static final VoxelShape BASE_EW = Block.createCuboidShape(4.0, 0.0, 0.0, 12.0, 2.0, 16.0);

	private static final VoxelShape CLOSED_NS = Voxels.union(CABINETS_NS, PANEL_NS, BASE_NS);
	private static final VoxelShape CLOSED_EW = Voxels.union(CABINETS_EW, PANEL_EW, BASE_EW);
	private static final VoxelShape OPEN_NS = Voxels.union(CABINETS_NS, BASE_NS);
	private static final VoxelShape OPEN_EW = Voxels.union(CABINETS_EW, BASE_EW);

	private static VoxelShape shapeFor(final BlockState state) {
		final boolean alongX = state.get(FACING).getAxis() == Direction.Axis.Z;
		if (state.get(OPEN) == GateState.OPEN) {
			return alongX ? OPEN_NS : OPEN_EW;
		}
		return alongX ? CLOSED_NS : CLOSED_EW;
	}

	/** 小工具：合并若干形状（等价 VoxelShapes.union，写短一点） */
	private static final class Voxels {
		private static VoxelShape union(final VoxelShape... shapes) {
			VoxelShape result = net.minecraft.util.shape.VoxelShapes.empty();
			for (final VoxelShape shape : shapes) {
				result = net.minecraft.util.shape.VoxelShapes.union(result, shape);
			}
			return result.simplify();
		}
	}

	public TicketGateBlock(final Settings settings) {
		super(settings);
		setDefaultState(getDefaultState().with(FACING, Direction.NORTH).with(OPEN, GateState.CLOSED)
				.with(KIND, GateKind.ENTRANCE));
	}

	@Override
	protected void appendProperties(final StateManager.Builder<Block, BlockState> builder) {
		builder.add(FACING, OPEN, KIND);
	}

	@Override
	public BlockState getPlacementState(final ItemPlacementContext ctx) {
		return getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing());
	}

	@Override
	public BlockRenderType getRenderType(final BlockState state) {
		return BlockRenderType.MODEL;
	}

	@Override
	public VoxelShape getOutlineShape(final BlockState state, final BlockView world, final BlockPos pos,
			final ShapeContext context) {
		return shapeFor(state);
	}

	@Override
	public VoxelShape getCollisionShape(final BlockState state, final BlockView world, final BlockPos pos,
			final ShapeContext context) {
		return shapeFor(state);
	}

	@Nullable
	@Override
	public BlockEntity createBlockEntity(final BlockPos pos, final BlockState state) {
		return new TicketGateBlockEntity(pos, state);
	}

	@Nullable
	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(final World world, final BlockState state,
			final BlockEntityType<T> type) {
		if (world.isClient()) {
			return null;
		}
		return (tickWorld, tickPos, tickState, blockEntity) -> {
			if (blockEntity instanceof TicketGateBlockEntity gate) {
				gate.serverTick(tickWorld, tickPos);
			}
		};
	}

	@Override
	public void scheduledTick(final BlockState state, final ServerWorld world, final BlockPos pos, final Random random) {
		if (state.get(OPEN) != GateState.CLOSED) {
			world.setBlockState(pos, state.with(OPEN, GateState.CLOSED), Block.NOTIFY_LISTENERS);
		}
	}

	@Override
	public void onPlaced(final World world, final BlockPos pos, final BlockState state, @Nullable final LivingEntity placer,
			final ItemStack itemStack) {
		super.onPlaced(world, pos, state, placer, itemStack);
		if (world.isClient() || !(world.getBlockEntity(pos) instanceof TicketGateBlockEntity gate)) {
			return;
		}
		// 相邻闸机自动继承车站/检票口/模式，方便成排布置
		for (final Direction direction : Direction.Type.HORIZONTAL) {
			final BlockEntity neighbour = world.getBlockEntity(pos.offset(direction));
			if (neighbour instanceof TicketGateBlockEntity other && !other.stationId().isEmpty()) {
				gate.setBinding(other.stationId(), other.checkpointIds(), other.mode());
				break;
			}
		}
		gate.syncBinding();
	}

	@Override
	public void onStateReplaced(final BlockState state, final World world, final BlockPos pos, final BlockState newState,
			final boolean moved) {
		if (!world.isClient() && !state.isOf(newState.getBlock()) && Mtr12306.manager() != null) {
			Mtr12306.manager().removeGate(GateHelper.key(world, pos));
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
		if (!(world.getBlockEntity(pos) instanceof TicketGateBlockEntity gate)) {
			return ActionResult.PASS;
		}
		gate.syncBinding();
		final boolean withTool = player.getStackInHand(hand).getItem() instanceof ConfigToolItem;
		if (withTool && serverPlayer.hasPermissionLevel(2)) {
			ModNetworking.openGateConfig(serverPlayer, gate);
			return ActionResult.SUCCESS;
		}
		// 普通右键：检票放行（Metropolis 风格）
		gate.tryPass((ServerWorld) world, pos, state, serverPlayer);
		return ActionResult.SUCCESS;
	}

	/** 兼容旧调用：显示闸机信息 */
	public static Text infoText(final TicketGateBlockEntity gate) {
		return Text.literal("§e此闸机：§f" + gate.bindingLabel() + " §7模式 §f" + gate.mode());
	}
}
