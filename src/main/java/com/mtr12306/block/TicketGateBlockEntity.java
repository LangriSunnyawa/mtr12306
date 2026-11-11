package com.mtr12306.block;

import com.mtr12306.Mtr12306;
import com.mtr12306.TicketingManager;
import com.mtr12306.data.Checkpoint;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 闸机方块实体：保存所属车站 / 检票口（可多选，空=不限）/ 通行模式，
 * 并在玩家右键时完成检票放行。
 */
public class TicketGateBlockEntity extends BlockEntity {
	private static final long MESSAGE_COOLDOWN = 1200L;

	private String stationId = "";
	/** 检票口 id 列表（空 = 不限） */
	private List<String> checkpointIds = new ArrayList<>();
	private String mode = "ENTRY";

	private final Map<UUID, Long> allowedUntil = new HashMap<>();
	private final Map<UUID, Long> deniedUntil = new HashMap<>();
	private final Map<UUID, Long> messageCooldown = new HashMap<>();
	/** 开门/红箭状态的到期时间（毫秒），由 tick 复位，避免卡住 */
	private long closeAt = 0L;
	private boolean tickerLogged = false;

	public TicketGateBlockEntity(final BlockPos pos, final BlockState state) {
		super(ModBlocks.TICKET_GATE_ENTITY, pos, state);
	}

	/* ------------------------------------------------------------------ 属性 */

	public String stationId() {
		return stationId;
	}

	public List<String> checkpointIds() {
		return checkpointIds;
	}

	public String mode() {
		return mode;
	}

	public void setBinding(final String stationId, final List<String> checkpointIds, final String mode) {
		this.stationId = stationId == null ? "" : stationId;
		this.checkpointIds = checkpointIds == null ? new ArrayList<>() : new ArrayList<>(checkpointIds);
		this.mode = mode == null || mode.isBlank() ? "ENTRY" : mode.toUpperCase();
		markDirty();
		applyKind();
	}

	/** 兼容旧调用（单检票口） */
	public void setCheckpoint(final String checkpointId, final String mode) {
		final TicketingManager manager = Mtr12306.manager();
		String station = stationId;
		final List<String> list = new ArrayList<>();
		if (checkpointId != null && !checkpointId.isEmpty()) {
			list.add(checkpointId);
			if (manager != null) {
				final Checkpoint checkpoint = manager.data().checkpoint(checkpointId);
				if (checkpoint != null) {
					station = checkpoint.stationId;
				}
			}
		}
		setBinding(station, list, mode);
	}

	private void applyKind() {
		if (world == null || world.isClient()) {
			return;
		}
		final BlockState state = world.getBlockState(pos);
		if (!state.contains(TicketGateBlock.KIND)) {
			return;
		}
		final boolean exitLike = "EXIT".equals(mode) || "TRANSFER".equals(mode) || "VALIDATE".equals(mode);
		final TicketGateBlock.GateKind kind = exitLike
				? TicketGateBlock.GateKind.EXIT : TicketGateBlock.GateKind.ENTRANCE;
		if (state.get(TicketGateBlock.KIND) != kind) {
			world.setBlockState(pos, state.with(TicketGateBlock.KIND, kind), 3);
		}
	}

	public String bindingLabel() {
		final TicketingManager manager = Mtr12306.manager();
		if (manager == null || stationId.isEmpty()) {
			return "未绑定车站";
		}
		return manager.checkpointLabel(stationId, checkpointIds);
	}

	public void syncBinding() {
		if (world == null) {
			return;
		}
		final TicketingManager manager = Mtr12306.manager();
		if (manager == null) {
			TicketingManager.queueGateRegistration(GateHelper.dimension(world), pos.getX(), pos.getY(), pos.getZ(),
					stationId, String.join(",", checkpointIds), mode);
			return;
		}
		manager.bindGateIfAbsent(GateHelper.dimension(world), pos.getX(), pos.getY(), pos.getZ(),
				stationId, checkpointIds, mode);
	}

	@Override
	public void setWorld(final World world) {
		super.setWorld(world);
		if (world != null && !world.isClient()) {
			syncBinding();
		}
	}

	@Override
	public void writeNbt(final NbtCompound nbt) {
		super.writeNbt(nbt);
		nbt.putString("stationId", stationId);
		nbt.putString("mode", mode);
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
		mode = nbt.contains("mode") ? nbt.getString("mode") : "ENTRY";
		checkpointIds = new ArrayList<>();
		if (nbt.contains("checkpointIds", NbtElement.LIST_TYPE)) {
			final NbtList list = nbt.getList("checkpointIds", NbtElement.STRING_TYPE);
			for (int i = 0; i < list.size(); i++) {
				checkpointIds.add(list.getString(i));
			}
		} else if (nbt.contains("checkpointId")) {
			final String legacy = nbt.getString("checkpointId");
			if (!legacy.isEmpty()) {
				checkpointIds.add(legacy);
			}
		}
	}

	@Override
	public NbtCompound toInitialChunkDataNbt() {
		return createNbt();
	}

	/* ------------------------------------------------------------------ 状态复位 */

