package com.mtr12306.block;

import com.mtr12306.Mtr12306;
import com.mtr12306.TicketingManager;
import com.mtr12306.data.Checkpoint;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
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
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 闸机方块实体：保存所属检票口与通行模式，并处理玩家通过时的检票。
 *
 * <p>检测有两条通道，任意一条命中即可：
 * ① 方块的 {@code onEntityCollision}（玩家移动时由原版调用）；
 * ② 方块实体每 2 tick 扫描一次与自身方块体积相交的玩家（站着不动、被推挤等也可靠）。</p>
 *
 * <p>模式：ENTRY 进站检票 / EXIT 出站放行（记录出闸）/ BOTH 双向 / TRANSFER 换乘。</p>
 */
public class TicketGateBlockEntity extends BlockEntity {
	/** 放行后保持开门的时间（tick），与 MTR 的 40 tick 一致 */
	public static final int OPEN_TICKS = 40;
	/** 拒绝时红箭头显示时间（tick） */
	public static final int DENY_TICKS = 30;
	/** 同一玩家两次处理的最小间隔（毫秒），避免两条检测通道重复检票 */
	private static final long HANDLE_COOLDOWN = 400L;

	private String checkpointId = "";
	private String mode = "ENTRY";

	/** 放行后到该时刻前不再重复检票 */
	private final Map<UUID, Long> allowedUntil = new HashMap<>();
	/** 拒绝后到该时刻前持续推回 */
	private final Map<UUID, Long> deniedUntil = new HashMap<>();
	/** 提示节流 */
	private final Map<UUID, Long> messageCooldown = new HashMap<>();
	/** 处理节流（两条检测通道去重） */
	private final Map<UUID, Long> lastHandle = new HashMap<>();
	/** 未绑定检票口的告警节流 */
	private long lastUnboundWarning = 0L;
	/** 闸机进入 open/pending 的时刻，用于兜底复位（防止区块未加载等原因导致卡在开门状态） */
	private long stateChangedAt = 0L;
	/** 非 closed 状态的最长保持时间（毫秒） */
	private static final long STATE_SAFETY_MILLIS = 5000L;
	/** 是否已记录过"ticker 已注册"日志 */
	private boolean tickerLogged = false;
	/** 是否已记录过"缓存状态滞后"日志 */
	private boolean stateMismatchLogged = false;
	/** 开/拒绝状态应复位的时间戳（毫秒） */
	private long closeAt = 0L;
	private long lastHeartbeat = 0L;

	public TicketGateBlockEntity(final BlockPos pos, final BlockState state) {
		super(ModBlocks.TICKET_GATE_ENTITY, pos, state);
	}

	/* ------------------------------------------------------------------ 属性 */

	public String checkpointId() {
		return checkpointId;
	}

	public String mode() {
		return mode;
	}

	public void setCheckpoint(final String checkpointId, final String mode) {
		this.checkpointId = checkpointId == null ? "" : checkpointId;
		this.mode = mode == null || mode.isBlank() ? "ENTRY" : mode.toUpperCase();
		markDirty();
		applyKind();
	}

	/** 出站/换乘闸机用绿色底座模型，进站/双向用橙色底座模型 */
	private void applyKind() {
		if (world == null || world.isClient()) {
			return;
		}
		final BlockState state = world.getBlockState(pos);
		if (!state.contains(TicketGateBlock.KIND)) {
			return;
		}
		final boolean exitLike = "EXIT".equals(mode) || "TRANSFER".equals(mode);
		final TicketGateBlock.GateKind kind = exitLike
				? TicketGateBlock.GateKind.EXIT : TicketGateBlock.GateKind.ENTRANCE;
		if (state.get(TicketGateBlock.KIND) != kind) {
			world.setBlockState(pos, state.with(TicketGateBlock.KIND, kind), 3);
		}
	}

	public String checkpointName() {
		final TicketingManager manager = Mtr12306.manager();
		if (manager == null || checkpointId.isEmpty()) {
			return "未绑定";
		}
		final Checkpoint checkpoint = manager.data().checkpoint(checkpointId);
		if (checkpoint == null) {
			return "未绑定";
		}
		return manager.data().stationName(checkpoint.stationId) + " " + checkpoint.name;
	}

