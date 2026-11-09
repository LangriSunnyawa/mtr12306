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
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 闸机方块实体：保存所属检票口与通行模式，并处理玩家经过时的检票逻辑。
 * 闸机正面（FACING 方向）为站台侧，玩家从背面走向正面即为进站。
 *
 * <p>模式：
 * ENTRY=进站检票，EXIT=出站放行（记录出闸），BOTH=双向（进站查票/出站查今日已检票），
 * TRANSFER=换乘（一次性完成上一段出闸 + 接续车次检票）。</p>
 */
public class TicketGateBlockEntity extends BlockEntity {
	/** 放行后保持开门的时间（tick），与 MTR 的 40 tick 一致 */
	private static final int OPEN_TICKS = 40;
	/** 拒绝时红箭头显示时间（tick） */
	private static final int DENY_TICKS = 30;

	private String checkpointId = "";
	private String mode = "ENTRY";

	private final Map<UUID, Long> allowedUntil = new HashMap<>();
	private final Map<UUID, Long> deniedUntil = new HashMap<>();
	private final Map<UUID, Long> messageCooldown = new HashMap<>();

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
		// 区块加载（含旧存档、结构生成、/setblock）时把闸机补登记到购票系统
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

	/* ------------------------------------------------------------------ 检票 */

	public void handlePlayer(final ServerWorld world, final BlockPos pos, final BlockState state,
			final ServerPlayerEntity player) {
		// 与 MTR 一致：只在待机状态触发，开门期间不重复检票
		if (state.get(TicketGateBlock.OPEN) != TicketGateBlock.GateState.CLOSED || player.isSpectator()
				|| player.isCreative()) {
			return;
		}
		final long now = System.currentTimeMillis();
		if (allowedUntil.size() > 64) {
			allowedUntil.values().removeIf(time -> time < now);
			deniedUntil.values().removeIf(time -> time < now);
			messageCooldown.values().removeIf(time -> time < now);
		}
		final Long allowed = allowedUntil.get(player.getUuid());
		if (allowed != null && now < allowed) {
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

		final TicketingManager manager = Mtr12306.manager();
		if (manager == null) {
			return;
		}
		syncBinding();
		final Checkpoint checkpoint = manager.data().checkpoint(checkpointId);

		// 出站闸机：只放行从站台侧走出来的人，并记录出闸
		if ("EXIT".equals(mode)) {
			if (inbound) {
				pushBack(player, center, fx, fz, true);
				notify(player, "§c此闸机仅限出站，请走进站闸机", 2000L);
				return;
			}
			if (checkpoint != null) {
				manager.markExits(player.getGameProfile().getName(), checkpoint.stationId, checkpointId);
			}
			allow(world, pos, state, player);
			world.playSound(null, pos, SoundEvents.BLOCK_IRON_TRAPDOOR_OPEN, SoundCategory.BLOCKS, 0.6F, 1.5F);
			player.sendMessage(Text.literal("§a出站成功 §7| 感谢乘坐"), true);
			return;
		}

		if (checkpoint == null) {
			notify(player, "§c此闸机尚未绑定检票口，请用配置面板右键设置", 4000L);
			return;
		}

		// 换乘闸机：出闸 + 接续车次检票，一次完成
		if ("TRANSFER".equals(mode)) {
			final TicketingManager.TransferResult result = manager.transfer(player.getGameProfile().getName(), checkpoint);
			if (!result.allow) {
				deny(world, pos, state, player, center, fx, fz, true, result.reason);
				return;
			}
			allow(world, pos, state, player);
			world.playSound(null, pos, SoundEvents.BLOCK_IRON_TRAPDOOR_OPEN, SoundCategory.BLOCKS, 0.7F, 1.5F);
			world.playSound(null, pos, SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.BLOCKS, 0.6F, 1.2F);
			world.spawnParticles(ParticleTypes.HAPPY_VILLAGER, center.x, pos.getY() + 1.0, center.z, 12, 0.3, 0.4, 0.3, 0.02);
			player.networkHandler.sendPacket(new TitleS2CPacket(Text.literal("§a换乘成功")));
			player.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal(result.nextSegment)));
			player.networkHandler.sendPacket(new TitleFadeS2CPacket(4, 70, 10));
			if (!result.arrivingSegment.isEmpty()) {
				player.sendMessage(Text.literal("§a✔ " + result.arrivingSegment), false);
			}
			player.sendMessage(Text.literal("§a✔ " + result.nextSegment), false);
			player.sendMessage(Text.literal("§7票号 " + result.next.id + "  检票口 " + result.checkpointName), false);
			return;
		}

