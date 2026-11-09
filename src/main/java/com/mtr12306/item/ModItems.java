package com.mtr12306.item;

import com.mtr12306.Mtr12306;
import com.mtr12306.block.ModBlocks;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.text.Text;

/**
 * 物品注册：四种通行模式的闸机 + 配置面板，并放进独立的创造模式分类。
 */
public final class ModItems {
	public static final Item GATE_ENTRY = new GateItem(ModBlocks.TICKET_GATE, new Item.Settings(), "ENTRY");
	public static final Item GATE_EXIT = new GateItem(ModBlocks.TICKET_GATE, new Item.Settings(), "EXIT");
	public static final Item GATE_BOTH = new GateItem(ModBlocks.TICKET_GATE, new Item.Settings(), "BOTH");
	public static final Item GATE_TRANSFER = new GateItem(ModBlocks.TICKET_GATE, new Item.Settings(), "TRANSFER");
	public static final Item CONFIG_TOOL = new ConfigToolItem(new Item.Settings().maxCount(1));

	private static final ItemGroup GROUP = FabricItemGroup.builder()
			.displayName(Text.translatable("itemGroup.mtr12306.gates"))
			.icon(() -> new ItemStack(GATE_ENTRY))
			.entries((context, entries) -> {
				entries.add(GATE_ENTRY);
				entries.add(GATE_EXIT);
				entries.add(GATE_BOTH);
				entries.add(GATE_TRANSFER);
				entries.add(CONFIG_TOOL);
			})
			.build();

	private ModItems() {
	}

	public static void register() {
		register("ticket_gate_entry", GATE_ENTRY);
		register("ticket_gate_exit", GATE_EXIT);
		register("ticket_gate_both", GATE_BOTH);
		register("ticket_gate_transfer", GATE_TRANSFER);
		register("config_tool", CONFIG_TOOL);
		Registry.register(Registries.ITEM_GROUP, Mtr12306.id("gates"), GROUP);
	}

	private static void register(final String id, final Item item) {
		Registry.register(Registries.ITEM, Mtr12306.id(id), item);
	}
}
