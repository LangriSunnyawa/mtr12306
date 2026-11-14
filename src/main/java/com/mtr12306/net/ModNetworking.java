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

import java.util.ArrayList;
import java.util.List;

/**
 * 闸机 / 售票机 的网络通信。
 */
public final class ModNetworking {
	public static final Identifier OPEN_GATE_CONFIG = Mtr12306.id("open_gate_config");
	public static final Identifier BIND_GATE = Mtr12306.id("bind_gate");
	public static final Identifier OPEN_BOARD_CONFIG = Mtr12306.id("open_board_config");
	public static final Identifier BIND_BOARD = Mtr12306.id("bind_board");
	public static final Identifier BIND_BROADCAST = Mtr12306.id("bind_broadcast");

	/** 售票机：服务端 → 客户端（打开界面并带上可售车次与我的车票） */
	public static final Identifier OPEN_MACHINE = Mtr12306.id("open_machine");
	/** 售票机：客户端 → 服务端 购票 */
	public static final Identifier MACHINE_BUY = Mtr12306.id("machine_buy");
	/** 售票机：客户端 → 服务端 打印报销凭证 */
	public static final Identifier MACHINE_PRINT = Mtr12306.id("machine_print");
	/** 售票机：服务端 → 客户端 结果提示 */
	public static final Identifier MACHINE_RESULT = Mtr12306.id("machine_result");

	private ModNetworking() {
	}

	public static void registerCommon() {
		ServerPlayNetworking.registerGlobalReceiver(BIND_GATE, (server, player, handler, buf, responseSender) -> {
			final BlockPos pos = buf.readBlockPos();
			final String stationId = buf.readString(32767);
			final String mode = buf.readString(16);
			final int count = buf.readInt();
			final List<String> ids = new ArrayList<>();
			for (int i = 0; i < count; i++) {
				ids.add(buf.readString(32767));
			}
			server.execute(() -> applyBinding(player, pos, stationId, ids, mode));
		});

		ServerPlayNetworking.registerGlobalReceiver(BIND_BOARD, (server, player, handler, buf, responseSender) -> {
			final BlockPos pos = buf.readBlockPos();
			final String stationId = buf.readString(32767);
			final int width = buf.readInt();
			final int height = buf.readInt();
			final int count = buf.readInt();
			final List<String> ids = new ArrayList<>();
			for (int i = 0; i < count; i++) {
				ids.add(buf.readString(32767));
			}
			server.execute(() -> applyBoardBinding(player, pos, stationId, width, height, ids));
		});

		ServerPlayNetworking.registerGlobalReceiver(BIND_BROADCAST, (server, player, handler, buf, responseSender) -> {
			final BlockPos pos = buf.readBlockPos();
			final String stationId = buf.readString(32767);
			final int count = buf.readInt();
			final List<String> ids = new ArrayList<>();
			for (int i = 0; i < count; i++) {
				ids.add(buf.readString(32767));
			}
			server.execute(() -> {
				if (!player.hasPermissionLevel(2)) {
					player.sendMessage(Text.literal("§c配置广播器需要 OP 权限"), false);
					return;
				}
				if (player.getWorld().getBlockEntity(pos) instanceof com.mtr12306.block.BroadcasterBlockEntity broadcaster) {
					broadcaster.setBinding(stationId, ids);
					player.sendMessage(Text.literal("§a广播器已更新：§f" + broadcaster.bindingLabel()), false);
				}
			});
		});

		ServerPlayNetworking.registerGlobalReceiver(MACHINE_BUY, (server, player, handler, buf, responseSender) -> {
			final BlockPos pos = buf.readBlockPos();
			final String service = buf.readString(32767);
			final String seatClass = buf.readString(64);
			final String toStationId = buf.readString(32767);
			server.execute(() -> MachineHandler.buy(player, pos, service, seatClass, toStationId));
		});

		ServerPlayNetworking.registerGlobalReceiver(MACHINE_PRINT, (server, player, handler, buf, responseSender) -> {
			final String ticketId = buf.readString(32767);
			server.execute(() -> MachineHandler.print(player, ticketId));
		});
	}

