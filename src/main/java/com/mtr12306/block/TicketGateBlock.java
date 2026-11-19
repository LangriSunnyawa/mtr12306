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
import net.minecraft.state.property.BooleanProperty;
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
	/** 是否与相邻闸机连成一排（决定用 Metropolis 的「并排」还是「独立」模型） */
	public static final BooleanProperty CONNECTED = BooleanProperty.of("connected");

	/** 关门：整格实心，走不过去，也挡得住贴边 */
	private static final VoxelShape CLOSED_SHAPE = Block.createCuboidShape(0.0, 0.0, 0.0, 16.0, 16.0, 16.0);
	/** 开门（南北向）：两侧窄机柜 + 底板，中间留 0.75 格通道 */
	private static final VoxelShape OPEN_NS = Voxels.union(
			Block.createCuboidShape(0.0, 0.0, 0.0, 2.0, 16.0, 16.0),
			Block.createCuboidShape(14.0, 0.0, 0.0, 16.0, 16.0, 16.0),
			Block.createCuboidShape(0.0, 0.0, 0.0, 16.0, 2.0, 16.0));
	/** 开门（东西向） */
	private static final VoxelShape OPEN_EW = Voxels.union(
			Block.createCuboidShape(0.0, 0.0, 0.0, 16.0, 16.0, 2.0),
			Block.createCuboidShape(0.0, 0.0, 14.0, 16.0, 16.0, 16.0),
			Block.createCuboidShape(0.0, 0.0, 0.0, 16.0, 2.0, 16.0));

	private static VoxelShape shapeFor(final BlockState state) {
		if (state.get(OPEN) == GateState.OPEN) {
			return state.get(FACING).getAxis() == Direction.Axis.Z ? OPEN_NS : OPEN_EW;
		}
		return CLOSED_SHAPE;
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
				.with(KIND, GateKind.ENTRANCE).with(CONNECTED, false));
	}

	@Override
	protected void appendProperties(final StateManager.Builder<Block, BlockState> builder) {
		builder.add(FACING, OPEN, KIND, CONNECTED);
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

	/** 左右（与朝向垂直的两侧）有同朝向闸机时视为「并排」 */
	private static void updateConnected(final World world, final BlockPos pos) {
		final BlockState state = world.getBlockState(pos);
		if (!state.isOf(ModBlocks.TICKET_GATE)) {
			return;
		}
		final Direction facing = state.get(FACING);
		final Direction left = facing.rotateYCounterclockwise();
		final Direction right = facing.rotateYClockwise();
		boolean connected = false;
		for (final Direction side : new Direction[]{left, right}) {
			final BlockState other = world.getBlockState(pos.offset(side));
			if (other.isOf(ModBlocks.TICKET_GATE) && other.get(FACING) == facing) {
				connected = true;
				break;
			}
		}
		if (state.get(CONNECTED) != connected) {
			world.setBlockState(pos, state.with(CONNECTED, connected), Block.NOTIFY_ALL);
		}
	}

	@Override
	public void onPlaced(final World world, final BlockPos pos, final BlockState state, @Nullable final LivingEntity placer,
			final ItemStack itemStack) {
		super.onPlaced(world, pos, state, placer, itemStack);
		if (!world.isClient()) {
			updateConnected(world, pos);
			for (final Direction direction : Direction.Type.HORIZONTAL) {
				updateConnected(world, pos.offset(direction));
			}
		}
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
		if (!world.isClient() && !state.isOf(newState.getBlock())) {
			if (Mtr12306.manager() != null) {
				Mtr12306.manager().removeGate(GateHelper.key(world, pos));
			}
			for (final Direction direction : Direction.Type.HORIZONTAL) {
				updateConnected(world, pos.offset(direction));
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
