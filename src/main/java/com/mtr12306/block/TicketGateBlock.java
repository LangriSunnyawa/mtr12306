package com.mtr12306.block;

import com.mtr12306.Mtr12306;
import com.mtr12306.net.ModNetworking;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * 检票闸机（使用 MTR 闸机的模型与通行方式）。
 * 模型几何来自 MTR 的 ticket_barrier（MIT），贴图做了重新配色。
 *
 * <p>状态机与 MTR 一致：closed / pending / open；玩家走到闸机所在格触发检票，
 * 通过时闸机指示灯变绿（箭头向上），否则显示红色箭头并把玩家推回去。</p>
 */
public class TicketGateBlock extends BlockWithEntity {
	public static final DirectionProperty FACING = HorizontalFacingBlock.FACING;
	/** 闸机状态：closed=待机，pending=拒绝，open=已放行（与 MTR 闸机一致） */
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

	/** 视觉类型：entrance=进站（橙色底座），exit=出站（绿色底座），与 MTR 的进/出闸机对应 */
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

	/** 机柜碰撞箱（按朝向取不同半边，与模型一致） */
	private static final VoxelShape SHAPE_NORTH = Block.createCuboidShape(12.0, 0.0, 0.0, 16.0, 15.0, 16.0);
	private static final VoxelShape SHAPE_EAST = Block.createCuboidShape(0.0, 0.0, 12.0, 16.0, 15.0, 16.0);
	private static final VoxelShape SHAPE_SOUTH = Block.createCuboidShape(0.0, 0.0, 0.0, 4.0, 15.0, 16.0);
	private static final VoxelShape SHAPE_WEST = Block.createCuboidShape(0.0, 0.0, 0.0, 16.0, 15.0, 4.0);
	/** 轮廓用整格，方便右键点到闸机 */
	private static final VoxelShape OUTLINE = Block.createCuboidShape(0.0, 0.0, 0.0, 16.0, 16.0, 16.0);

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
		return OUTLINE;
	}

	@Override
	public VoxelShape getCollisionShape(final BlockState state, final BlockView world, final BlockPos pos,
			final ShapeContext context) {
		return switch (state.get(FACING)) {
			case EAST -> SHAPE_EAST;
			case SOUTH -> SHAPE_SOUTH;
			case WEST -> SHAPE_WEST;
			default -> SHAPE_NORTH;
		};
	}

	@Nullable
	@Override
	public BlockEntity createBlockEntity(final BlockPos pos, final BlockState state) {
		return new TicketGateBlockEntity(pos, state);
	}


	/** 玩家踩进闸机所在方块时触发检票（MTR 的闸机也是这么做的） */
	@Override
	public void onEntityCollision(final BlockState state, final World world, final BlockPos pos, final Entity entity) {
		if (world.isClient() || !(entity instanceof ServerPlayerEntity player)) {
			return;
		}
		if (world.getBlockEntity(pos) instanceof TicketGateBlockEntity gate) {
			gate.handlePlayer((ServerWorld) world, pos, state, player);
		}
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
		// 相邻闸机自动继承检票口与模式，方便成排布置
		for (final Direction direction : Direction.Type.HORIZONTAL) {
			final BlockEntity neighbour = world.getBlockEntity(pos.offset(direction));
			if (neighbour instanceof TicketGateBlockEntity other && !other.checkpointId().isEmpty()) {
				gate.setCheckpoint(other.checkpointId(), other.mode());
				break;
			}
		}
		gate.syncBinding();
		if (Mtr12306.manager() != null) {
			Mtr12306.manager().bindGate(GateHelper.dimension(world), pos.getX(), pos.getY(), pos.getZ(),
					gate.checkpointId(), gate.mode());
		}
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
		serverPlayer.sendMessage(Text.literal("§e此闸机：§f" + gate.checkpointName() + " §7模式 §f" + gate.mode()), true);
		if (serverPlayer.hasPermissionLevel(2)) {
			serverPlayer.sendMessage(Text.literal("§7拿 §f闸机配置面板 §7右键可修改配置"), false);
		}
		return ActionResult.SUCCESS;
	}
}