	/** 把绑定关系同步到购票系统数据（供网页后台查看） */
	public void syncBinding() {
		if (world == null) {
			return;
		}
		final TicketingManager manager = Mtr12306.manager();
		if (manager == null) {
			TicketingManager.queueGateRegistration(GateHelper.dimension(world), pos.getX(), pos.getY(), pos.getZ(),
					checkpointId, mode);
			return;
		}
		manager.bindGateIfAbsent(GateHelper.dimension(world), pos.getX(), pos.getY(), pos.getZ(), checkpointId, mode);
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
		nbt.putString("checkpointId", checkpointId);
		nbt.putString("mode", mode);
	}

	@Override
	public void readNbt(final NbtCompound nbt) {
		super.readNbt(nbt);
		checkpointId = nbt.getString("checkpointId");
		mode = nbt.contains("mode") ? nbt.getString("mode") : "ENTRY";
	}

	@Override
	public NbtCompound toInitialChunkDataNbt() {
		return createNbt();
	}

	/* ------------------------------------------------------------------ 检测通道 */

	/** 通道②：每 2 tick 扫描与本方块相交的玩家（与 MTR 一样按方块位置判定，不依赖碰撞箱） */
	public void serverTick(final World world, final BlockPos pos, final BlockState tickedState) {
		if (world.isClient() || !(world instanceof ServerWorld serverWorld)) {
			return;
		}
		// 方块实体 ticker 传进来的是"缓存状态"，方块状态被改变后可能滞后；这里一律以实时状态为准
		final BlockState state = serverWorld.getBlockState(pos);
		if (tickedState != state && !stateMismatchLogged) {
			stateMismatchLogged = true;
			Mtr12306.LOGGER.info("[MTR-12306] 闸机 {} ticker 缓存状态滞后，已改用实时状态 {}", pos.toShortString(),
					state.get(TicketGateBlock.OPEN));
		}
		if (!tickerLogged) {
			tickerLogged = true;
			Mtr12306.LOGGER.info("[MTR-12306] 闸机 {} 已开始工作（方块实体 ticker 已注册）", pos.toShortString());
		}
		final long now = System.currentTimeMillis();
		if (state.get(TicketGateBlock.OPEN) != TicketGateBlock.GateState.CLOSED) {
			// 闸机处于开门/拒绝指示状态：由这里按"到期时间"复位，不依赖 scheduledTick，
			// 也顺带兜底处理服务器重启后残留的 open 状态（否则会一直放人通过）。
			if (stateChangedAt == 0L) {
				stateChangedAt = now;
				closeAt = now + STATE_SAFETY_MILLIS;
			}
			if (now >= closeAt) {
				serverWorld.setBlockState(pos, state.with(TicketGateBlock.OPEN, TicketGateBlock.GateState.CLOSED), 3);
				stateChangedAt = 0L;
				closeAt = 0L;
			}
			return;
		}
		stateChangedAt = 0L;
		closeAt = 0L;
		if (serverWorld.getTime() % 2 != 0) {
			return;
		}
		final Box volume = new Box(pos).expand(0.08);
		for (final ServerPlayerEntity player : serverWorld.getPlayers(candidate ->
				candidate.getBoundingBox().intersects(volume))) {
			handlePlayer(serverWorld, pos, state, player);
		}
	}

