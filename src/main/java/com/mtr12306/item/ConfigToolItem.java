package com.mtr12306.item;

import com.mtr12306.block.TicketGateBlockEntity;
import com.mtr12306.net.ModNetworking;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * 配置面板：管理员（OP）拿它右键闸机即可唤出闸机配置菜单。
 */
public class ConfigToolItem extends Item {
	public ConfigToolItem(final Settings settings) {
		super(settings);
	}

	@Override
	public ActionResult useOnBlock(final ItemUsageContext context) {
		final World world = context.getWorld();
		final PlayerEntity player = context.getPlayer();
		final BlockPos pos = context.getBlockPos();
		if (world.isClient()) {
			return ActionResult.SUCCESS;
		}
		if (!(player instanceof ServerPlayerEntity serverPlayer)) {
			return ActionResult.PASS;
		}
		if (!serverPlayer.hasPermissionLevel(2)) {
			serverPlayer.sendMessage(Text.literal("§c配置闸机需要 OP（管理员）权限"), true);
			return ActionResult.FAIL;
		}
		if (world.getBlockEntity(pos) instanceof TicketGateBlockEntity gate) {
			ModNetworking.openGateConfig(serverPlayer, gate);
			return ActionResult.SUCCESS;
		}
		serverPlayer.sendMessage(Text.literal("§c请对着检票闸机使用配置面板"), true);
		return ActionResult.PASS;
	}

	@Override
	public TypedActionResult<net.minecraft.item.ItemStack> use(final World world, final PlayerEntity user,
			final Hand hand) {
		if (!world.isClient() && user instanceof ServerPlayerEntity serverPlayer) {
			serverPlayer.sendMessage(Text.literal("§7用法：拿着配置面板 §f右键闸机§7 打开配置菜单（需要 OP 权限）"), false);
			serverPlayer.sendMessage(Text.literal("§7可设置：所属检票口（仅限闸机所在 MTR 车站）与通行模式"), false);
		}
		return super.use(world, user, hand);
	}
}