	public void serverTick(final World world, final BlockPos pos) {
		if (!(world instanceof ServerWorld serverWorld)) {
			return;
		}
		if (!tickerLogged) {
			tickerLogged = true;
			Mtr12306.LOGGER.info("[MTR-12306] 闸机 {} 已开始工作（右键检票模式）", pos.toShortString());
		}
		final BlockState state = serverWorld.getBlockState(pos);
		if (state.get(TicketGateBlock.OPEN) == TicketGateBlock.GateState.CLOSED) {
			closeAt = 0L;
			return;
		}
		final long now = System.currentTimeMillis();
		if (closeAt == 0L) {
			closeAt = now + 5000L;
		}
		if (now >= closeAt) {
			serverWorld.setBlockState(pos, state.with(TicketGateBlock.OPEN, TicketGateBlock.GateState.CLOSED), 3);
			closeAt = 0L;
		}
	}

	/* ------------------------------------------------------------------ 右键检票 */

	public void tryPass(final ServerWorld world, final BlockPos pos, final BlockState state,
			final ServerPlayerEntity player) {
		if (state.get(TicketGateBlock.OPEN) != TicketGateBlock.GateState.CLOSED) {
			notify(player, "§e闸门正在通行，请稍候", 800L);
			return;
		}
		if (player.isSpectator()) {
			return;
		}
		final long now = System.currentTimeMillis();
		if (deniedUntil.getOrDefault(player.getUuid(), 0L) > now
				|| allowedUntil.getOrDefault(player.getUuid(), 0L) > now) {
			return;
		}
		final Direction facing = state.get(TicketGateBlock.FACING);
		final Vec3d center = Vec3d.ofCenter(pos);
		final double along = (player.getX() - center.x) * facing.getOffsetX()
				+ (player.getZ() - center.z) * facing.getOffsetZ();
		final boolean inbound = along <= 0.35D;

		final Decision decision = decide(world, pos, state, player.getGameProfile().getName(), inbound, true);
		if (decision.allowed()) {
			allowedUntil.put(player.getUuid(), now + Math.max(1, Mtr12306.CONFIG.gateOpenSeconds) * 1000L);
			deniedUntil.remove(player.getUuid());
			openGate(world, pos, state);
			playAllowEffects(world, pos, player, decision);
		} else {
			deniedUntil.put(player.getUuid(), now + 1500L);
			setPending(world, pos, state);
			world.playSound(null, pos, SoundEvents.ENTITY_VILLAGER_NO, SoundCategory.BLOCKS, 0.7F, 1.0F);
			world.spawnParticles(ParticleTypes.SMOKE, center.x, pos.getY() + 1.0, center.z, 6, 0.2, 0.2, 0.2, 0.01);
			notify(player, "§c✘ " + decision.message, 1500L);
			Mtr12306.LOGGER.info("[MTR-12306] 闸机 {} 拒绝 {}：{}", pos.toShortString(),
					player.getGameProfile().getName(), decision.message);
		}
	}

	public static final class Decision {
		public final String action;
		public final String message;
		public final String detail;
		public final String label;

		private Decision(final String action, final String message, final String detail, final String label) {
			this.action = action;
			this.message = message;
			this.detail = detail;
			this.label = label;
		}

		public boolean allowed() {
			return "ALLOW".equals(action);
		}
	}

	private static Decision allow(final String message, final String detail, final String label) {
		return new Decision("ALLOW", message, detail, label);
	}

	private static Decision deny(final String message) {
		return new Decision("DENY", message, "", "");
	}

