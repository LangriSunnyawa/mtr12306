package com.mtr12306.block;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public final class GateHelper {
	private GateHelper() {
	}

	public static String dimension(final World world) {
		return world.getRegistryKey().getValue().toString();
	}

	public static String key(final World world, final BlockPos pos) {
		return dimension(world) + "@" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
	}
}
