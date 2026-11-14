package com.mtr12306.block;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mtr12306.Mtr12306;
import com.mtr12306.TicketingManager;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.ArrayList;
import java.util.List;

/**
 * 广播器方块实体：按车站/检票口筛选，自动播报「正在检票」的车次（京张广播格式）。
 * 文案与音效可在 config/broadcast.json 中覆盖。
 */
public class BroadcasterBlockEntity extends BlockEntity {
	private static final int INTERVAL_TICKS = 20 * 25;
	private static final double RADIUS = 64.0D;

	private String stationId = "";
	private List<String> checkpointIds = new ArrayList<>();
	private String lastBroadcast = "";
	private int cooldown = 0;

	public BroadcasterBlockEntity(final BlockPos pos, final BlockState state) {
		super(ModBlocks.BROADCASTER_ENTITY, pos, state);
	}

	public String stationId() {
		return stationId;
	}

	public List<String> checkpointIds() {
		return checkpointIds;
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

	public void serverTick(final ServerWorld world, final BlockPos pos) {
		if (cooldown > 0) {
			cooldown--;
			return;
		}
		cooldown = INTERVAL_TICKS;
		if (stationId.isEmpty() || Mtr12306.manager() == null) {
			return;
		}
		final JsonArray rows = Mtr12306.manager().boardRows(stationId, checkpointIds);
		String chosen = null;
		JsonObject chosenRow = null;
		for (int i = 0; i < rows.size(); i++) {
			final JsonObject row = rows.get(i).getAsJsonObject();
			if (row.has("boarding") && row.get("boarding").getAsBoolean()) {
				final String service = row.get("service").getAsString();
				if (!service.equals(lastBroadcast)) {
					chosen = service;
					chosenRow = row;
					break;
				}
			}
		}
		if (chosen == null || chosenRow == null) {
			return;
		}
		lastBroadcast = chosen;
		broadcast(world, pos, Text.literal(Mtr12306.CONFIG.broadcastPrefix
				+ chosen + "次列车开始检票，请到" + chosenRow.get("platform").getAsString() + "站台检票上车。"
				+ Mtr12306.CONFIG.broadcastSuffix));
	}

	private void broadcast(final ServerWorld world, final BlockPos pos, final Text message) {
		world.playSound(null, pos, SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), SoundCategory.BLOCKS, 1.0F, 1.6F);
		world.playSound(null, pos, SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), SoundCategory.BLOCKS, 0.9F, 1.2F);
		for (final ServerPlayerEntity player : world.getPlayers(candidate ->
				candidate.getPos().squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)
						<= RADIUS * RADIUS)) {
			TicketGateBlockEntity.safeMessage(player, Text.literal("§b[车站广播] §f" + message.getString()), false);
		}
	}

	@Override
	public void writeNbt(final NbtCompound nbt) {
		super.writeNbt(nbt);
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
		return createNbt();
	}

	@SuppressWarnings("unused")
	private static Box unusedBox() {
		return new Box(0, 0, 0, 1, 1, 1);
	}
}
