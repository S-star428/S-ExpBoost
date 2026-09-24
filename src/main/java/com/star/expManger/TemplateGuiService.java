package com.star.expManger;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class TemplateGuiService implements Listener {

    private static final String messagePrefix = "<gradient:#FFEA0D:#0BB9FF>[ S - Exp ]</gradient> ";
    private static final MiniMessage mm = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.builder()
                    .character('§')
                    .hexColors()
                    .useUnusualXRepeatedCharacterHexFormat()
                    .build();

    public enum EditMode { BOOST, LEVELUP }

    public static final String PATH_BOOST_TEMPLATE = "templates.boost";
    public static final String PATH_LEVELUP_TEMPLATE = "templates.levelup";

    public static final String GUI_TITLE_BOOST   = ChatColor.DARK_GREEN + "경험치 버프 주문서 설정";
    public static final String GUI_TITLE_LEVELUP = ChatColor.DARK_AQUA  + "레벨업 주문서 설정";

    public static final int GUI_SIZE = 27;
    public static final int CENTER_SLOT = 13;

    private final ExperienceBoostPlugin plugin;
    private final Map<UUID, EditMode> editing = new HashMap<>();

    public TemplateGuiService(ExperienceBoostPlugin plugin) {
        this.plugin = plugin;
    }

    public void openBoostTemplateGui(Player p) {
        openTemplateGui(p, EditMode.BOOST);
    }

    public void openLevelupTemplateGui(Player p) {
        openTemplateGui(p, EditMode.LEVELUP);
    }

    private void openTemplateGui(Player p, EditMode mode) {
        String title = (mode == EditMode.BOOST) ? GUI_TITLE_BOOST : GUI_TITLE_LEVELUP;
        Inventory inv = Bukkit.createInventory(p, GUI_SIZE, title);

        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta fm = filler.getItemMeta();
        if (fm != null) {
            fm.setDisplayName(" ");
            filler.setItemMeta(fm);
        }
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);

        inv.setItem(CENTER_SLOT, null);

        // load saved
        FileConfiguration cfg = plugin.getConfig();
        String path = (mode == EditMode.BOOST) ? PATH_BOOST_TEMPLATE : PATH_LEVELUP_TEMPLATE;
        ItemStack saved = cfg.getItemStack(path);
        if (saved != null && !saved.getType().isAir()) inv.setItem(CENTER_SLOT, saved.clone());

        editing.put(p.getUniqueId(), mode);
        p.openInventory(inv);

        // 안내 + 이펙트
        info(p, "<yellow>중앙 슬롯에 주문서로 사용할 아이템을 넣고 창을 닫으면 저장됩니다.</yellow>");
        info(p, "<gray>비우고 닫으면 저장된 템플릿이 삭제됩니다.</gray>");
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
        p.spawnParticle(Particle.HAPPY_VILLAGER, p.getLocation().add(0, 1.0, 0), 8, 0.25, 0.35, 0.25, 0.01);
    }

    @EventHandler
    public void onInvClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;

        EditMode mode = editing.get(p.getUniqueId());
        if (mode == null) return;

        String title = e.getView().getTitle();
        boolean isBoostGui = title.equals(GUI_TITLE_BOOST);
        boolean isLevelupGui = title.equals(GUI_TITLE_LEVELUP);
        if (!isBoostGui && !isLevelupGui) return;

        Inventory top = e.getView().getTopInventory();

        // top: only center slot
        if (e.getRawSlot() < top.getSize()) {
            if (e.getRawSlot() != CENTER_SLOT) {
                e.setCancelled(true);
                return;
            }
            return;
        }

        // SHIFT-CLICK -> center set
        if (e.isShiftClick()) {
            ItemStack clicked = e.getCurrentItem();
            if (clicked == null || clicked.getType().isAir()) return;

            e.setCancelled(true);

            ItemStack one = clicked.clone();
            one.setAmount(1);

            ItemStack centerNow = top.getItem(CENTER_SLOT);

            if (centerNow != null && !centerNow.getType().isAir()) {
                Map<Integer, ItemStack> leftover = p.getInventory().addItem(centerNow);
                if (!leftover.isEmpty()) {
                    for (ItemStack it : leftover.values()) {
                        p.getWorld().dropItemNaturally(p.getLocation(), it);
                    }
                }
            }

            top.setItem(CENTER_SLOT, one);

            if (clicked.getAmount() <= 1) e.setCurrentItem(null);
            else {
                clicked.setAmount(clicked.getAmount() - 1);
                e.setCurrentItem(clicked);
            }

            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.1f);
            p.spawnParticle(Particle.END_ROD, p.getLocation().add(0, 1.0, 0), 6, 0.2, 0.3, 0.2, 0.01);
        }
    }

    @EventHandler
    public void onInvDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;

        EditMode mode = editing.get(p.getUniqueId());
        if (mode == null) return;

        String title = e.getView().getTitle();
        if (!title.equals(GUI_TITLE_BOOST) && !title.equals(GUI_TITLE_LEVELUP)) return;

        for (int raw : e.getRawSlots()) {
            if (raw < e.getView().getTopInventory().getSize() && raw != CENTER_SLOT) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onInvClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;

        EditMode mode = editing.remove(p.getUniqueId());
        if (mode == null) return;

        String title = e.getView().getTitle();
        if (!title.equals(GUI_TITLE_BOOST) && !title.equals(GUI_TITLE_LEVELUP)) return;

        String path = (mode == EditMode.BOOST) ? PATH_BOOST_TEMPLATE : PATH_LEVELUP_TEMPLATE;
        String which = (mode == EditMode.BOOST) ? "경험치 버프" : "경험치 레벨업";

        ItemStack template = e.getInventory().getItem(CENTER_SLOT);

        // ✅ empty => delete
        if (template == null || template.getType().isAir()) {
            plugin.getConfig().set(path, null);
            plugin.saveConfig();

            info(p, "<yellow>" + which + " 주문서 템플릿이 삭제됐어요!</yellow>");
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.8f);
            p.spawnParticle(Particle.SMOKE, p.getLocation().add(0, 1.0, 0), 10, 0.25, 0.35, 0.25, 0.01);
            return;
        }

        ItemStack saveItem = template.clone();
        saveItem.setAmount(1);

        plugin.getConfig().set(path, saveItem);
        plugin.saveConfig();

        info(p, "<green>" + which + " 주문서 템플릿이 저장됐어요!</green>");
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.9f, 1.6f);
        p.spawnParticle(Particle.TOTEM_OF_UNDYING, p.getLocation().add(0, 1.0, 0), 18, 0.35, 0.45, 0.35, 0.01);
    }

    private void info(Player p, String msg) {
        p.sendMessage(LEGACY.serialize(mm.deserialize(messagePrefix + msg)));
    }
}
