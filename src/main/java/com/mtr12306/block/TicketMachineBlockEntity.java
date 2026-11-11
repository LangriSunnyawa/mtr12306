package com.mtr12306.block;

import com.mtr12306.Mtr12306;
import com.mtr12306.TicketingManager;
import com.mtr12306.data.Station;
import com.mtr12306.mtr.MtrIntegration;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * 售票机方块实体：只负责判断自己属于哪个车站（按所在位置的 MTR 车站名匹配本系统车站）。
 */
public class TicketMachineBlockEntity extends BlockEntity {
	public TicketMachineBlockEntity(final BlockPos pos, final BlockState state) {
		super(ModBlocks.TICKET_MACHINE_ENTITY, pos, state);
	}

	@Override
	public void writeNbt(final NbtCompound nbt) {
		super.writeNbt(nbt);
	}

	@Override
	public void readNbt(final NbtCompound nbt) {
		super.readNbt(nbt);
	}

	@Override
	public NbtCompound toInitialChunkDataNbt() {
		return createNbt();
	}

	/** 机器所属车站 id（识别不到时返回第一个车站，再不行返回空串） */
	public String stationId(final ServerWorld world, final BlockPos pos) {
		final TicketingManager manager = Mtr12306.manager();
		if (manager == null) {
			return "";
		}
		final String mtrStation = MtrIntegration.stationNameAt(GateHelper.dimension(world), pos.getX(), pos.getY(),
				pos.getZ());
		if (!mtrStation.isBlank()) {
			for (final Station station : manager.data().stations.values()) {
				if (station.name.equalsIgnoreCase(mtrStation) || station.mtrStation.equalsIgnoreCase(mtrStation)) {
					return station.id;
				}
			}
		}
		return manager.data().stations.isEmpty() ? "" : manager.data().stations.values().iterator().next().id;
	}
}
