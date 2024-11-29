package com.star.expManger;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.potion.PotionType;
import org.bukkit.potion.Potion;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class ExperienceBoostPlugin extends JavaPlugin implements Listener, TabExecutor, TabCompleter {

    private final ConcurrentHashMap<UUID, Integer> activeBoosts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> boostDurations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, BukkitRunnable> activeBoostTasks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> remainingTimes = new ConcurrentHashMap<>();
    String Message = ChatColor.WHITE + "[ " + ChatColor.AQUA + "S" + ChatColor.WHITE + " - " + ChatColor.YELLOW + "Exp" + ChatColor.WHITE + " ] ";

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        getCommand("경험치관리").setExecutor(this);
        getCommand("경험치관리").setTabCompleter(this);
        saveDefaultConfig();
        getLogger().info("[ S - Exp ] 플러그인이 활성화 되었습니다.");
    }

    @Override
    public void onDisable() {
        getLogger().info("[ S - Exp ] 플러그인이 비활성화 되었습니다.");
        activeBoostTasks.values().forEach(BukkitRunnable::cancel);
    }


    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("경험치관리")) {
            if (!sender.hasPermission("starexp.command")) {
                sender.sendMessage(ChatColor.RED + "[ Error ] 명령어를 사용할 권한이 없습니다.");
                return true;
            }

            if (!sender.isOp()) {
                sender.sendMessage(ChatColor.RED + "[ Error ] 오피 권한이 없습니다.");
                return true;
            }

            if (args.length == 0) {
                sendHelpMessage(sender);
                return true;
            }

            if (args[0].equalsIgnoreCase("버프")) {
                if (!sender.hasPermission("starexp.command.buf")) {
                    sender.sendMessage(ChatColor.RED + "[ Error ] 명령어를 사용할 권한이 없습니다.");
                    return true;
                }

                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "[ Error ] 경험치 증가율을 입력해주세요.");
                    return true;
                } else if (args.length < 3) {
                    sender.sendMessage(ChatColor.RED + "[ Error ] 시간을 입력해주세요.");
                    return true;
                }

                if (sender instanceof Player) {
                    Player player = (Player) sender;

                    int boostRate;
                    int duration;

                    try {
                        boostRate = Integer.parseInt(args[1]);
                        duration = Integer.parseInt(args[2]);
                    } catch (NumberFormatException e) {
                        player.sendMessage(ChatColor.RED + "[ Error ] 경험치 증가율과 시간은 숫자여야 합니다.");
                        return true;
                    }

                    ItemStack boostItem = createBoostItem(boostRate, duration);
                    player.getInventory().addItem(boostItem);
                    player.sendMessage(Message + ChatColor.GREEN + "경험치 부스트 주문서를 받았습니다.");
                } else {
                    sender.sendMessage(ChatColor.RED + "[ Error ] 이 명령어는 플레이어만 사용할 수 있습니다.");
                }
            } else if (args[0].equalsIgnoreCase("레벨업")) {
                if (!sender.hasPermission("starexp.command.levelup")) {
                    sender.sendMessage(ChatColor.RED + "[ Error ] 명령어를 사용할 권한이 없습니다.");
                    return true;
                }

                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "[ Error ] 레벨을 입력해주세요.");
                    return true;
                }

                if (sender instanceof Player) {
                    Player player = (Player) sender;
                    int levels;

                    try {
                        levels = Integer.parseInt(args[1]);
                    } catch (NumberFormatException e) {
                        player.sendMessage(ChatColor.RED + "[ Error ] 레벨은 숫자여야 합니다.");
                        return true;

                    }

                    ItemStack levelUpItem = createLevelUpItem(levels);
                    player.getInventory().addItem(levelUpItem);
                    player.sendMessage(Message + ChatColor.GREEN + "레벨업 주문서를 받았습니다.");
                } else {
                    sender.sendMessage(ChatColor.RED + "[ Error ] 이 명령어는 플레이어만 사용할 수 있습니다.");
                }
            } else if (args[0].equalsIgnoreCase("버프확인")) {
                if (!sender.hasPermission("starexp.command.checkbuff")) {
                    sender.sendMessage(ChatColor.RED + "[ Error ] 명령어를 사용할 권한이 없습니다.");
                    return true;
                }

                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "[ Error ] 플레이어를 입력해주세요.");
                    return true;
                }

                Player targetPlayer = Bukkit.getPlayer(args[1]);
                if (targetPlayer == null) {
                    sender.sendMessage(ChatColor.RED + "[ Error ] 플레이어를 찾을 수 없습니다.");
                    return true;
                }

                UUID playerId = targetPlayer.getUniqueId();
                if (activeBoosts.containsKey(playerId)) {
                    int boostRate = activeBoosts.get(playerId);
                    int totalDuration = boostDurations.get(playerId);
                    int secondsLeft = remainingTimes.get(playerId);

                    String Duration;
                    if (totalDuration >= 60) {
                        int minute = totalDuration / 60;
                        int second = totalDuration % 60;
                        Duration = minute + "분 " + second + "초";
                    } else {
                        Duration = totalDuration + "초";
                    }

                    String timeLeft;
                    if (secondsLeft >= 60) {
                        int minutes = secondsLeft / 60;
                        int seconds = secondsLeft % 60;
                        timeLeft = minutes + "분 " + seconds + "초";
                    } else {
                        timeLeft = secondsLeft + "초";
                    }

                    sender.sendMessage(Message + ChatColor.GREEN + targetPlayer.getName() + "님의 경험치 부스트 정보:");
                    sender.sendMessage(ChatColor.GOLD + " - 증가율: " + boostRate + "%");
                    sender.sendMessage(ChatColor.GOLD + " - 총 지속 시간: " + Duration);
                    sender.sendMessage(ChatColor.GOLD + " - 남은 시간: " + timeLeft);
                } else {
                    sender.sendMessage(ChatColor.RED + "[ Error ] " + targetPlayer.getName() + "님은 현재 활성화된 경험치 부스트가 없습니다.");
                }
            } else if (args[0].equalsIgnoreCase("버프중단")) {
                if (!sender.hasPermission("starexp.command.buffstop")) {
                    sender.sendMessage(ChatColor.RED + "[ Error ] 명령어를 사용할 권한이 없습니다.");
                    return true;
                }

                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "[ Error ] 플레이어를 입력해주세요.");
                    return true;
                }

                Player targetPlayer = Bukkit.getPlayer(args[1]);
                if (targetPlayer == null) {
                    sender.sendMessage(ChatColor.RED + "[ Error ] 플레이어를 찾을 수 없습니다.");
                    return true;
                }

                UUID playerId = targetPlayer.getUniqueId();
                if (activeBoosts.containsKey(playerId)) {
                    activeBoosts.remove(playerId);
                    boostDurations.remove(playerId);
                    remainingTimes.remove(playerId);
                    if (activeBoostTasks.containsKey(playerId)) {
                        activeBoostTasks.get(playerId).cancel();
                        activeBoostTasks.remove(playerId);
                    }
                    sender.sendMessage(Message + ChatColor.GREEN + targetPlayer.getName() + "님의 경험치 부스트가 강제로 중단되었습니다.");
                    targetPlayer.sendMessage(Message + ChatColor.RED + "경험치 부스트가 강제로 중단되었습니다.");
                } else {
                    sender.sendMessage(ChatColor.RED + "[ Error ] " + targetPlayer.getName() + "님은 현재 활성화된 경험치 부스트가 없습니다.");
                }
            } else if (args[0].equalsIgnoreCase("리로드")) {
                reloadConfig();
                sender.sendMessage(Message + ChatColor.GREEN + "구성 파일이 리로드되었습니다.");
            } else {
                sendHelpMessage(sender);
            }
            return true;
        }
        return false;
    }
    

    private ItemStack createBoostItem(int boostRate, int duration) {
        FileConfiguration config = getConfig();
        String itemName = ChatColor.translateAlternateColorCodes('&', config.getString("levelup_buf.name"))
                .replace("boostper", String.valueOf(boostRate))
                .replace("second", String.valueOf(duration));
        List<String> itemLoreList = config.getStringList("levelup_buf.lore");
        List<String> formattedLoreList = new ArrayList<>();
        for (String lore : itemLoreList) {
            formattedLoreList.add(ChatColor.translateAlternateColorCodes('&', lore)
                    .replace("boostper", String.valueOf(boostRate))
                    .replace("second", String.valueOf(duration)));
        }
        Material itemType = Material.valueOf(config.getString("levelup_buf.type", "PAPER"));
        int customModelData = config.getInt("levelup_buf.custom_model", -1);

        ItemStack boostItem = new ItemStack(itemType);
        ItemMeta meta = boostItem.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(itemName);
            meta.setLore(formattedLoreList);
            if (customModelData != -1) {
                meta.setCustomModelData(customModelData);
            }
            boostItem.setItemMeta(meta);
        }
        return boostItem;
    }

    private ItemStack createLevelUpItem(int levels) {
        FileConfiguration config = getConfig();
        String itemName = ChatColor.translateAlternateColorCodes('&', config.getString("levelup.name"))
                .replace("level", String.valueOf(levels));
        List<String> itemLoreList = config.getStringList("levelup.lore");
        List<String> formattedLoreList = new ArrayList<>();
        for (String lore : itemLoreList) {
            formattedLoreList.add(ChatColor.translateAlternateColorCodes('&', lore)
                    .replace("level", String.valueOf(levels)));
        }
        Material itemType = Material.valueOf(config.getString("levelup.type", "PAPER"));
        int customModelData = config.getInt("levelup.custom_model", -1);

        ItemStack levelUpItem = new ItemStack(itemType);
        ItemMeta meta = levelUpItem.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(itemName);
            meta.setLore(formattedLoreList);
            if (customModelData != -1) {
                meta.setCustomModelData(customModelData);
            }
            levelUpItem.setItemMeta(meta);
        }
        return levelUpItem;
    }

    private void sendHelpMessage(CommandSender sender) {
        sender.sendMessage(ChatColor.WHITE + "===========[ " + ChatColor.AQUA + "S" + ChatColor.WHITE + " - " + ChatColor.YELLOW + "Exp" + ChatColor.WHITE + " ]===========");
        sender.sendMessage(ChatColor.WHITE + "/경험치관리 버프 <경험치증가율> <시간(초)> : 지정된 경험치 증가율과 시간 동안 경험치를 추가로 얻는 주문서를 지급합니다.");
        sender.sendMessage(ChatColor.WHITE + "/경험치관리 레벨업 <레벨> : 지정된 레벨을 바로 증가시키는 주문서를 지급합니다.");
        sender.sendMessage(ChatColor.WHITE + "/경험치관리 버프확인 <플레이어닉네임> : 해당 플레이어의 경험치 부스트 상태를 확인합니다.");
        sender.sendMessage(ChatColor.WHITE + "/경험치관리 버프중단 <플레이어닉네임> : 해당 플레이어의 경험치 부스트를 강제로 중단합니다.");
        sender.sendMessage(ChatColor.WHITE + "/경험치관리 리로드 : 구성 파일을 리로드합니다.");
        sender.sendMessage(ChatColor.WHITE + "=============================");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("경험치관리")) {
            if (!sender.hasPermission("starexp.command") || !sender.isOp()) {
                return Collections.emptyList();
            }

            List<String> completions = new ArrayList<>();
            if (args.length == 1) {
                completions.add("버프");
                completions.add("레벨업");
                completions.add("버프확인");
                completions.add("버프중단");
                completions.add("리로드");
            } else if (args.length == 2) {
                if (args[0].equalsIgnoreCase("레벨업")) {
                    completions.add("<레벨>");
                } else if (args[0].equalsIgnoreCase("버프")) {
                    completions.add("<경험치증가율>");
                } else if (args[0].equalsIgnoreCase("버프확인") || args[0].equalsIgnoreCase("버프중단")) {
                    return Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList());
                }
            } else if (args.length == 3 && args[0].equalsIgnoreCase("버프")) {
                completions.add("<시간(초)>");
            }
            return completions;
        }
        return null;
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        Player player = event.getPlayer();
        if (item != null && item.hasItemMeta()) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null && meta.hasDisplayName() && meta.hasLore()) {
                String displayName = meta.getDisplayName();
                List<String> lore = meta.getLore();

                // 버프 주문서 처리
                Material buffItemType;
                try {
                    buffItemType = Material.valueOf(getConfig().getString("levelup_buf.type", "PAPER"));
                } catch (IllegalArgumentException e) {
                    player.sendMessage(ChatColor.RED + "[ Error ] 잘못된 버프 주문서 아이템 타입입니다.");
                    return;
                }

                if (item.getType() == buffItemType && displayName.equals(ChatColor.translateAlternateColorCodes('&', getConfig().getString("levelup_buf.name")))) {
                    if (activeBoosts.containsKey(player.getUniqueId())) {
                        player.sendMessage(ChatColor.RED + "[ Error ] 이미 활성화된 경험치 부스트가 있습니다. 다른 부스트를 사용하려면 현재 부스트가 종료되어야 합니다.");
                        return;
                    }

                    try {
                        boolean foundBoostRate = false;
                        boolean foundDuration = false;
                        int boostRate = 0;
                        int duration = 0;

                        for (String loreLine : lore) {
                            String loreString = ChatColor.stripColor(loreLine);
                            Pattern boostPattern = Pattern.compile("(\\d+)%");
                            Pattern durationPattern = Pattern.compile("(\\d+)초");
                            Matcher boostMatcher = boostPattern.matcher(loreString);
                            Matcher durationMatcher = durationPattern.matcher(loreString);
                            if (boostMatcher.find()) {
                                boostRate = Integer.parseInt(boostMatcher.group(1));
                                foundBoostRate = true;
                            }
                            if (durationMatcher.find()) {
                                duration = Integer.parseInt(durationMatcher.group(1));
                                foundDuration = true;
                            }
                        }

                        if (foundBoostRate && foundDuration) {
                            activeBoosts.put(player.getUniqueId(), boostRate);
                            boostDurations.put(player.getUniqueId(), duration);
                            remainingTimes.put(player.getUniqueId(), duration);
                            player.sendMessage(Message + ChatColor.GREEN + "경험치 부스트가 활성화되었습니다. " + duration + "초 동안 경험치 " + boostRate + "% 추가 획득.");

                            // Play boost sound
                            String boostSound = getConfig().getString("Exp_buf_sound", "ENTITY_WITCH_AMBIENT");
                            playSound(player, boostSound);

                            int finalDuration = duration;
                            int finalBoostRate = boostRate;
                            BukkitRunnable task = new BukkitRunnable() {
                                int secondsLeft = finalDuration;

                                @Override
                                public void run() {
                                    if (secondsLeft <= 0) {
                                        activeBoosts.remove(player.getUniqueId());
                                        boostDurations.remove(player.getUniqueId());
                                        remainingTimes.remove(player.getUniqueId());
                                        activeBoostTasks.remove(player.getUniqueId());
                                        player.sendMessage(Message + ChatColor.BLUE + "경험치 부스트가 종료되었습니다.");
                                        this.cancel();
                                        return;
                                    }

                                    remainingTimes.put(player.getUniqueId(), secondsLeft);
                                    int minutes = secondsLeft / 60;
                                    int seconds = secondsLeft % 60;
                                    player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(ChatColor.GOLD + "경험치 부스트: " + minutes + "분 " + seconds + "초 남음, 증가율: " + finalBoostRate + "%"));
                                    secondsLeft--;
                                }
                            };
                            task.runTaskTimer(this, 0, 20L);
                            activeBoostTasks.put(player.getUniqueId(), task);

                            item.setAmount(item.getAmount() - 1);
                        } else {
                            player.sendMessage(ChatColor.RED + "[ Error ] 주문서에 잘못된 형식의 데이터가 있습니다.");
                        }
                    } catch (NumberFormatException e) {
                        player.sendMessage(ChatColor.RED + "[ Error ] 주문서에 잘못된 형식의 데이터가 있습니다.");
                    }
                }

                // 레벨업 주문서 처리
                Material levelupItemType;
                try {
                    levelupItemType = Material.valueOf(getConfig().getString("levelup.type", "PAPER"));
                } catch (IllegalArgumentException e) {
                    player.sendMessage(ChatColor.RED + "[ Error ] 잘못된 레벨업 주문서 아이템 타입입니다.");
                    return;
                }

                if (item.getType() == levelupItemType && displayName.equals(ChatColor.translateAlternateColorCodes('&', getConfig().getString("levelup.name")))) {
                    try {
                        boolean found = false;
                        int levels = 0;

                        for (String loreLine : lore) {
                            String loreString = ChatColor.stripColor(loreLine);
                            Pattern pattern = Pattern.compile("(\\d+)");
                            Matcher matcher = pattern.matcher(loreString);
                            if (matcher.find()) {
                                levels = Integer.parseInt(matcher.group(1));
                                found = true;
                                break;
                            }
                        }

                        if (found) {
                            player.giveExpLevels(levels);
                            player.sendMessage(Message + ChatColor.GREEN + levels + "레벨을 얻었습니다.");

                            // Play level up sound
                            String levelUpSound = getConfig().getString("levelup_sound", "ENTITY_PLAYER_LEVELUP");
                            playSound(player, levelUpSound);

                            item.setAmount(item.getAmount() - 1);
                        } else {
                            player.sendMessage(ChatColor.RED + "[ Error ] 주문서에 잘못된 형식의 데이터가 있습니다.");
                        }
                    } catch (NumberFormatException e) {
                        player.sendMessage(ChatColor.RED + "[ Error ] 주문서에 잘못된 형식의 데이터가 있습니다.");
                    }
                }
            }
        }
    }

    private void playSound(Player player, String soundName) {
        try {
            Sound sound = Sound.valueOf(soundName);
            player.playSound(player.getLocation(), sound, 1.0f, 1.0f);
        } catch (IllegalArgumentException e) {
            player.sendMessage(ChatColor.RED + "[ Error ] 잘못된 사운드 설정입니다. 기본값으로 재생됩니다.");
            player.playSound(player.getLocation(), Sound.ENTITY_WITCH_AMBIENT, 1.0f, 1.0f);
        }
    }

    @EventHandler
    public void onPlayerExpChange(PlayerExpChangeEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        if (activeBoosts.containsKey(playerId)) {
            int boostRate = activeBoosts.get(playerId);
            int originalExp = event.getAmount();
            int boostedExp = originalExp + (originalExp * boostRate / 100);
            event.setAmount(boostedExp);
        }
    }
}
