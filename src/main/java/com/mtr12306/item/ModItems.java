package com.mtr12306.item;

import com.google.gson.JsonObject;
import com.mtr12306.Mtr12306;
import com.mtr12306.block.ModBlocks;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.text.Text;

/**
 * 物品注册：五种闸机 + 配置面板 + 售票机 + 报销凭证，并放进独立的创造模式分类。
 */
public final class ModItems {
	public static final Item GATE_ENTRY = new GateItem(ModBlocks.TICKET_GATE, new Item.Settings(), "ENTRY");
	public static final Item GATE_EXIT = new GateItem(ModBlocks.TICKET_GATE, new Item.Settings(), "EXIT");
	public static final Item GATE_BOTH = new GateItem(ModBlocks.TICKET_GATE, new Item.Settings(), "BOTH");
	public static final Item GATE_TRANSFER = new GateItem(ModBlocks.TICKET_GATE, new Item.Settings(), "TRANSFER");
	public static final Item GATE_VALIDATE = new GateItem(ModBlocks.TICKET_GATE, new Item.Settings(), "VALIDATE");
	public static final Item CONFIG_TOOL = new ConfigToolItem(new Item.Settings().maxCount(1));
	public static final Item TICKET_MACHINE_ITEM = new net.minecraft.item.BlockItem(ModBlocks.TICKET_MACHINE,
			new Item.Settings());
	public static final Item STATION_BOARD_ITEM = new net.minecraft.item.BlockItem(ModBlocks.STATION_BOARD,
			new Item.Settings());
	public static final Item BROADCASTER_ITEM = new net.minecraft.item.BlockItem(ModBlocks.BROADCASTER,
			new Item.Settings());
	public static final Item RECEIPT = new ItemReceipt(new Item.Settings().maxCount(1));

	private static final ItemGroup GROUP = FabricItemGroup.builder()
			.displayName(Text.translatable("itemGroup.mtr12306.gates"))
			.icon(() -> new ItemStack(GATE_ENTRY))
			.entries((context, entries) -> {
				entries.add(GATE_ENTRY);
				entries.add(GATE_EXIT);
				entries.add(GATE_BOTH);
				entries.add(GATE_TRANSFER);
				entries.add(GATE_VALIDATE);
				entries.add(CONFIG_TOOL);
				entries.add(TICKET_MACHINE_ITEM);
				entries.add(STATION_BOARD_ITEM);
				entries.add(BROADCASTER_ITEM);
			})
			.build();

	private ModItems() {
	}

	public static void register() {
		register("ticket_gate_entry", GATE_ENTRY);
		register("ticket_gate_exit", GATE_EXIT);
		register("ticket_gate_both", GATE_BOTH);
		register("ticket_gate_transfer", GATE_TRANSFER);
		register("ticket_gate_validate", GATE_VALIDATE);
		register("config_tool", CONFIG_TOOL);
		register("ticket_machine", TICKET_MACHINE_ITEM);
		register("station_board", STATION_BOARD_ITEM);
		register("broadcaster", BROADCASTER_ITEM);
		register("receipt", RECEIPT);
		Registry.register(Registries.ITEM_GROUP, Mtr12306.id("gates"), GROUP);
	}

	private static void register(final String id, final Item item) {
		Registry.register(Registries.ITEM, Mtr12306.id(id), item);
	}

	/** 生成一张报销凭证物品（把车票信息写进 NBT） */
	public static ItemStack createReceipt(final JsonObject receipt) {
		final ItemStack stack = new ItemStack(RECEIPT);
		final NbtCompound nbt = stack.getOrCreateNbt();
		nbt.putString(ItemReceipt.NBT_KEY, receipt.toString());
		return stack;
	}
}
