package com.fieldju.adminpokedex;

import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.item.PokemonItem;
import com.cobblemon.mod.common.pokemon.FormData;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.Species;
import com.cobblemon.mod.common.util.PlayerExtensionsKt;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

/**
 * A 6-row chest menu that only ever lives on the server. Vanilla clients render it as a normal chest;
 * the Pokémon icons are Cobblemon's own pokemon_model items, which every Cobblemon client can draw.
 * All clicks are intercepted, so nothing can be taken out of it.
 */
public class DexMenu extends ChestMenu {
    private static final int SIZE = 54;
    private static final int PER_PAGE = 45;
    /** First National Dex number of each generation, for the generation-jump buttons. */
    private static final int[] GEN_STARTS = {1, 152, 252, 387, 494, 650, 722, 810, 906};

    // Browse view buttons
    private static final int PREV_PAGE = 45, PREV_GEN = 47, INFO = 49, NEXT_GEN = 51, NEXT_PAGE = 53;
    // Detail view slots
    private static final int PREVIEW = 4, FORMS_START = 9, FORMS_END = 26;
    private static final int LV_MIN = 27, LV_DOWN10 = 28, LV_DOWN1 = 29, LV_SHOW = 31, LV_UP1 = 33, LV_UP10 = 34, LV_MAX = 35;
    private static final int SHINY = 40, BACK = 45, GET = 49, CLOSE = 53;

    private final ServerPlayer player;
    private final SimpleContainer box;
    private final List<Species> species = AdminPokedex.allSpecies();

    private int page = 0;
    private Species selected;          // null = browsing the grid
    private List<FormData> forms = List.of();
    private int formIndex = 0;
    private int level = 50;
    private boolean shiny = false;

    public DexMenu(int id, Inventory inv, ServerPlayer player, Species start) {
        this(id, inv, player, new SimpleContainer(SIZE), start);
    }

    private DexMenu(int id, Inventory inv, ServerPlayer player, SimpleContainer box, Species start) {
        super(MenuType.GENERIC_9x6, id, inv, box, 6);
        this.player = player;
        this.box = box;
        if (start != null) {
            page = species.indexOf(start) / PER_PAGE;
            select(start);
        } else {
            drawBrowse();
        }
    }

    // ---------------------------------------------------------------- clicks

    @Override
    public void clicked(int slot, int button, ClickType type, Player p) {
        if (slot >= 0 && slot < SIZE && type == ClickType.PICKUP) {
            if (selected == null) clickBrowse(slot);
            else clickDetail(slot);
        }
        // Never call super: the menu is display-only. Resync so the client drops any predicted pickup.
        setCarried(ItemStack.EMPTY);
        sendAllDataToRemote();
    }

    @Override
    public ItemStack quickMoveStack(Player p, int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player p) {
        return true;
    }

    private void clickBrowse(int slot) {
        int pages = (species.size() + PER_PAGE - 1) / PER_PAGE;
        if (slot < PER_PAGE) {
            int i = page * PER_PAGE + slot;
            if (i < species.size()) select(species.get(i));
            return;
        }
        switch (slot) {
            case PREV_PAGE -> page = Math.max(0, page - 1);
            case NEXT_PAGE -> page = Math.min(pages - 1, page + 1);
            case PREV_GEN -> page = pageOfDex(prevGenStart());
            case NEXT_GEN -> page = pageOfDex(nextGenStart());
            default -> { return; }
        }
        drawBrowse();
    }

    private void clickDetail(int slot) {
        if (slot >= FORMS_START && slot <= FORMS_END) {
            int i = slot - FORMS_START;
            if (i < forms.size()) formIndex = i;
        } else switch (slot) {
            case LV_MIN -> level = 1;
            case LV_DOWN10 -> level = Math.max(1, level - 10);
            case LV_DOWN1 -> level = Math.max(1, level - 1);
            case LV_UP1 -> level = Math.min(100, level + 1);
            case LV_UP10 -> level = Math.min(100, level + 10);
            case LV_MAX -> level = 100;
            case SHINY -> shiny = !shiny;
            case BACK -> { selected = null; drawBrowse(); return; }
            case CLOSE -> { player.closeContainer(); return; }
            case GET, PREVIEW -> give();
            default -> { return; }
        }
        drawDetail();
    }