	/** 判定（供右键与自检命令共用），consume=false 时只读 */
	public Decision decide(final World world, final BlockPos pos, final BlockState state, final String playerName,
			final boolean inbound, final boolean consume) {
		final TicketingManager manager = Mtr12306.manager();
		if (manager == null) {
			return deny("购票系统尚未就绪");
		}
		if (stationId.isEmpty()) {
			return deny("此闸机未绑定车站，请用「闸机配置面板」设置");
		}
		final String label = manager.checkpointLabel(stationId, checkpointIds);

		switch (mode) {
			case "VALIDATE" -> {
				if (!consume) {
					final TicketingManager.CheckResult probe = manager.checkEntry(playerName, stationId, checkpointIds, false);
					return allow("核验通过（只读）", probe.resultSummary(), label);
				}
				final var ticket = manager.validateTicket(playerName, Mtr12306.CONFIG.validateHours);
				if (ticket == null) {
					return deny("未查询到 " + Mtr12306.CONFIG.validateHours + " 小时内的有效车票");
				}
				return allow("车票核验通过", ticket.serviceNumber + " 次 " + ticket.date + " "
						+ manager.data().stationName(ticket.fromStationId) + " " + ticket.fromTime + " 开 · "
						+ ticket.seatClassLabel + " " + ticket.seat, label);
			}
			case "EXIT" -> {
				if (inbound) {
					return deny("此闸机仅限出站");
				}
				if (!consume) {
					return allow("可出站（只读）", "出站", label);
				}
				final var ticket = manager.exitLatest(playerName, stationId, checkpointIds);
				if (ticket == null) {
					return deny("没有可出站的车票（需先在本站进站检票）");
				}
				return allow("出站成功", ticket.serviceNumber + " 次 "
						+ manager.data().stationName(ticket.fromStationId) + " → "
						+ manager.data().stationName(ticket.toStationId) + " · " + ticket.seatClassLabel
						+ " " + ticket.seat, label);
			}
			case "TRANSFER" -> {
				if (!inbound) {
					return deny("换乘闸机请从站厅一侧右键");
				}
				final Checkpoint virtual = manager.virtualCheckpoint(stationId, checkpointIds);
				final TicketingManager.TransferResult result = manager.transfer(playerName, virtual, consume);
				if (!result.allow) {
					return deny(result.reason);
				}
				final String detail = (result.arrivingSegment.isEmpty() ? "" : result.arrivingSegment + "\n")
						+ result.nextSegment;
				return allow("换乘成功", detail, label);
			}
			case "BOTH" -> {
				if (!inbound) {
					if (!consume) {
						return allow("可出站（只读）", "出站", label);
					}
					final var ticket = manager.exitLatest(playerName, stationId, checkpointIds);
					if (ticket == null) {
						return deny("没有可出站的车票（需先在本站进站检票）");
					}
					return allow("出站成功", ticket.serviceNumber + " 次 "
							+ manager.data().stationName(ticket.fromStationId) + " → "
							+ manager.data().stationName(ticket.toStationId), label);
				}
				final TicketingManager.CheckResult result = manager.checkEntry(playerName, stationId, checkpointIds, consume);
				return result.allow
						? allow("检票通过", result.serviceNumber + " 次 · " + result.seatClassLabel + " " + result.seat
						+ " · 票价 " + result.price + " 元\n" + result.segment, label)
						: deny(result.reason);
			}
			default -> {
				if (!inbound) {
					return deny("此闸机仅限进站");
				}
				final TicketingManager.CheckResult result = manager.checkEntry(playerName, stationId, checkpointIds, consume);
				return result.allow
						? allow("检票通过", result.serviceNumber + " 次 · " + result.seatClassLabel + " " + result.seat
						+ " · 票价 " + result.price + " 元\n" + result.segment, label)
						: deny(result.reason);
			}
		}
	}

	/* ------------------------------------------------------------------ 效果 */

	private void openGate(final ServerWorld world, final BlockPos pos, final BlockState state) {
		closeAt = System.currentTimeMillis() + Math.max(1, Mtr12306.CONFIG.gateOpenSeconds) * 1000L;
		world.setBlockState(pos, state.with(TicketGateBlock.OPEN, TicketGateBlock.GateState.OPEN), 3);
		world.scheduleBlockTick(pos, state.getBlock(), Math.max(1, Mtr12306.CONFIG.gateOpenSeconds) * 20);
	}

	private void setPending(final ServerWorld world, final BlockPos pos, final BlockState state) {
		closeAt = System.currentTimeMillis() + 1500L;
		world.setBlockState(pos, state.with(TicketGateBlock.OPEN, TicketGateBlock.GateState.PENDING), 3);
		world.scheduleBlockTick(pos, state.getBlock(), 30);
	}

	private void playAllowEffects(final ServerWorld world, final BlockPos pos, final ServerPlayerEntity player,
			final Decision decision) {
		world.playSound(null, pos, SoundEvents.BLOCK_IRON_TRAPDOOR_OPEN, SoundCategory.BLOCKS, 0.7F, 1.5F);
		world.playSound(null, pos, SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.BLOCKS, 0.6F, 1.2F);
		world.spawnParticles(ParticleTypes.HAPPY_VILLAGER, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5,
				10, 0.3, 0.4, 0.3, 0.02);
		if (player.networkHandler != null) {
			final String[] lines = decision.detail.split("\\n");
			player.networkHandler.sendPacket(new TitleS2CPacket(Text.literal("§a" + decision.message)));
			player.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal("§f" + (lines.length > 0 ? lines[0] : decision.label))));
			player.networkHandler.sendPacket(new TitleFadeS2CPacket(4, 55, 10));
		}
		for (final String line : decision.detail.split("\\n")) {
			if (!line.isBlank()) {
				safeMessage(player, Text.literal("§a✔ §f" + line), false);
			}
		}
		safeMessage(player, Text.literal("§7闸门已开启 " + Mtr12306.CONFIG.gateOpenSeconds + " 秒 · " + decision.label), true);
	}

	public static void safeMessage(final ServerPlayerEntity player, final Text message, final boolean overlay) {
		if (player.networkHandler != null) {
			player.sendMessage(message, overlay);
		}
	}

	private void notify(final ServerPlayerEntity player, final String message, final long cooldownMillis) {
		final long now = System.currentTimeMillis();
		final Long last = messageCooldown.get(player.getUuid());
		if (last != null && now < last) {
			return;
		}
		messageCooldown.put(player.getUuid(), now + cooldownMillis);
		safeMessage(player, Text.literal(message), true);
	}
}
