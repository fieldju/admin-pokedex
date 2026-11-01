package com.fieldju.adminpokedex;

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.pokemon.Species;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;

public class AdminPokedex implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("admin_pokedex");

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(Commands.literal("dex")
                .executes(ctx -> open(ctx, null))
                .then(Commands.argument("species", StringArgumentType.word())
                    .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                        allSpecies().stream().map(s -> s.getResourceIdentifier().getPath()), builder))
                    .executes(ctx -> open(ctx, StringArgumentType.getString(ctx, "species"))))));
    }

    private static int open(CommandContext<CommandSourceStack> ctx, String speciesName) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Species jumpTo = null;
        if (speciesName != null) {
            jumpTo = PokemonSpecies.getByName(speciesName.toLowerCase());
            if (jumpTo == null) {
                ctx.getSource().sendFailure(Component.literal("No Pokémon called \"" + speciesName + "\". Try tab-completing the name."));
                return 0;
            }
        }
        Species start = jumpTo;
        player.openMenu(new SimpleMenuProvider(
            (id, inv, p) -> new DexMenu(id, inv, (ServerPlayer) p, start),
            Component.literal("Admin Pokédex")));
        return 1;
    }

    /** Every registered species, in National Dex order. */
    public static List<Species> allSpecies() {
        return PokemonSpecies.getSpecies().stream()
            .sorted(Comparator.comparingInt(Species::getNationalPokedexNumber).thenComparing(Species::getName))
            .toList();
    }
}