		if (!inbound) {
			// 站台侧进入：只有双向闸机才处理（出站方向）
			if (!"BOTH".equals(mode)) {
				pushBack(player, center, fx, fz, false);
				notify(player, "§c此闸机仅限进站", 2000L);
				return;
			}
			if (manager.hasBoardedToday(player.getGameProfile().getName())) {
				manager.markExits(player.getGameProfile().getName(), checkpoint.stationId, checkpointId);
				allow(world, pos, state, player);
				world.playSound(null, pos, SoundEvents.BLOCK_IRON_TRAPDOOR_OPEN, SoundCategory.BLOCKS, 0.6F, 1.5F);
				player.sendMessage(Text.literal("§a出站成功 §7| 感谢乘坐"), true);
			} else {
				deny(world, pos, state, player, center, fx, fz, false, "今日没有已检票的车票，无法出站");
			}
			return;
		}

		// 进站方向：正常检票
		final TicketingManager.CheckResult result = manager.checkPlayer(player.getGameProfile().getName(), checkpoint, true);
		if (result.allow) {
			allow(world, pos, state, player);
			world.playSound(null, pos, SoundEvents.BLOCK_IRON_TRAPDOOR_OPEN, SoundCategory.BLOCKS, 0.7F, 1.5F);
			world.playSound(null, pos, SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.BLOCKS, 0.6F, 1.2F);
			world.spawnParticles(ParticleTypes.HAPPY_VILLAGER, center.x, pos.getY() + 1.0, center.z, 10, 0.3, 0.4, 0.3, 0.02);
			player.networkHandler.sendPacket(new TitleS2CPacket(Text.literal("§a检票通过")));
			player.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal(result.segment)));
			player.networkHandler.sendPacket(new TitleFadeS2CPacket(4, 60, 10));
			player.sendMessage(Text.literal("§a✔ 检票成功 §7| §f" + result.serviceNumber + " 次 §7| §f"
					+ result.seatClassLabel + " " + result.seat + " §7| 票价 " + result.price + " 元"), false);
			player.sendMessage(Text.literal("§f" + result.segment), false);
			player.sendMessage(Text.literal("§7票号 " + result.ticket.id + "  检票口 " + result.checkpointName), false);
		} else {
			deny(world, pos, state, player, center, fx, fz, true, result.reason);
		}
	}

	private void allow(final ServerWorld world, final BlockPos pos, final BlockState state,
			final ServerPlayerEntity player) {
		allowedUntil.put(player.getUuid(), System.currentTimeMillis() + OPEN_TICKS * 50L);
		deniedUntil.remove(player.getUuid());
		world.setBlockState(pos, state.with(TicketGateBlock.OPEN, TicketGateBlock.GateState.OPEN), 3);
		world.scheduleBlockTick(pos, state.getBlock(), OPEN_TICKS);
	}

	private void deny(final ServerWorld world, final BlockPos pos, final BlockState state,
			final ServerPlayerEntity player, final Vec3d center, final double fx, final double fz,
			final boolean inbound, final String reason) {
		deniedUntil.put(player.getUuid(), System.currentTimeMillis() + 1500L);
		world.setBlockState(pos, state.with(TicketGateBlock.OPEN, TicketGateBlock.GateState.PENDING), 3);
		world.scheduleBlockTick(pos, state.getBlock(), DENY_TICKS);
		world.playSound(null, pos, SoundEvents.ENTITY_VILLAGER_NO, SoundCategory.BLOCKS, 0.7F, 1.0F);
		world.spawnParticles(ParticleTypes.SMOKE, center.x, pos.getY() + 1.0, center.z, 6, 0.2, 0.2, 0.2, 0.01);
		pushBack(player, center, fx, fz, inbound);
		notify(player, "§c✘ 检票失败：" + reason, 1500L);
	}

	/** 把玩家推回进入前那一侧（闸机只挡机柜，需要主动推回） */
	private void pushBack(final ServerPlayerEntity player, final Vec3d center, final double fx, final double fz,
			final boolean inbound) {
		final double sign = inbound ? -1.0D : 1.0D;
		player.requestTeleport(center.x + fx * 1.15 * sign, player.getY(), center.z + fz * 1.15 * sign);
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
		player.sendMessage(Text.literal(message), true);
	}
}