	private static void applyBinding(final ServerPlayerEntity player, final BlockPos pos, final String stationId,
			final List<String> checkpointIds, final String mode) {
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
		if (!stationId.isEmpty() && manager.data().station(stationId) == null) {
			player.sendMessage(Text.literal("§c车站不存在: " + stationId), false);
			return;
		}
		// 只能绑定闸机所在 MTR 车站对应的车站（识别不到时不做限制）
		final String mtrStation = MtrIntegration.stationNameAt(GateHelper.dimension(player.getWorld()),
				pos.getX(), pos.getY(), pos.getZ());
		if (!mtrStation.isBlank() && !stationId.isEmpty()) {
			final Station station = manager.data().station(stationId);
			final boolean match = station != null && (station.name.equalsIgnoreCase(mtrStation)
					|| station.mtrStation.equalsIgnoreCase(mtrStation));
			if (!match) {
				player.sendMessage(Text.literal("§c该车站不在本闸机所在的 MTR 车站（" + mtrStation + "）范围内"), false);
				return;
			}
		}
		gate.setBinding(stationId, checkpointIds, mode);
		gate.syncBinding();
		player.sendMessage(Text.literal("§a闸机已更新：§f" + gate.bindingLabel() + " §7模式 §f" + gate.mode()), false);
	}

	/** 打开闸机配置界面：车站 + 检票口多选（可留空=不限）+ 模式 */
	public static void openGateConfig(final ServerPlayerEntity player, final TicketGateBlockEntity gate) {
		final TicketingManager manager = Mtr12306.manager();
		if (manager == null) {
			return;
		}
		final BlockPos pos = gate.getPos();
		final String mtrStation = MtrIntegration.stationNameAt(GateHelper.dimension(player.getWorld()),
				pos.getX(), pos.getY(), pos.getZ());

		// 可选车站：识别到 MTR 车站时只列出同名车站，否则列出全部
		final List<Station> stations = new ArrayList<>();
		for (final Station station : manager.data().stations.values()) {
			if (mtrStation.isBlank() || station.name.equalsIgnoreCase(mtrStation)
					|| station.mtrStation.equalsIgnoreCase(mtrStation)) {
				stations.add(station);
			}
		}
		final PacketByteBuf buf = PacketByteBufs.create();
		buf.writeBlockPos(pos);
		buf.writeString(gate.stationId());
		buf.writeString(gate.mode());
		buf.writeString(mtrStation);
		buf.writeInt(stations.size());
		for (final Station station : stations) {
			buf.writeString(station.id);
			buf.writeString(station.name);
		}
		final List<Checkpoint> checkpoints = new ArrayList<>();
		for (final Checkpoint checkpoint : manager.data().checkpoints.values()) {
			if (checkpoint.enabled) {
				checkpoints.add(checkpoint);
			}
		}
		buf.writeInt(checkpoints.size());
		for (final Checkpoint checkpoint : checkpoints) {
			buf.writeString(checkpoint.id);
			buf.writeString(checkpoint.stationId);
			buf.writeString(checkpoint.name
					+ (checkpoint.platforms.isEmpty() ? "（全部站台）" : "（站台 " + String.join(",", checkpoint.platforms) + "）"));
		}
		buf.writeInt(gate.checkpointIds().size());
		for (final String id : gate.checkpointIds()) {
			buf.writeString(id);
		}
		ServerPlayNetworking.send(player, OPEN_GATE_CONFIG, buf);
	}

	private static void applyBoardBinding(final ServerPlayerEntity player, final BlockPos pos, final String stationId,
			final int width, final int height, final List<String> checkpointIds) {
		if (!player.hasPermissionLevel(2)) {
			player.sendMessage(Text.literal("§c配置大屏需要 OP 权限"), false);
			return;
		}
		if (!(player.getWorld().getBlockEntity(pos) instanceof com.mtr12306.block.StationBoardBlockEntity board)) {
			return;
		}
		if (!player.getWorld().getServer().getOverworld().equals(player.getWorld())
				&& !(player.getWorld() instanceof net.minecraft.server.world.ServerWorld)) {
			return;
		}
		final boolean ok = board.applySize(player.getServerWorld(), width, height, stationId, checkpointIds);
		player.sendMessage(Text.literal(ok
				? ("§a大屏已更新：§f" + board.bindingLabel() + " §7尺寸 §f" + width + "×" + height)
				: "§c该区域放不下这么大的屏幕（被其他方块占用）"), false);
	}