	/** 通道①与②共用的入口 */
	public void handlePlayer(final ServerWorld world, final BlockPos pos, final BlockState state,
			final ServerPlayerEntity player) {
		if (state.get(TicketGateBlock.OPEN) != TicketGateBlock.GateState.CLOSED) {
			return;
		}
		if (player.isSpectator()) {
			return;
		}
		final long now = System.currentTimeMillis();
		if (lastHandle.size() > 128) {
			lastHandle.values().removeIf(time -> time < now - 5000L);
			allowedUntil.values().removeIf(time -> time < now);
			deniedUntil.values().removeIf(time -> time < now);
			messageCooldown.values().removeIf(time -> time < now);
		}
		final Long last = lastHandle.get(player.getUuid());
		if (last != null && now - last < HANDLE_COOLDOWN) {
			return;
		}
		lastHandle.put(player.getUuid(), now);
		if (allowedUntil.getOrDefault(player.getUuid(), 0L) > now) {
			return;
		}

		final Direction facing = state.get(TicketGateBlock.FACING);
		final double fx = facing.getOffsetX();
		final double fz = facing.getOffsetZ();
		final Vec3d center = Vec3d.ofCenter(pos);
		final double along = (player.getX() - center.x) * fx + (player.getZ() - center.z) * fz;
		final boolean inbound = along <= 0.35;

		final Long denied = deniedUntil.get(player.getUuid());
		if (denied != null && now < denied) {
			pushBack(player, center, fx, fz, inbound);
			return;
		}

		final Decision decision = decide(world, pos, state, player.getGameProfile().getName(), inbound, true);
		switch (decision.action) {
			case "ALLOW" -> {
				allowedUntil.put(player.getUuid(), now + OPEN_TICKS * 50L);
				deniedUntil.remove(player.getUuid());
				openGate(world, pos, state);
				playAllowEffects(world, pos, player, decision);
			}
			case "DENY" -> {
				deniedUntil.put(player.getUuid(), now + 1500L);
				setPending(world, pos, state);
				world.playSound(null, pos, SoundEvents.ENTITY_VILLAGER_NO, SoundCategory.BLOCKS, 0.7F, 1.0F);
				world.spawnParticles(ParticleTypes.SMOKE, center.x, pos.getY() + 1.0, center.z, 6, 0.2, 0.2, 0.2, 0.01);
				pushBack(player, center, fx, fz, inbound);
				notify(player, "§c✘ 检票失败：" + decision.message, 1500L);
				Mtr12306.LOGGER.info("[MTR-12306] 闸机 {} 拒绝 {}（{}）：{}", pos.toShortString(),
						player.getGameProfile().getName(), inbound ? "进站方向" : "出站方向", decision.message);
			}
			default -> {
			}
		}
	}

	/* ------------------------------------------------------------------ 判定（可被命令/测试调用） */

	public static final class Decision {
		public final String action;
		public final String message;
		public final String detail;
		public final String checkpoint;

		private Decision(final String action, final String message, final String detail, final String checkpoint) {
			this.action = action;
			this.message = message;
			this.detail = detail;
			this.checkpoint = checkpoint;
		}

		static Decision allow(final String message, final String detail, final String checkpoint) {
			return new Decision("ALLOW", message, detail, checkpoint);
		}

		static Decision deny(final String message) {
			return new Decision("DENY", message, "", "");
		}

		static Decision ignore(final String message) {
			return new Decision("IGNORE", message, "", "");
		}

		public boolean allowed() {
			return "ALLOW".equals(action);
		}
	}

	/**
	 * 判定某玩家在该闸机前的通过请求。consume=false 时不会核销车票（供后台/命令自检用）。
	 */
	public Decision decide(final World world, final BlockPos pos, final BlockState state, final String playerName,
			final boolean inbound, final boolean consume) {
		final TicketingManager manager = Mtr12306.manager();
		if (manager == null) {
			return Decision.deny("购票系统尚未就绪");
		}
		syncBinding();
		final Checkpoint checkpoint = manager.data().checkpoint(checkpointId);

		// 出站闸机：不查票，只放行从站台侧走出的人
		if ("EXIT".equals(mode)) {
			if (inbound) {
				return Decision.deny("此闸机仅限出站，请走进站闸机");
			}
			int exits = 0;
			if (checkpoint != null && consume) {
				exits = manager.markExits(playerName, checkpoint.stationId, checkpointId);
			}
			return Decision.allow("出站成功" + (exits > 0 ? "（已记录 " + exits + " 张出闸）" : ""),
					"出站", checkpoint == null ? "未绑定" : checkpoint.name);
		}

		if (checkpoint == null) {
			// 未绑定检票口：明确拦截，避免"看起来能用其实不检票"
			if (System.currentTimeMillis() - lastUnboundWarning > 10_000L) {
				lastUnboundWarning = System.currentTimeMillis();
				Mtr12306.LOGGER.warn("[MTR-12306] 闸机 {} 未绑定检票口，已拦截通过；请用「闸机配置面板」右键设置",
						pos.toShortString());
			}
			return Decision.deny("此闸机未绑定检票口，请联系管理员用「闸机配置面板」设置");
		}

		// 换乘闸机
		if ("TRANSFER".equals(mode)) {
			if (!inbound) {
				return Decision.deny("换乘闸机请从站厅一侧通过");
			}
			final TicketingManager.TransferResult result = manager.transfer(playerName, checkpoint, consume);
			if (!result.allow) {
				return Decision.deny(result.reason);
			}
			final String detail = (result.arrivingSegment.isEmpty() ? "" : result.arrivingSegment + "\n")
					+ result.nextSegment;
			return Decision.allow(result.nextSegment, detail, checkpoint.name);
		}

		if (!inbound) {
			// 站台侧进入：只有双向闸机处理出站方向
			if (!"BOTH".equals(mode)) {
				return Decision.deny("此闸机仅限进站");
			}
			if (manager.hasBoardedToday(playerName)) {
				if (consume) {
					manager.markExits(playerName, checkpoint.stationId, checkpointId);
				}
				return Decision.allow("出站成功", "出站", checkpoint.name);
			}
			return Decision.deny("今日没有已检票的车票，无法出站");
		}

		// 进站检票
		final TicketingManager.CheckResult result = manager.checkPlayer(playerName, checkpoint, consume);
		if (!result.allow) {
			return Decision.deny(result.reason);
		}
		return Decision.allow("检票通过", result.serviceNumber + " 次 · " + result.seatClassLabel + " " + result.seat
				+ " · 票价 " + result.price + " 元\n" + result.segment, result.checkpointName);
	}

