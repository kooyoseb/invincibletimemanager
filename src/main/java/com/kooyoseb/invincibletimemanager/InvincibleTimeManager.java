package com.kooyoseb.invincibletimemanager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class InvincibleTimeManager implements ModInitializer {
	public static final String MOD_ID = "invincibletimemanager";
	public static final String MOD_NAME = "InvincibleTimeManager";
	public static final String VERSION = "1.0.0";

	private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String PACK_ID = "invincibletimemanager";
	private static final String DAMAGE_TAG_PATH = "data/minecraft/tags/damage_type/bypasses_cooldown.json";

	private final DamageTypeCache damageTypeCache = new DamageTypeCache();

	@Override
	public void onInitialize() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			try {
				damageTypeCache.refresh(server);
			} catch (IOException exception) {
				LOGGER.warn("Failed to refresh damage type cache", exception);
			}
		});

		CommandRegistrationCallback.EVENT.register(this::registerCommands);
	}

	private void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext registryAccess, Commands.CommandSelection environment) {
		var root = Commands.literal("InvincibleTimeManager")
				.requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
				.executes(context -> showVersion(context.getSource()))
				.then(Commands.literal("add")
						.then(Commands.argument("damage_type", IdentifierArgument.id())
								.suggests((context, builder) -> SharedSuggestionProvider.suggest(getDamageTypeSuggestions(context.getSource().getServer()), builder))
								.executes(context -> addDamageType(context.getSource(), IdentifierArgument.getId(context, "damage_type")))))
				.then(Commands.literal("delete")
						.then(Commands.argument("damage_type", IdentifierArgument.id())
								.suggests((context, builder) -> SharedSuggestionProvider.suggest(readConfiguredDamageTypes(context.getSource().getServer()), builder))
								.executes(context -> deleteDamageType(context.getSource(), IdentifierArgument.getId(context, "damage_type")))))
				.then(Commands.literal("reset")
						.executes(context -> resetPack(context.getSource())))
				.then(Commands.literal("list")
						.executes(context -> listDamageTypes(context.getSource())))
				.then(Commands.literal("List")
						.executes(context -> listDamageTypes(context.getSource())))
				.then(Commands.literal("help")
						.executes(context -> showHelp(context.getSource())))
				.then(Commands.literal("hlep")
						.executes(context -> showHelp(context.getSource())))
				.then(Commands.literal("version")
						.executes(context -> showVersion(context.getSource())))
				.then(Commands.literal("Version")
						.executes(context -> showVersion(context.getSource())))
				.then(Commands.literal("reload")
						.executes(context -> reloadDamageTypes(context.getSource())))
				.then(Commands.literal("Reload")
						.executes(context -> reloadDamageTypes(context.getSource())));

		dispatcher.register(root);
		dispatcher.register(Commands.literal("invincibletimemanager")
				.requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
				.redirect(dispatcher.getRoot().getChild("InvincibleTimeManager")));
	}

	private int addDamageType(CommandSourceStack source, Identifier rawDamageType) {
		MinecraftServer server = source.getServer();
		Identifier damageType = normalizeDamageType(rawDamageType);

		source.sendSuccess(() -> Component.literal("Making a datapack"), false);
		try {
			DatapackStore store = DatapackStore.forServer(server);
			store.ensurePack(source);

			source.sendSuccess(() -> Component.literal("Checking argument files"), false);
			boolean tagExists = Files.exists(store.tagFile());
			Set<Identifier> values = store.readDamageTypes();
			if (!tagExists) {
				source.sendSuccess(() -> Component.literal("Creating argument file"), false);
			}

			if (!damageTypeExists(server, damageType)) {
				source.sendSuccess(() -> Component.literal("Warning: damage type is not in the cached game registry. Use /InvincibleTimeManager Reload after adding new mods or datapacks."), false);
			}

			if (!values.add(damageType)) {
				source.sendSuccess(() -> Component.literal("Already added: " + damageType), false);
				return 0;
			}

			store.writeDamageTypes(values);
			reloadDatapacks(source);
			source.sendSuccess(() -> Component.literal("Added damage type: " + damageType), true);
			return values.size();
		} catch (IOException exception) {
			source.sendFailure(Component.literal("Failed to update datapack: " + exception.getMessage()));
			LOGGER.warn("Failed to add damage type {}", damageType, exception);
			return 0;
		}
	}

	private int deleteDamageType(CommandSourceStack source, Identifier rawDamageType) {
		Identifier damageType = normalizeDamageType(rawDamageType);
		try {
			DatapackStore store = DatapackStore.forServer(source.getServer());
			source.sendSuccess(() -> Component.literal("Checking argument files"), false);
			Set<Identifier> values = store.readDamageTypes();

			if (!values.remove(damageType)) {
				source.sendSuccess(() -> Component.literal("Damage type was not added: " + damageType), false);
				return 0;
			}

			store.ensurePack(source);
			store.writeDamageTypes(values);
			reloadDatapacks(source);
			source.sendSuccess(() -> Component.literal("Deleted damage type: " + damageType), true);
			return values.size();
		} catch (IOException exception) {
			source.sendFailure(Component.literal("Failed to update datapack: " + exception.getMessage()));
			LOGGER.warn("Failed to delete damage type {}", damageType, exception);
			return 0;
		}
	}

	private int resetPack(CommandSourceStack source) {
		try {
			source.sendSuccess(() -> Component.literal("Making a datapack"), false);
			DatapackStore store = DatapackStore.forServer(source.getServer());
			store.ensurePack(source);

			source.sendSuccess(() -> Component.literal("Checking argument files"), false);
			if (!Files.exists(store.tagFile())) {
				source.sendSuccess(() -> Component.literal("Creating argument file"), false);
				store.writeDamageTypes(Collections.emptySet());
			}

			reloadDatapacks(source);
			source.sendSuccess(() -> Component.literal("Datapack files are ready."), true);
			return 1;
		} catch (IOException exception) {
			source.sendFailure(Component.literal("Failed to reset datapack: " + exception.getMessage()));
			LOGGER.warn("Failed to reset datapack", exception);
			return 0;
		}
	}

	private int listDamageTypes(CommandSourceStack source) {
		try {
			List<String> values = DatapackStore.forServer(source.getServer()).readDamageTypes().stream()
					.map(Identifier::toString)
					.sorted()
					.toList();

			if (values.isEmpty()) {
				source.sendSuccess(() -> Component.literal("No damage types are currently added."), false);
			} else {
				source.sendSuccess(() -> Component.literal("Current damage types: " + String.join(", ", values)), false);
			}
			return values.size();
		} catch (IOException exception) {
			source.sendFailure(Component.literal("Failed to read datapack: " + exception.getMessage()));
			return 0;
		}
	}

	private int showHelp(CommandSourceStack source) {
		source.sendSuccess(() -> Component.literal("Invincible Time Manager commands:"), false);
		source.sendSuccess(() -> Component.literal("/InvincibleTimeManager add <damage_type>"), false);
		source.sendSuccess(() -> Component.literal("/InvincibleTimeManager delete <damage_type>"), false);
		source.sendSuccess(() -> Component.literal("/InvincibleTimeManager list"), false);
		source.sendSuccess(() -> Component.literal("/InvincibleTimeManager reset"), false);
		source.sendSuccess(() -> Component.literal("/InvincibleTimeManager Reload"), false);
		source.sendSuccess(() -> Component.literal("/InvincibleTimeManager Version"), false);
		return 1;
	}

	private int showVersion(CommandSourceStack source) {
		source.sendSuccess(() -> Component.literal(MOD_NAME + " " + VERSION), false);
		source.sendSuccess(() -> Component.literal("Minecraft 26.1.2 Fabric datapack manager for minecraft:bypasses_cooldown."), false);
		return 1;
	}

	private int reloadDamageTypes(CommandSourceStack source) {
		try {
			damageTypeCache.refresh(source.getServer());
			source.sendSuccess(() -> Component.literal("Damage type cache reloaded."), true);
			return damageTypeCache.read().size();
		} catch (IOException exception) {
			source.sendFailure(Component.literal("Failed to reload damage type cache: " + exception.getMessage()));
			LOGGER.warn("Failed to reload damage type cache", exception);
			return 0;
		}
	}

	private Collection<String> getDamageTypeSuggestions(MinecraftServer server) {
		Set<String> suggestions = new LinkedHashSet<>();
		try {
			suggestions.addAll(readCurrentDamageTypes(server));
			suggestions.addAll(damageTypeCache.read());
		} catch (IOException exception) {
			LOGGER.warn("Failed to read damage type suggestions", exception);
		}
		return suggestions;
	}

	private Collection<String> readConfiguredDamageTypes(MinecraftServer server) {
		try {
			return DatapackStore.forServer(server).readDamageTypes().stream()
					.map(Identifier::toString)
					.sorted()
					.toList();
		} catch (IOException exception) {
			LOGGER.warn("Failed to read configured damage types", exception);
			return Collections.emptyList();
		}
	}

	private List<String> readCurrentDamageTypes(MinecraftServer server) {
		Registry<DamageType> registry = server.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE);
		return registry.keySet().stream()
				.map(Identifier::toString)
				.sorted()
				.toList();
	}

	private boolean damageTypeExists(MinecraftServer server, Identifier damageType) {
		Registry<DamageType> registry = server.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE);
		return registry.containsKey(damageType);
	}

	private Identifier normalizeDamageType(Identifier input) {
		return Identifier.tryBuild(
				input.getNamespace().toLowerCase(Locale.ROOT),
				input.getPath().toLowerCase(Locale.ROOT)
		);
	}

	private void reloadDatapacks(CommandSourceStack source) {
		MinecraftServer server = source.getServer();
		Set<String> enabledPacks = new LinkedHashSet<>(server.getPackRepository().getSelectedIds());
		enabledPacks.add("file/" + PACK_ID);
		CompletableFuture<Void> future = server.reloadResources(enabledPacks);
		future.exceptionally(exception -> {
			LOGGER.warn("Failed to reload datapacks", exception);
			source.sendFailure(Component.literal("Datapack file was changed, but automatic reload failed. Run /reload manually."));
			return null;
		});
	}

	private static final class DatapackStore {
		private final Path packRoot;

		private DatapackStore(Path packRoot) {
			this.packRoot = packRoot;
		}

		static DatapackStore forServer(MinecraftServer server) {
			return new DatapackStore(server.getWorldPath(LevelResource.DATAPACK_DIR).resolve(PACK_ID));
		}

		Path tagFile() {
			return packRoot.resolve(DAMAGE_TAG_PATH);
		}

		void ensurePack(CommandSourceStack source) throws IOException {
			Files.createDirectories(tagFile().getParent());

			Path metadata = packRoot.resolve("pack.mcmeta");
			if (!Files.exists(metadata)) {
				writeJson(metadata, createPackMetadata());
				source.sendSuccess(() -> Component.literal("Creating pack metadata"), false);
			}
		}

		Set<Identifier> readDamageTypes() throws IOException {
			Path tagFile = tagFile();
			if (!Files.exists(tagFile)) {
				return new LinkedHashSet<>();
			}

			try (BufferedReader reader = Files.newBufferedReader(tagFile, StandardCharsets.UTF_8)) {
				JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
				JsonArray values = Optional.ofNullable(json.getAsJsonArray("values")).orElseGet(JsonArray::new);
				Set<Identifier> identifiers = new LinkedHashSet<>();
				for (JsonElement value : values) {
					Identifier identifier = Identifier.tryParse(value.getAsString());
					if (identifier != null) {
						identifiers.add(identifier);
					}
				}
				return identifiers;
			}
		}

		void writeDamageTypes(Collection<Identifier> damageTypes) throws IOException {
			Files.createDirectories(tagFile().getParent());
			JsonObject json = new JsonObject();
			json.addProperty("replace", false);

			JsonArray values = new JsonArray();
			List<String> sorted = new ArrayList<>(damageTypes.stream().map(Identifier::toString).toList());
			Collections.sort(sorted);
			for (String value : sorted) {
				values.add(value);
			}
			json.add("values", values);
			writeJson(tagFile(), json);
		}

		private static JsonObject createPackMetadata() {
			JsonObject root = new JsonObject();
			JsonObject pack = new JsonObject();
			pack.addProperty("pack_format", SharedConstants.getCurrentVersion().packVersion(PackType.SERVER_DATA).major());
			pack.addProperty("description", "InvincibleTimeManager generated datapack");
			root.add("pack", pack);
			return root;
		}

		private static void writeJson(Path path, JsonObject json) throws IOException {
			Files.createDirectories(path.getParent());
			try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
				GSON.toJson(json, writer);
				writer.write(System.lineSeparator());
			}
		}
	}

	private final class DamageTypeCache {
		private final Path cacheFile = FabricLoader.getInstance().getGameDir()
				.resolve("moddata")
				.resolve(MOD_ID)
				.resolve("damage_types.json");

		void refresh(MinecraftServer server) throws IOException {
			Files.createDirectories(cacheFile.getParent());

			JsonObject json = new JsonObject();
			json.addProperty("minecraft_version", server.getServerVersion());
			JsonArray values = new JsonArray();
			for (String id : readCurrentDamageTypes(server)) {
				values.add(id);
			}
			json.add("values", values);

			try (BufferedWriter writer = Files.newBufferedWriter(cacheFile, StandardCharsets.UTF_8)) {
				GSON.toJson(json, writer);
				writer.write(System.lineSeparator());
			}
		}

		List<String> read() throws IOException {
			if (!Files.exists(cacheFile)) {
				return Collections.emptyList();
			}

			try (BufferedReader reader = Files.newBufferedReader(cacheFile, StandardCharsets.UTF_8)) {
				JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
				JsonArray values = Optional.ofNullable(json.getAsJsonArray("values")).orElseGet(JsonArray::new);
				List<String> identifiers = new ArrayList<>();
				for (JsonElement value : values) {
					identifiers.add(value.getAsString());
				}
				Collections.sort(identifiers);
				return identifiers;
			}
		}
	}
}

