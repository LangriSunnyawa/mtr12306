package com.mtr12306.item;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

/**
 * 配置道具判定：MTR 原版的「刷子」({@code mtr:brush}) 与本模组的配置面板都可用来配置闸机/大屏/广播器。
 */
public final class ConfigTools {
	private static Item mtrBrush = null;
	private static boolean resolved = false;

	private ConfigTools() {
	}

	public static boolean isConfigTool(final ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return false;
		}
		if (stack.getItem() instanceof ConfigToolItem) {
			return true;
		}
		return stack.isOf(mtrBrush());
	}

	private static Item mtrBrush() {
		if (!resolved) {
			resolved = true;
			mtrBrush = Registries.ITEM.get(new Identifier("mtr", "brush"));
		}
		return mtrBrush;
	}
}