	/* ------------------------------------------------------------------ 效果 */

	/** 供管理员自检命令使用：按判定结果直接驱动闸机状态（不含玩家提示） */
	public void applyDecision(final ServerWorld world, final BlockPos pos, final BlockState state,
			final Decision decision) {
		if (decision.allowed()) {
			allowedUntil.clear();
			deniedUntil.clear();
			openGate(world, pos, state);
		} else {
			setPending(world, pos, state);
		}
	}

	private void openGate(final ServerWorld world, final BlockPos pos, final BlockState state) {
		stateChangedAt = System.currentTimeMillis();
		closeAt = stateChangedAt + OPEN_TICKS * 50L;
		world.setBlockState(pos, state.with(TicketGateBlock.OPEN, TicketGateBlock.GateState.OPEN), 3);
		world.scheduleBlockTick(pos, state.getBlock(), OPEN_TICKS);
	}

	private void setPending(final ServerWorld world, final BlockPos pos, final BlockState state) {
		stateChangedAt = System.currentTimeMillis();
		closeAt = stateChangedAt + DENY_TICKS * 50L;
		world.setBlockState(pos, state.with(TicketGateBlock.OPEN, TicketGateBlock.GateState.PENDING), 3);
		world.scheduleBlockTick(pos, state.getBlock(), DENY_TICKS);
	}

	private void playAllowEffects(final ServerWorld world, final BlockPos pos, final ServerPlayerEntity player,
			final Decision decision) {
		world.playSound(null, pos, SoundEvents.BLOCK_IRON_TRAPDOOR_OPEN, SoundCategory.BLOCKS, 0.7F, 1.5F);
		world.playSound(null, pos, SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.BLOCKS, 0.6F, 1.2F);
		world.spawnParticles(ParticleTypes.HAPPY_VILLAGER, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5,
				10, 0.3, 0.4, 0.3, 0.02);
		if (player.networkHandler != null) {
			player.networkHandler.sendPacket(new TitleS2CPacket(Text.literal("§a" + decision.message)));
			player.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal(
					decision.detail.isEmpty() ? ("§f" + decision.checkpoint) : decision.detail.split("\n")[0])));
			player.networkHandler.sendPacket(new TitleFadeS2CPacket(4, 60, 10));
		}
		for (final String line : decision.detail.split("\n")) {
			if (!line.isBlank()) {
				safeMessage(player, Text.literal("§a✔ §f" + line), false);
			}
		}
		safeMessage(player, Text.literal("§7闸机状态：§a已放行 §7| 检票口 " + decision.checkpoint), true);
	}

	/** 无网络连接时（例如自检用的临时玩家）跳过消息发送，避免 NPE */
	public static void safeMessage(final ServerPlayerEntity player, final Text message, final boolean overlay) {
		if (player.networkHandler != null) {
			player.sendMessage(message, overlay);
		}
	}

	/** 把玩家推回进入前那一侧（闸机只挡机柜，需要主动推回） */
	private void pushBack(final ServerPlayerEntity player, final Vec3d center, final double fx, final double fz,
			final boolean inbound) {
		final double sign = inbound ? -1.0D : 1.0D;
		final double targetX = center.x + fx * 1.15 * sign;
		final double targetZ = center.z + fz * 1.15 * sign;
		if (player.networkHandler != null) {
			player.requestTeleport(targetX, player.getY(), targetZ);
		} else {
			// 无网络连接（例如自检用的临时玩家）时直接改坐标
			player.setPos(targetX, player.getY(), targetZ);
		}
		player.setVelocity(Vec3d.ZERO);
		player.velocityModified = true;
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