	/** 打开大屏配置界面 */
	public static void openBoardConfig(final ServerPlayerEntity player,
			final com.mtr12306.block.StationBoardBlockEntity board) {
		final TicketingManager manager = Mtr12306.manager();
		if (manager == null) {
			return;
		}
		final BlockPos pos = board.getPos();
		final String mtrStation = MtrIntegration.stationNameAt(GateHelper.dimension(player.getWorld()),
				pos.getX(), pos.getY(), pos.getZ());
		final List<Station> stations = new ArrayList<>();
		for (final Station station : manager.data().stations.values()) {
			if (mtrStation.isBlank() || station.name.equalsIgnoreCase(mtrStation)
					|| station.mtrStation.equalsIgnoreCase(mtrStation)) {
				stations.add(station);
			}
		}
		final PacketByteBuf buf = PacketByteBufs.create();
		buf.writeBlockPos(pos);
		buf.writeByte(0);
		buf.writeString(board.stationId());
		buf.writeString(board.rightDirectionName());
		buf.writeInt(board.width());
		buf.writeInt(board.height());
		buf.writeInt(stations.size());
		for (final Station station : stations) {
			buf.writeString(station.id);
			buf.writeString(station.name);
		}
		final List<Checkpoint> checkpoints = new ArrayList<>();
		for (final Checkpoint checkpoint : manager.data().checkpoints.values()) {
			if (checkpoint.enabled) {
				checkpoints.add(checkpoint);
			}
		}
		buf.writeInt(checkpoints.size());
		for (final Checkpoint checkpoint : checkpoints) {
			buf.writeString(checkpoint.id);
			buf.writeString(checkpoint.stationId);
			buf.writeString(checkpoint.name + (checkpoint.platforms.isEmpty() ? "（全部站台）"
					: "（站台 " + String.join(",", checkpoint.platforms) + "）"));
		}
		buf.writeInt(board.checkpointIds().size());
		for (final String id : board.checkpointIds()) {
			buf.writeString(id);
		}
		ServerPlayNetworking.send(player, OPEN_BOARD_CONFIG, buf);
	}

	/** 打开广播器配置界面（复用大屏配置界面） */
	public static void openBroadcastConfig(final ServerPlayerEntity player,
			final com.mtr12306.block.BroadcasterBlockEntity broadcaster) {
		final TicketingManager manager = Mtr12306.manager();
		if (manager == null) {
			return;
		}
		final BlockPos pos = broadcaster.getPos();
		final String mtrStation = MtrIntegration.stationNameAt(GateHelper.dimension(player.getWorld()),
				pos.getX(), pos.getY(), pos.getZ());
		final List<Station> stations = new ArrayList<>();
		for (final Station station : manager.data().stations.values()) {
			if (mtrStation.isBlank() || station.name.equalsIgnoreCase(mtrStation)
					|| station.mtrStation.equalsIgnoreCase(mtrStation)) {
				stations.add(station);
			}
		}
		final PacketByteBuf buf = PacketByteBufs.create();
		buf.writeBlockPos(pos);
		buf.writeByte(1);
		buf.writeString(broadcaster.stationId());
		buf.writeString("north");
		buf.writeInt(1);
		buf.writeInt(1);
		buf.writeInt(stations.size());
		for (final Station station : stations) {
			buf.writeString(station.id);
			buf.writeString(station.name);
		}
		final List<Checkpoint> checkpoints = new ArrayList<>();
		for (final Checkpoint checkpoint : manager.data().checkpoints.values()) {
			if (checkpoint.enabled) {
				checkpoints.add(checkpoint);
			}
		}
		buf.writeInt(checkpoints.size());
		for (final Checkpoint checkpoint : checkpoints) {
			buf.writeString(checkpoint.id);
			buf.writeString(checkpoint.stationId);
			buf.writeString(checkpoint.name);
		}
		buf.writeInt(broadcaster.checkpointIds().size());
		for (final String id : broadcaster.checkpointIds()) {
			buf.writeString(id);
		}
		ServerPlayNetworking.send(player, OPEN_BOARD_CONFIG, buf);
	}

	/** 打开售票机界面 */
	public static void openMachine(final ServerPlayerEntity player, final BlockPos pos, final String stationId) {
		final TicketingManager manager = Mtr12306.manager();
		if (manager == null) {
			return;
		}
		final JsonObject payload = new JsonObject();
		payload.addProperty("stationId", stationId);
		payload.addProperty("station", manager.data().stationName(stationId));
		payload.add("services", manager.servicesAtStation(stationId));
		payload.add("tickets", manager.receiptTickets(player.getGameProfile().getName()));
		final PacketByteBuf buf = PacketByteBufs.create();
		buf.writeBlockPos(pos);
		buf.writeString(payload.toString());
		ServerPlayNetworking.send(player, OPEN_MACHINE, buf);
	}

	public static void sendMachineResult(final ServerPlayerEntity player, final boolean success, final String message) {
		final PacketByteBuf buf = PacketByteBufs.create();
		buf.writeBoolean(success);
		buf.writeString(message);
		ServerPlayNetworking.send(player, MACHINE_RESULT, buf);
	}
}