    // ---------------------------------------------------------------- giving

    private void give() {
        FormData form = forms.get(formIndex);
        PokemonProperties props = PokemonProperties.Companion.parse("");
        props.setSpecies(selected.getResourceIdentifier().toString());
        props.setLevel(level);
        props.setShiny(shiny);
        if (formIndex != 0) props.setAspects(new HashSet<>(form.getAspects()));
        Pokemon pokemon = props.create(player);

        PlayerPartyStore party = PlayerExtensionsKt.party(player);
        int before = party.occupied();
        if (!party.add(pokemon)) {
            player.sendSystemMessage(Component.literal("Couldn't add it — your party and PC are both full!").withStyle(ChatFormatting.RED));
            return;
        }
        String where = party.occupied() > before ? "your party" : "your PC";
        MutableComponent msg = Component.literal("Added ").withStyle(ChatFormatting.GREEN)
            .append(Component.literal(describe(form)).withStyle(ChatFormatting.GOLD))
            .append(Component.literal(" to " + where + "!").withStyle(ChatFormatting.GREEN));
        player.sendSystemMessage(msg);
        AdminPokedex.LOGGER.info("{} took {} (form={}, aspects={}) via /pokedex",
            player.getGameProfile().getName(), describe(form), pokemon.getForm().getName(), pokemon.getAspects());
    }

    private String describe(FormData form) {
        StringBuilder s = new StringBuilder("Lv." + level + " ");
        if (shiny) s.append("shiny ");
        if (formIndex != 0) s.append(form.getName()).append(" ");
        return s.append(selected.getTranslatedName().getString()).toString();
    }

    // ---------------------------------------------------------------- drawing

    private void select(Species s) {
        selected = s;
        forms = new ArrayList<>();
        forms.add(s.getStandardForm());
        for (FormData f : s.getForms()) {
            if (f != s.getStandardForm() && forms.size() < FORMS_END - FORMS_START + 1) forms.add(f);
        }
        formIndex = 0;
        drawDetail();
    }

    private void drawBrowse() {
        box.clearContent();
        int pages = (species.size() + PER_PAGE - 1) / PER_PAGE;
        for (int slot = 0; slot < PER_PAGE; slot++) {
            int i = page * PER_PAGE + slot;
            if (i >= species.size()) break;
            Species s = species.get(i);
            ItemStack icon = PokemonItem.from(s);
            name(icon, Component.literal(String.format("#%03d ", s.getNationalPokedexNumber())).withStyle(ChatFormatting.GRAY)
                .append(s.getTranslatedName().copy().withStyle(ChatFormatting.WHITE)));
            int extraForms = s.getForms().size() > 1 ? s.getForms().size() - 1 : 0;
            lore(icon, extraForms > 0 ? line(extraForms + " other form" + (extraForms == 1 ? "" : "s"), ChatFormatting.AQUA) : null,
                line("Click to choose form, level & shiny", ChatFormatting.DARK_GRAY));
            box.setItem(slot, icon);
        }
        int first = page * PER_PAGE, last = Math.min(species.size(), first + PER_PAGE) - 1;
        button(PREV_PAGE, Items.ARROW, "◀ Previous page");
        button(NEXT_PAGE, Items.ARROW, "Next page ▶");
        button(PREV_GEN, Items.SPECTRAL_ARROW, "◀◀ Previous generation");
        button(NEXT_GEN, Items.SPECTRAL_ARROW, "Next generation ▶▶");
        ItemStack info = button(INFO, Items.BOOK, "Page " + (page + 1) + " of " + pages);
        lore(info, line("Dex #" + species.get(first).getNationalPokedexNumber() + " – #" + species.get(last).getNationalPokedexNumber(), ChatFormatting.GRAY),
            line("Tip: /pokedex <name> jumps straight to one", ChatFormatting.DARK_GRAY));
        filler();
        broadcastChanges();
    }

