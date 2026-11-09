package com.mtr12306.block;

import com.mtr12306.Mtr12306;
import net.fabricmc.fabric.api.object.builder.v1.block.FabricBlockSettings;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.BlockSoundGroup;

public final class ModBlocks {
	public static final Block TICKET_GATE = new TicketGateBlock(
			FabricBlockSettings.create().strength(2.0F, 6.0F).sounds(BlockSoundGroup.METAL).nonOpaque().requiresTool()
	);

	public static final BlockEntityType<TicketGateBlockEntity> TICKET_GATE_ENTITY = FabricBlockEntityTypeBuilder
			.create(TicketGateBlockEntity::new, TICKET_GATE)
			.build();

	private ModBlocks() {
	}

	public static void register() {
		Registry.register(Registries.BLOCK, Mtr12306.id("ticket_gate"), TICKET_GATE);
		Registry.register(Registries.BLOCK_ENTITY_TYPE, Mtr12306.id("ticket_gate"), TICKET_GATE_ENTITY);
	}
}
