package com.mtr12306.net;

import com.google.gson.JsonObject;
import com.mtr12306.Mtr12306;
import com.mtr12306.TicketingManager;
import com.mtr12306.block.GateHelper;
import com.mtr12306.block.TicketGateBlockEntity;
import com.mtr12306.data.Checkpoint;
import com.mtr12306.data.Station;
import com.mtr12306.mtr.MtrIntegration;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/**
 * 闸机配置界面的网络通信。
 */
public final class ModNetworking {
	public static final Identifier OPEN_GATE_CONFIG = Mtr12306.id("open_gate_config");
	public static final Identifier BIND_GATE = Mtr12306.id("bind_gate");

	private ModNetworking() {
	}

	public static void registerCommon() {
		ServerPlayNetworking.registerGlobalReceiver(BIND_GATE, (server, player, handler, buf, responseSender) -> {
			final BlockPos pos = buf.readBlockPos();
			final String checkpointId = buf.readString(32767);
			final String mode = buf.readString(16);
			server.execute(() -> applyBinding(player, pos, checkpointId, mode));
		});
	}

	private static void applyBinding(final ServerPlayerEntity player, final BlockPos pos, final String checkpointId,
			final String mode) {
		if (!player.hasPermissionLevel(2)) {
			player.sendMessage(Text.literal("§c配置闸机需要 OP 权限"), false);
			return;
		}
		if (!(player.getWorld().getBlockEntity(pos) instanceof TicketGateBlockEntity gate)) {
			return;
		}
		final TicketingManager manager = Mtr12306.manager();
		if (manager == null) {
			return;
		}
		if (!checkpointId.isEmpty()) {
			final Checkpoint checkpoint = manager.data().checkpoint(checkpointId);
			if (checkpoint == null) {
				player.sendMessage(Text.literal("§c检票口不存在: " + checkpointId), false);
				return;
			}
			// 只能绑定闸机所在 MTR 车站的检票口
			final String stationName = MtrIntegration.stationNameAt(GateHelper.dimension(player.getWorld()),
					pos.getX(), pos.getY(), pos.getZ());
			final com.google.gson.JsonArray allowed = manager.checkpointsForStationName(stationName);
			boolean found = false;
			for (final com.google.gson.JsonElement element : allowed) {
				final JsonObject entry = element.getAsJsonObject();
				if (entry.get("id").getAsString().equals(checkpointId)) {
					found = true;
					break;
				}
			}
			if (!found) {
				player.sendMessage(Text.literal("§c该检票口不在本闸机所在的车站内"
						+ (stationName.isEmpty() ? "（闸机不在任何 MTR 车站范围内）" : "（车站：" + stationName + "）")), false);
				return;
			}
		}
		gate.setCheckpoint(checkpointId, mode);
		gate.syncBinding();
		player.sendMessage(Text.literal("§a闸机已更新：检票口 §e" + gate.checkpointName() + " §a模式 §e" + gate.mode()), false);
	}

	/** 打开闸机配置界面：检票口列表按闸机所在 MTR 车站过滤 */
	public static void openGateConfig(final ServerPlayerEntity player, final TicketGateBlockEntity gate) {
		final TicketingManager manager = Mtr12306.manager();
		if (manager == null) {
			return;
		}
		final BlockPos pos = gate.getPos();
		final String stationName = MtrIntegration.stationNameAt(GateHelper.dimension(player.getWorld()),
				pos.getX(), pos.getY(), pos.getZ());
		final com.google.gson.JsonArray allowed = manager.checkpointsForStationName(stationName);

		final PacketByteBuf buf = PacketByteBufs.create();
		buf.writeBlockPos(pos);
		buf.writeString(gate.checkpointId());
		buf.writeString(gate.mode());
		buf.writeString(stationName);
		buf.writeInt(allowed.size());
		for (final com.google.gson.JsonElement element : allowed) {
			final JsonObject entry = element.getAsJsonObject();
			buf.writeString(entry.get("id").getAsString());
			final String platforms = entry.get("platforms").getAsString();
			buf.writeString(entry.get("station").getAsString() + " · " + entry.get("name").getAsString()
					+ (platforms.isBlank() ? "" : "（站台 " + platforms + "）"));
		}
		ServerPlayNetworking.send(player, OPEN_GATE_CONFIG, buf);
	}

	@SuppressWarnings("unused")
	private static String stationName(final Checkpoint checkpoint, final TicketingManager manager) {
		final Station station = manager.data().station(checkpoint.stationId);
		return station == null ? "" : station.name;
	}
}