    private void drawDetail() {
        box.clearContent();
        FormData form = forms.get(formIndex);

        ItemStack preview = icon(form, shiny);
        name(preview, Component.literal(describe(form)).withStyle(ChatFormatting.GOLD));
        lore(preview, line("Click here or the green button to get it", ChatFormatting.GREEN));
        box.setItem(PREVIEW, preview);

        for (int i = 0; i < forms.size(); i++) {
            FormData f = forms.get(i);
            ItemStack it = icon(f, shiny);
            name(it, Component.literal(i == 0 ? "Normal form" : f.getName() + " form")
                .withStyle(i == formIndex ? ChatFormatting.GREEN : ChatFormatting.WHITE));
            lore(it, line(i == formIndex ? "✔ Selected" : "Click to select", i == formIndex ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY));
            if (i == formIndex) it.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
            box.setItem(FORMS_START + i, it);
        }

        button(LV_MIN, Items.WOODEN_SWORD, "Level 1");
        button(LV_DOWN10, Items.RED_CONCRETE, "−10 levels");
        button(LV_DOWN1, Items.RED_STAINED_GLASS_PANE, "−1 level");
        ItemStack lv = button(LV_SHOW, Items.EXPERIENCE_BOTTLE, "Level " + level);
        lv.setCount(Math.max(1, Math.min(64, level)));
        button(LV_UP1, Items.LIME_STAINED_GLASS_PANE, "+1 level");
        button(LV_UP10, Items.LIME_CONCRETE, "+10 levels");
        button(LV_MAX, Items.NETHERITE_SWORD, "Level 100");

        ItemStack sh = button(SHINY, shiny ? Items.NETHER_STAR : Items.GRAY_DYE, shiny ? "✦ Shiny: ON" : "Shiny: OFF");
        lore(sh, line("Click to toggle", ChatFormatting.DARK_GRAY));
        if (shiny) sh.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);

        button(BACK, Items.ARROW, "◀ Back to Pokédex");
        ItemStack get = button(GET, Items.EMERALD_BLOCK, "Get this Pokémon!");
        lore(get, line(describe(form), ChatFormatting.GOLD), line("Goes to your party, or PC if it's full", ChatFormatting.GRAY));
        button(CLOSE, Items.BARRIER, "Close");
        filler();
        broadcastChanges();
    }

    // ---------------------------------------------------------------- helpers

    private ItemStack icon(FormData form, boolean shiny) {
        List<String> aspects = new ArrayList<>(form == selected.getStandardForm() ? List.of() : form.getAspects());
        if (shiny) aspects.add("shiny");
        return PokemonItem.from(selected, aspects.toArray(new String[0]));
    }

    private ItemStack button(int slot, Item item, String label) {
        ItemStack stack = new ItemStack(item);
        name(stack, Component.literal(label).withStyle(ChatFormatting.YELLOW));
        box.setItem(slot, stack);
        return stack;
    }

    private void filler() {
        for (int i = PER_PAGE; i < SIZE; i++) {
            if (box.getItem(i).isEmpty()) {
                ItemStack pane = new ItemStack(Items.BLACK_STAINED_GLASS_PANE);
                name(pane, Component.literal(" "));
                box.setItem(i, pane);
            }
        }
    }

    private static void name(ItemStack stack, Component name) {
        stack.set(DataComponents.CUSTOM_NAME, name.copy().withStyle(s -> s.withItalic(false)));
    }

    private static void lore(ItemStack stack, Component... lines) {
        stack.set(DataComponents.LORE, new ItemLore(Arrays.stream(lines).filter(l -> l != null).toList()));
    }

    private static Component line(String text, ChatFormatting color) {
        return Component.literal(text).withStyle(s -> s.withItalic(false).withColor(color));
    }

    private int pageOfDex(int dex) {
        for (int i = 0; i < species.size(); i++) {
            if (species.get(i).getNationalPokedexNumber() >= dex) return i / PER_PAGE;
        }
        return page;
    }

    private int currentDex() {
        return species.get(Math.min(species.size() - 1, page * PER_PAGE)).getNationalPokedexNumber();
    }

    private int prevGenStart() {
        int dex = currentDex(), target = 1;
        for (int start : GEN_STARTS) if (start < dex) target = start;
        return target;
    }

    private int nextGenStart() {
        int dex = currentDex();
        for (int start : GEN_STARTS) if (start > dex) return start;
        return GEN_STARTS[GEN_STARTS.length - 1];
    }
}
