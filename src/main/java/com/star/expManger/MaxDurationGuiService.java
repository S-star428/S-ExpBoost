package com.star.expManger;

import org.bukkit.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MaxDurationGuiService implements Listener {

    public static final String PATH_MAX_SECONDS = "settings.max_total_duration_seconds";
    public static final String PATH_CENTER_ITEM = "settings.max_duration_center_item";

    private static final String GUI_TITLE = ChatColor.DARK_PURPLE + "최대 지속시간 설정";
    private static final int GUI_SIZE = 27;
    private static final int CENTER_SLOT = 13;



    private final ExperienceBoostPlugin plugin;

    private enum State { GUI_OPEN, WAITING_CHAT }

    private final Map<UUID, State> state = new HashMap<>();
    private final Map<UUID, Integer> tempSeconds = new HashMap<>(); // GUI에서 보여줄 임시 값(닫을 때 저장)
    private final Set<UUID> suppressNextCloseSave = new HashSet<>(); // 채팅 입력 유도 때문에 닫힐 때 저장 막기

    public MaxDurationGuiService(ExperienceBoostPlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player p) {
        int seconds = getMaxSeconds();
        tempSeconds.put(p.getUniqueId(), seconds);
        state.put(p.getUniqueId(), State.GUI_OPEN);

        Inventory inv = Bukkit.createInventory(p, GUI_SIZE, GUI_TITLE);

        // filler
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta fm = filler.getItemMeta();
        if (fm != null) {
            fm.setDisplayName(" ");
            filler.setItemMeta(fm);
        }
        for (int i = 0; i < GUI_SIZE; i++) inv.setItem(i, filler);

        inv.setItem(CENTER_SLOT, buildCenterItem(seconds));
        p.openInventory(inv);

        p.sendMessage(ChatColor.YELLOW + "중앙 아이템을 클릭하면 채팅으로 최대 지속시간을 입력할 수 있어요. 예: 1시간 30분 20초");
    }

    private ItemStack buildCenterItem(int seconds) {
        FileConfiguration cfg = plugin.getConfig();

        ItemStack base = cfg.getItemStack(PATH_CENTER_ITEM);
        ItemStack item;

        if (base != null && !base.getType().isAir()) {
            item = base.clone();
        } else {
            // 기본값
            item = new ItemStack(Material.CLOCK);
        }

        item.setAmount(1);

        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.LIGHT_PURPLE + "최대 지속시간");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "현재 설정: " + ChatColor.GOLD + formatKorean(seconds));
            lore.add(ChatColor.DARK_GRAY + "클릭해서 채팅 입력");
            lore.add(ChatColor.DARK_GRAY + "창을 닫으면 저장됨");
            meta.setLore(lore);
            item.setItemMeta(meta);
        }

        return item;
    }

    public boolean setCenterItemFromHand(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand == null || hand.getType().isAir()) return false;

        ItemStack save = hand.clone();
        save.setAmount(1);

        // ✅ 이름/로어는 어차피 GUI에서 덮어씌우므로, 저장은 “외형”만
        plugin.getConfig().set(PATH_CENTER_ITEM, save);
        plugin.saveConfig();
        return true;
    }


    private int getMaxSeconds() {
        FileConfiguration cfg = plugin.getConfig();
        int v = cfg.getInt(PATH_MAX_SECONDS, 0);
        return Math.max(v, 0);
    }

    private void saveMaxSeconds(int seconds) {
        plugin.getConfig().set(PATH_MAX_SECONDS, Math.max(seconds, 0));
        plugin.saveConfig();
    }

    @EventHandler
    public void onInvClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!e.getView().getTitle().equals(GUI_TITLE)) return;

        e.setCancelled(true); // 기본적으로 전부 막고

        if (e.getRawSlot() != CENTER_SLOT) return;

        UUID uuid = p.getUniqueId();
        state.put(uuid, State.WAITING_CHAT);

        // 우리가 채팅 입력을 받기 위해 일부러 닫는 거라, close에서 저장되면 안 됨
        suppressNextCloseSave.add(uuid);

        p.closeInventory();
        p.sendMessage(ChatColor.AQUA + "최대 지속시간을 채팅으로 입력하세요. (예: 1시간 30분 20초 / 90분 / 120초 / 1:30:20)");
        p.sendMessage(ChatColor.GRAY + "취소하려면: 취소");
    }

    @EventHandler
    public void onInvDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        if (!e.getView().getTitle().equals(GUI_TITLE)) return;
        e.setCancelled(true);
    }

    @EventHandler
    public void onInvClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        if (!e.getView().getTitle().equals(GUI_TITLE)) return;

        UUID uuid = p.getUniqueId();

        // 채팅 입력 유도 때문에 닫힌 경우: 저장 스킵
        if (suppressNextCloseSave.remove(uuid)) return;

        // 진짜로 사용자가 GUI를 닫은 경우: tempSeconds를 저장
        Integer sec = tempSeconds.get(uuid);
        if (sec == null) sec = getMaxSeconds();

        saveMaxSeconds(sec);
        state.remove(uuid);
        tempSeconds.remove(uuid);

        p.sendMessage(ChatColor.GREEN + "최대 지속시간이 저장되었습니다: " + ChatColor.GOLD + formatKorean(sec));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.4f);
        p.spawnParticle(Particle.HAPPY_VILLAGER, p.getLocation().add(0, 1.0, 0), 8, 0.25, 0.35, 0.25, 0.01);
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        UUID uuid = p.getUniqueId();

        if (state.get(uuid) != State.WAITING_CHAT) return;

        e.setCancelled(true);
        String msg = e.getMessage().trim();

        if (msg.equalsIgnoreCase("취소") || msg.equalsIgnoreCase("cancel")) {
            state.put(uuid, State.GUI_OPEN);
            Bukkit.getScheduler().runTask(plugin, () -> open(p));
            p.sendMessage(ChatColor.YELLOW + "입력이 취소되었습니다.");
            return;
        }

        Integer parsed = parseDurationToSeconds(msg);
        if (parsed == null) {
            p.sendMessage(ChatColor.RED + "[ Error ] " + ChatColor.WHITE + "형식이 올바르지 않습니다. 예: 1시간 30분 20초 / 90분 / 120초 / 1:30:20");
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 1.0f);
            return;
        }

        // 0도 허용(=제한 없음)으로 쓰고 싶으면 그대로 두면 됨
        parsed = Math.max(parsed, 0);

        tempSeconds.put(uuid, parsed);
        state.put(uuid, State.GUI_OPEN);

        // 입력 완료 후 GUI로 복귀
        final int finalParsed = parsed;
        Bukkit.getScheduler().runTask(plugin, () -> {
            Inventory inv = Bukkit.createInventory(p, GUI_SIZE, GUI_TITLE);

            ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
            ItemMeta fm = filler.getItemMeta();
            if (fm != null) {
                fm.setDisplayName(" ");
                filler.setItemMeta(fm);
            }
            for (int i = 0; i < GUI_SIZE; i++) inv.setItem(i, filler);

            inv.setItem(CENTER_SLOT, buildCenterItem(finalParsed));
            p.openInventory(inv);

            p.sendMessage(ChatColor.GREEN + "입력 완료: " + ChatColor.GOLD + formatKorean(finalParsed));
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
        });
    }

    // -------------------------
    // 시간 파서: "1시간 30분 20초", "90분", "120초", "1:30:20", "10:05" 등
    // -------------------------
    private Integer parseDurationToSeconds(String input) {
        String s = input.replaceAll("\\s+", "");

        // 1:30:20 / 10:05 같은 콜론 형식
        if (s.matches("^\\d{1,3}:\\d{1,2}:\\d{1,2}$")) {
            String[] parts = s.split(":");
            int h = Integer.parseInt(parts[0]);
            int m = Integer.parseInt(parts[1]);
            int sec = Integer.parseInt(parts[2]);
            if (m >= 60 || sec >= 60) return null;
            return h * 3600 + m * 60 + sec;
        }
        if (s.matches("^\\d{1,3}:\\d{1,2}$")) {
            String[] parts = s.split(":");
            int m = Integer.parseInt(parts[0]);
            int sec = Integer.parseInt(parts[1]);
            if (sec >= 60) return null;
            return m * 60 + sec;
        }

        // 한국어 단위
        int hours = 0, minutes = 0, seconds = 0;
        boolean found = false;

        Matcher mh = Pattern.compile("(\\d+)시간").matcher(s);
        if (mh.find()) { hours = Integer.parseInt(mh.group(1)); found = true; }

        Matcher mm = Pattern.compile("(\\d+)분").matcher(s);
        if (mm.find()) { minutes = Integer.parseInt(mm.group(1)); found = true; }

        Matcher ms = Pattern.compile("(\\d+)초").matcher(s);
        if (ms.find()) { seconds = Integer.parseInt(ms.group(1)); found = true; }

        if (found) {
            if (minutes >= 60 || seconds >= 60) {
                // "90분" 같은 건 허용해야 하니까 분/초 60 제한은 여기서 안 걸고 그대로 합산 허용해도 됨.
            }
            return hours * 3600 + minutes * 60 + seconds;
        }

        // 숫자만 들어오면 초로 취급
        if (s.matches("^\\d+$")) {
            return Integer.parseInt(s);
        }

        return null;
    }

    private String formatKorean(int totalSec) {
        if (totalSec <= 0) return "제한 없음(0초)";

        int h = totalSec / 3600;
        int rem = totalSec % 3600;
        int m = rem / 60;
        int s = rem % 60;

        StringBuilder sb = new StringBuilder();
        if (h > 0) sb.append(h).append("시간 ");
        if (m > 0) sb.append(m).append("분 ");
        if (s > 0) sb.append(s).append("초");
        return sb.toString().trim();
    }
}
