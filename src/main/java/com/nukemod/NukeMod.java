package com.nukemod;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NukeMod implements ModInitializer {
	public static final String MOD_ID = "nuke";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static Block NUKE_BLOCK;
	public static Item NUKE_ITEM;

	@Override
	public void onInitialize() {
		ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, id("nuke"));
		ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, id("nuke"));

		NUKE_BLOCK = Registry.register(
				BuiltInRegistries.BLOCK,
				blockKey,
				new NukeBlock(BlockBehaviour.Properties.of()
						.strength(2.0F, 6.0F)
						.sound(SoundType.METAL)
						.lightLevel(state -> state.getValue(NukeBlock.ARMED) ? 15 : 0)
						.setId(blockKey)));

		NUKE_ITEM = Registry.register(
				BuiltInRegistries.ITEM,
				itemKey,
				new BlockItem(NUKE_BLOCK, new Item.Properties().rarity(Rarity.EPIC).setId(itemKey)));

		// Show up in the Redstone Blocks creative tab (next to TNT).
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.REDSTONE_BLOCKS)
				.register(output -> output.accept(NUKE_ITEM));

		// Drives running detonations (crater, shockwave, mushroom cloud) once per server tick.
		ServerTickEvents.END_SERVER_TICK.register(NukeBlast::tickAll);

		LOGGER.info("Nuke mod loaded. Handle with care.");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
