package com.mtr12306.block;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mtr12306.Mtr12306;
import com.mtr12306.TicketingManager;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * 车站大屏方块实体：锚点记录整块矩形的尺寸与筛选条件；其余格子共享同一份数据。
 */
public class StationBoardBlockEntity extends BlockEntity {
	private BlockPos anchor = BlockPos.ORIGIN;
	private int width = 1;
	private int height = 1;
	private String stationId = "";
	private List<String> checkpointIds = new ArrayList<>();

	public StationBoardBlockEntity(final BlockPos pos, final BlockState state) {
		super(ModBlocks.STATION_BOARD_ENTITY, pos, state);
		anchor = pos;
	}

	public BlockPos anchor() {
		return anchor;
	}

	public int width() {
		return width;
	}

	public int heightValue() {
		return height;
	}

	public int height() {
		return height;
	}

	public String stationId() {
		return stationId;
	}

	public List<String> checkpointIds() {
		return checkpointIds;
	}

	public void setAnchor(final BlockPos anchor) {
		this.anchor = anchor;
	}

	public void setBinding(final String stationId, final List<String> checkpointIds) {
		this.stationId = stationId == null ? "" : stationId;
		this.checkpointIds = checkpointIds == null ? new ArrayList<>() : new ArrayList<>(checkpointIds);
		markDirty();
	}

	public String bindingLabel() {
		final TicketingManager manager = Mtr12306.manager();
		if (manager == null || stationId.isEmpty()) {
			return "未绑定车站";
		}
		return manager.checkpointLabel(stationId, checkpointIds);
	}

	/** 本屏幕显示的行数据（服务端计算后同步给客户端渲染） */
	public JsonArray rows() {
		final TicketingManager manager = Mtr12306.manager();
		if (manager == null || stationId.isEmpty()) {
			return new JsonArray();
		}
		return manager.boardRows(stationId, checkpointIds);
	}

	public JsonObject renderPayload() {
		final JsonObject json = new JsonObject();
		json.addProperty("width", width);
		json.addProperty("height", height);
		json.addProperty("station", bindingLabel());
		json.add("rows", rows());
		return json;
	}

	/* ------------------------------------------------------------------ 多格矩形 */

	/** 水平展开方向（屏幕“向右”扩展的轴向） */
	public Direction rightDirection() {
		final Direction facing = getCachedState().get(StationBoardBlock.FACING);
		return facing.getAxis() == Direction.Axis.Z
				? (facing == Direction.NORTH ? Direction.EAST : Direction.WEST)
				: (facing == Direction.EAST ? Direction.SOUTH : Direction.NORTH);
	}

	public String rightDirectionName() {
		return rightDirection().asString();
	}

	/**
	 * 设置屏幕尺寸：以锚点为左下角，向右（rightDirection）、向上扩展。
	 * 只允许在整块矩形都为空气或本屏幕方块时生效。
	 */
	public boolean applySize(final ServerWorld world, final int newWidth, final int newHeight, final String stationId,
			final List<String> checkpointIds) {
		final int w = Math.max(1, Math.min(12, newWidth));
		final int h = Math.max(1, Math.min(8, newHeight));
		final Direction right = rightDirection();
		final BlockPos base = anchor;

		// 1) 检查目标区域是否可用
		for (int dx = 0; dx < w; dx++) {
			for (int dy = 0; dy < h; dy++) {
				final BlockPos target = base.offset(right, dx).up(dy);
				final BlockState state = world.getBlockState(target);
				final boolean ours = state.isOf(ModBlocks.STATION_BOARD);
				if (!state.isAir() && !ours) {
					return false;
				}
			}
		}

		// 2) 清掉旧的格子（锚点保留）
		removeCells(world, base, getCachedState().get(StationBoardBlock.FACING));

		// 3) 铺新矩形
		final BlockState baseState = world.getBlockState(base);
		final Direction facing = baseState.get(StationBoardBlock.FACING);
		for (int dx = 0; dx < w; dx++) {
			for (int dy = 0; dy < h; dy++) {
				final BlockPos target = base.offset(right, dx).up(dy);
				final BlockState cellState = baseState
						.with(StationBoardBlock.MAIN, dx == 0 && dy == 0)
						.with(StationBoardBlock.FACING, facing);
				world.setBlockState(target, cellState, Block.NOTIFY_ALL);
				if (world.getBlockEntity(target) instanceof StationBoardBlockEntity cell) {
					cell.anchor = base;
					cell.width = w;
					cell.height = h;
					cell.setBinding(stationId, checkpointIds);
					cell.markDirty();
				}
			}
		}
		if (world.getBlockEntity(base) instanceof StationBoardBlockEntity cell) {
			cell.anchor = base;
			cell.width = w;
			cell.height = h;
			cell.setBinding(stationId, checkpointIds);
			cell.markDirty();
		}
		world.updateListeners(base, baseState, world.getBlockState(base), Block.NOTIFY_ALL);
		return true;
	}

	/** 清掉以 base/facing 为前提、当前尺寸覆盖的所有非锚点格子 */
	private void removeCells(final ServerWorld world, final BlockPos base, final Direction facing) {
		final Direction right = rightDirection();
		for (int dx = 0; dx < width; dx++) {
			for (int dy = 0; dy < height; dy++) {
				if (dx == 0 && dy == 0) {
					continue;
				}
				final BlockPos target = base.offset(right, dx).up(dy);
				if (world.getBlockState(target).isOf(ModBlocks.STATION_BOARD)) {
					world.setBlockState(target, net.minecraft.block.Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
				}
			}
		}
	}

	/** 整块移除（拆任意一格时调用） */
	public void removeWholeBoard(final ServerWorld world) {
		final BlockPos base = anchor;
		final Direction right = rightDirection();
		for (int dx = 0; dx < width; dx++) {
			for (int dy = 0; dy < height; dy++) {
				final BlockPos target = base.offset(right, dx).up(dy);
				if (world.getBlockState(target).isOf(ModBlocks.STATION_BOARD)) {
					world.setBlockState(target, net.minecraft.block.Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
				}
			}
		}
	}

	/* ------------------------------------------------------------------ NBT */

	@Override
	public void writeNbt(final NbtCompound nbt) {
		super.writeNbt(nbt);
		nbt.putInt("ax", anchor.getX());
		nbt.putInt("ay", anchor.getY());
		nbt.putInt("az", anchor.getZ());
		nbt.putInt("w", width);
		nbt.putInt("h", height);
		nbt.putString("stationId", stationId);
		final NbtList list = new NbtList();
		for (final String id : checkpointIds) {
			list.add(NbtString.of(id));
		}
		nbt.put("checkpointIds", list);
	}

	@Override
	public void readNbt(final NbtCompound nbt) {
		super.readNbt(nbt);
		anchor = new BlockPos(nbt.getInt("ax"), nbt.getInt("ay"), nbt.getInt("az"));
		width = Math.max(1, nbt.getInt("w"));
		height = Math.max(1, nbt.getInt("h"));
		stationId = nbt.getString("stationId");
		checkpointIds = new ArrayList<>();
		if (nbt.contains("checkpointIds", NbtElement.LIST_TYPE)) {
			final NbtList list = nbt.getList("checkpointIds", NbtElement.STRING_TYPE);
			for (int i = 0; i < list.size(); i++) {
				checkpointIds.add(list.getString(i));
			}
		}
	}

	@Override
	public NbtCompound toInitialChunkDataNbt() {
		final NbtCompound nbt = createNbt();
		// 客户端渲染需要行数据：把渲染载荷塞进同步数据里
		nbt.putString("payload", renderPayload().toString());
		return nbt;
	}
}
