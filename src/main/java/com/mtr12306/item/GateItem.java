package com.mtr12306.item;

import com.mtr12306.block.TicketGateBlockEntity;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * 闸机物品：放置时把闸机预设为指定通行模式（进站 / 出站 / 双向 / 换乘）。
 */
public class GateItem extends BlockItem {
	private final String mode;

	public GateItem(final Block block, final Settings settings, final String mode) {
		super(block, settings);
		this.mode = mode;
	}

	public String mode() {
		return mode;
	}

	@Override
	protected boolean postPlacement(final BlockPos pos, final World world, @Nullable final PlayerEntity player,
			final ItemStack stack, final BlockState state) {
		final boolean result = super.postPlacement(pos, world, player, stack, state);
		if (!world.isClient() && world.getBlockEntity(pos) instanceof TicketGateBlockEntity gate) {
			gate.setCheckpoint(gate.checkpointId(), mode);
			gate.syncBinding();
		}
		return result;
	}

	@Override
	public String getTranslationKey() {
		return "item.mtr12306.ticket_gate_" + mode.toLowerCase();
	}
}
