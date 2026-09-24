package com.star.expManger;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.stream.Collectors;

public class ScrollService implements Listener, CommandExecutor, TabCompleter {

    private final ExperienceBoostPlugin plugin;
    private final TemplateGuiService gui;
    private final MaxDurationGuiService maxGui;

    // Permissions (plugin.yml에도 동일하게 등록 권장)
    private static final String PERM_ADMIN    = "sexp.admin";
    private static final String PERM_TEMPLATE = "sexp.template"; // /경험치관리 설정 ...
    private static final String PERM_GIVE     = "sexp.give";     // /경험치관리 버프, 레벨업
    private static final String PERM_CHECK    = "sexp.check";    // /경험치관리 버프확인
    private static final String PERM_STOP     = "sexp.stop";     // /경험치관리 버프중단
    private static final String PERM_RELOAD   = "sexp.reload";   // /경험치관리 리로드

    // MiniMessage
    private static final MiniMessage mm = MiniMessage.miniMessage();
    private static final String messagePrefix = "<gradient:#FFEA0D:#0BB9FF>[ S - Exp ]</gradient> ";

    // PDC Keys
    private final NamespacedKey KEY_TYPE;
    private final NamespacedKey KEY_BOOST;
    private final NamespacedKey KEY_DURATION;
    private final NamespacedKey KEY_LEVELS;

    // boost 상태
    private final Map<UUID, Integer> activeBoosts = new HashMap<>();
    private final Map<UUID, Integer> remainingTimes = new HashMap<>();
    private final Map<UUID, Integer> totalDurations = new HashMap<>();
    private final Map<UUID, BukkitRunnable> activeBoostTasks = new HashMap<>();

    public ScrollService(ExperienceBoostPlugin plugin, TemplateGuiService gui, MaxDurationGuiService maxGui) {
        this.plugin = plugin;
        this.gui = gui;
        this.maxGui = maxGui;

        KEY_TYPE     = new NamespacedKey(plugin, "sexp_type");
        KEY_BOOST    = new NamespacedKey(plugin, "sexp_boost");
        KEY_DURATION = new NamespacedKey(plugin, "sexp_duration");
        KEY_LEVELS   = new NamespacedKey(plugin, "sexp_levels");
    }

    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.builder()
                    .character('§')
                    .hexColors()
                    .useUnusualXRepeatedCharacterHexFormat() // §x§R§R§G§G§B§B 형식
                    .build();

    // =========================
    // MiniMessage 출력 유틸
    // =========================
    private void info(CommandSender sender, String msg) {
        sender.sendMessage(LEGACY.serialize(mm.deserialize(messagePrefix + msg)));
    }

    private void success(CommandSender sender, String msg) {
        sender.sendMessage(LEGACY.serialize(mm.deserialize(messagePrefix + "<green>" + msg + "</green>")));
    }

    private void warn(CommandSender sender, String msg) {
        sender.sendMessage(LEGACY.serialize(mm.deserialize(messagePrefix + "<yellow>" + msg + "</yellow>")));
    }

    private void error(CommandSender sender, String msg) {
        sender.sendMessage(LEGACY.serialize(mm.deserialize(messagePrefix + "<red>[ Error ]</red> <white>" + msg + "</white>")));
    }

    private void usage(CommandSender sender, String msg) {
        // msg 안에 < > 쓰면 MiniMessage 태그로 먹히니까, 사용법은 [] 같은 걸로 써줘야 안전함
        error(sender, msg);
    }

    // =========================
    // /경험치관리 커맨드
    // =========================
    private boolean hasPerm(CommandSender sender, String perm) {
        if (sender.isOp() || sender.hasPermission(PERM_ADMIN)) return true;
        if (sender.hasPermission(perm)) return true;

        error(sender, "권한이 없습니다. (" + perm + ")");
        return false;
    }

    private Player resolveTargetSelfOrName(CommandSender sender, String[] args, int idx) {
        if (args.length > idx) {
            Player p = Bukkit.getPlayerExact(args[idx]);
            if (p == null) error(sender, "플레이어를 찾을 수 없습니다: " + args[idx]);
            return p;
        }
        if (sender instanceof Player p) return p;

        error(sender, "콘솔에서 사용할 경우 플레이어 이름을 지정해야 합니다.");
        return null;
    }

    private Player resolveTarget(CommandSender sender, String[] args, int playerArgIndex) {
        if (args.length > playerArgIndex) {
            Player p = Bukkit.getPlayerExact(args[playerArgIndex]);
            if (p == null) error(sender, "플레이어를 찾을 수 없습니다: " + args[playerArgIndex]);
            return p;
        }
        if (sender instanceof Player p) return p;

        error(sender, "콘솔에서 사용할 경우 플레이어 이름을 지정해야 합니다.");
        return null;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        // ✅ /경험치관리 -> 도움말 출력 (명령어 "도움말"은 없음)
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        // 리로드
        if (sub.equals("리로드") || sub.equals("reload")) {
            if (!hasPerm(sender, PERM_RELOAD)) return true;
            plugin.reloadConfig();
            success(sender, "config.yml 리로드 완료!");
            return true;
        }

        // 버프확인
        if (sub.equals("버프확인")) {
            if (!hasPerm(sender, PERM_CHECK)) return true;

            Player target = resolveTargetSelfOrName(sender, args, 1);
            if (target == null) return true;

            sendBoostStatus(sender, target);
            return true;
        }

        // 버프중단
        if (sub.equals("버프중단")) {
            if (!hasPerm(sender, PERM_STOP)) return true;

            Player target = resolveTargetSelfOrName(sender, args, 1);
            if (target == null) return true;

            boolean cancelled = cancelBoost(target, true);
            if (cancelled) {
                success(sender, "<white>" + target.getName() + "</white>님의 경험치 부스트를 중단했습니다.");
            } else {
                error(sender, "<white>" + target.getName() + "</white>님은 활성화된 경험치 부스트가 없습니다.");
            }
            return true;
        }

        // 설정 GUI
        if (sub.equals("설정")) {
            if (!hasPerm(sender, PERM_TEMPLATE)) return true;

            if (!(sender instanceof Player p)) {
                error(sender, "플레이어만 사용할 수 있어요.");
                return true;
            }

            if (args.length < 2) {
                error(sender, "사용법: <gray>/경험치관리 설정 경험치버프</gray> 또는 <gray>/경험치관리 설정 레벨업</gray>");
                return true;
            }

            String what = args[1].toLowerCase(Locale.ROOT);

            if (what.equals("경험치버프")) {
                gui.openBoostTemplateGui(p);
                return true;
            }

            if (what.equals("레벨업")) {
                gui.openLevelupTemplateGui(p);
                return true;
            }

            if (what.equals("최대지속시간")) {
                maxGui.open(p);
                return true;
            }

            if (what.equals("최대지속시간설정")) {
                if (args.length < 3) {
                    error(sender, "사용법: /경험치관리 설정 최대지속시간설정 중앙아이템설정");
                    return true;
                }

                String sub2 = args[2].toLowerCase(Locale.ROOT);
                if (sub2.equals("중앙아이템설정")) {
                    boolean ok = maxGui.setCenterItemFromHand(p);
                    if (!ok) {
                        error(sender, "손에 아이템을 들고 사용하세요.");
                        return true;
                    }
                    success(sender, "최대지속시간 GUI 중앙 아이템이 설정되었습니다.");
                    return true;
                }

                error(sender, "사용법: /경험치관리 설정 최대지속시간설정 중앙아이템설정");
                return true;
            }

            error(sender, "사용법: <gray>/경험치관리 설정 경험치버프</gray> 또는 <gray>/경험치관리 설정 레벨업</gray>");
            return true;
        }

        // 지급: 버프
        if (sub.equals("버프")) {
            if (!hasPerm(sender, PERM_GIVE)) return true;

            if (args.length < 3) {
                error(sender, "사용법: <gray>/경험치관리 버프 [증가율] [초] [플레이어]</gray>");
                info(sender, "<gray>예시: /경험치관리 버프 50 300</gray>");
                return true;
            }

            Integer rate = parseInt(args[1]);
            Integer duration = parseInt(args[2]);
            if (rate == null || duration == null || rate <= 0 || duration <= 0) {
                error(sender, "증가율/초는 1 이상의 숫자여야 합니다.");
                return true;
            }

            Player target = resolveTarget(sender, args, 3);
            if (target == null) return true;

            ItemStack scroll = createBoostScroll(rate, duration);
            giveOrDrop(target, scroll);

            success(sender, "<white>" + target.getName() + "</white> 님에게 버프 주문서 지급: <gold>" + rate + "%</gold> / <gold>" + duration + "초</gold>");
            return true;
        }

        // 지급: 레벨업
        if (sub.equals("레벨업")) {
            if (!hasPerm(sender, PERM_GIVE)) return true;

            if (args.length < 2) {
                error(sender, "사용법: <gray>/경험치관리 레벨업 [레벨수] [플레이어]</gray>");
                info(sender, "<gray>예시: /경험치관리 레벨업 5</gray>");
                return true;
            }

            Integer levels = parseInt(args[1]);
            if (levels == null || levels <= 0) {
                error(sender, "레벨수는 1 이상의 숫자여야 합니다.");
                return true;
            }

            Player target = resolveTarget(sender, args, 2);
            if (target == null) return true;

            ItemStack scroll = createLevelupScroll(levels);
            giveOrDrop(target, scroll);

            success(sender, "<white>" + target.getName() + "</white> 님에게 레벨업 주문서 지급: <gold>" + levels + "레벨</gold>");
            return true;
        }

        sendHelp(sender);
        return true;
    }


    private void sendHelp(CommandSender sender) {
        info(sender, "<aqua>==== S-Exp 주문서 시스템 도움말 ====</aqua>");
        info(sender, "<white>/경험치관리 설정 경험치버프</white>");
        info(sender, "<gray>중앙 슬롯에 '버프 주문서로 사용할 아이템(외형)'을 넣고 닫으면 저장됩니다.</gray>");
        info(sender, "<white>/경험치관리 설정 경험치레벨업</white>");
        info(sender, "<gray>중앙 슬롯에 '레벨업 주문서로 사용할 아이템(외형)'을 넣고 닫으면 저장됩니다.</gray>");
        info(sender, "<gray>(템플릿 아이템이 비어있으면 기본 종이 아이템으로 대체됩니다.)</gray>");

        info(sender, "<yellow>변수</yellow><gray> : boostper(증가율), second(초), level(레벨)</gray>");
        info(sender, "<gray>증가율 라인에는 반드시 %가 있어야 하고, 지속시간 라인에는 반드시 '초'가 있어야 합니다.</gray>");
        info(sender, "<gray>예시: boostper% 만큼, 지속시간: second초</gray>");

        info(sender, "<white>/경험치관리 버프 [증가율] [초] [플레이어]</white>");
        info(sender, "<gray>예) /경험치관리 버프 50 300   (5분 동안 +50%)</gray>");
        info(sender, "<white>/경험치관리 레벨업 [레벨수] [플레이어]</white>");
        info(sender, "<gray>예) /경험치관리 레벨업 5</gray>");

        info(sender, "<white>/경험치관리 버프확인 [플레이어닉네임]</white><gray> : 해당 플레이어의 경험치 부스트 상태를 확인합니다.</gray>");
        info(sender, "<white>/경험치관리 버프중단 [플레이어닉네임]</white><gray> : 해당 플레이어의 경험치 부스트를 강제로 중단합니다.</gray>");
        info(sender, "<white>/경험치관리 리로드</white><gray> : 구성 파일을 리로드합니다.</gray>");

        info(sender, "<yellow>사용 방법</yellow>");
        info(sender, "<gray>- 지급된 주문서는 우클릭으로 사용됩니다.</gray>");

        info(sender, "<aqua>==============================</aqua>");
    }

    private int getMaxRemainSeconds() {
        int v = plugin.getConfig().getInt(MaxDurationGuiService.PATH_MAX_SECONDS, 0);
        return Math.max(v, 0); // 0이면 제한 없음
    }


    // =========================
    // 주문서 사용: PDC만 보고 처리
    // =========================
    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (item == null || !item.hasItemMeta()) return;

        // 오프핸드 중복 방지
        if (event.getHand() != null && event.getHand() != EquipmentSlot.HAND) return;

        Player player = event.getPlayer();
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();

        String type = pdc.get(KEY_TYPE, PersistentDataType.STRING);
        if (type == null) return;

        // 경험치 부스트 주문서(활성화된 부스트 중복 처리 포함)
//        if (type.equals("BOOST_SCROLL")) {
//            Integer boostRate = pdc.get(KEY_BOOST, PersistentDataType.INTEGER);
//            Integer duration = pdc.get(KEY_DURATION, PersistentDataType.INTEGER);
//            if (boostRate == null || duration == null) {
//                error(player, "주문서 데이터가 손상되었습니다.");
//                return;
//            }
//
//            if (activeBoosts.containsKey(player.getUniqueId())) {
//                error(player, "이미 활성화된 경험치 부스트가 있습니다. 종료 후 사용하세요.");
//                return;
//            }
//
//            startBoost(player, boostRate, duration);
//
//            item.setAmount(item.getAmount() - 1);
//            event.setCancelled(true);
//            return;
//        }
        if (type.equals("BOOST_SCROLL")) {
            Integer newRate = pdc.get(KEY_BOOST, PersistentDataType.INTEGER);
            Integer newDuration = pdc.get(KEY_DURATION, PersistentDataType.INTEGER);
            if (newRate == null || newDuration == null) {
                // 에러 출력
                return;
            }

            UUID uuid = player.getUniqueId();
            Integer curRate = activeBoosts.get(uuid);

            // 1) 부스트 없음 -> 그냥 시작
            if (curRate == null) {
                startBoost(player, newRate, newDuration);
                item.setAmount(item.getAmount() - 1);
                event.setCancelled(true);
                return;
            }

            // 2) 같은 증가율 -> 시간 누적
            if (newRate.equals(curRate)) {
                int added = extendBoost(player, newDuration);

                if (added <= 0) {
                    // ✅ 상한 도달: 소모 X, 메시지만 경고
                    player.sendMessage(ChatColor.RED + "[ Error ] 최대 지속시간에 도달하여 더 이상 연장할 수 없습니다.");
                    player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 1.0f);
                    event.setCancelled(true);
                    return;
                }

                // ✅ 실제로 연장된 만큼만 안내하고 소모
                item.setAmount(item.getAmount() - 1);

                if (added < newDuration) {
                    player.sendMessage(ChatColor.YELLOW + "최대 지속시간 제한으로 " + added + "초만 연장되었습니다.");
                } else {
                    player.sendMessage(ChatColor.GREEN + "지속시간이 " + added + "초 연장되었습니다.");
                }

                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
                event.setCancelled(true);
                return;
            }

            int max = getMaxRemainSeconds(); // PATH_MAX_SECONDS 읽기
            int durationToUse = (max > 0) ? Math.min(newDuration, max) : newDuration;
            // 3) 더 높은 증가율 -> 새로 갱신(증가율/시간 리셋)
            if (newRate > curRate) {
                // 기존 버프 중단(이펙트는 굳이 안 울려도 됨)
                cancelBoost(player, false);

                startBoost(player, newRate, durationToUse);

                // 메시지 예시:
                // "[S-Exp] 더 높은 증가율로 갱신되었습니다. (기존 버프는 종료)"
                info(player, "<blue>기존 경험치 부스트는 종료되고, 더 높은 증가율로 갱신되었습니다.</blue>");
                item.setAmount(item.getAmount() - 1);
                event.setCancelled(true);
                return;
            }

            // 4) 더 낮은 증가율 -> 사용 불가(소모 X)
            error(player, "현재 더 높은 증가율 부스트가 활성화되어 있어 사용할 수 없습니다.");
            event.setCancelled(true);
            return;
        }


        if (type.equals("LEVELUP_SCROLL")) {
            Integer levels = pdc.get(KEY_LEVELS, PersistentDataType.INTEGER);
            if (levels == null) {
                error(player, "주문서 데이터가 손상되었습니다.");
                return;
            }

            player.giveExpLevels(levels);
            success(player, "<gold>" + levels + "</gold>레벨을 얻었습니다.");

            String s = plugin.getConfig().getString("levelup_sound", "ENTITY_PLAYER_LEVELUP");
            playSoundSafe(player, s, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);

            item.setAmount(item.getAmount() - 1);
            event.setCancelled(true);
        }
    }

    // =========================
    // 경험치 부스트 시작
    // =========================


    private void startBoost(Player player, int boostRate, int durationSec) {
        UUID uuid = player.getUniqueId();

        // 기존 task 있으면 제거(안전)
        BukkitRunnable old = activeBoostTasks.remove(uuid);
        if (old != null) old.cancel();

        activeBoosts.put(uuid, boostRate);
        remainingTimes.put(uuid, durationSec);
        totalDurations.put(uuid, durationSec);

        success(player, "경험치 부스트가 활성화되었습니다. <gold>" + durationSec + "초</gold> 동안 경험치 <gold>" + boostRate + "%</gold> 추가 획득.");

        String s = plugin.getConfig().getString("Exp_buf_sound", "ENTITY_WITCH_AMBIENT");
        playSoundSafe(player, s, Sound.ENTITY_WITCH_AMBIENT, 1.0f, 1.0f);

        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                int left = remainingTimes.getOrDefault(uuid, 0);

                if (left <= 0) {
                    activeBoosts.remove(uuid);
                    remainingTimes.remove(uuid);
                    totalDurations.remove(uuid);
                    activeBoostTasks.remove(uuid);

                    info(player, "<blue>경험치 부스트가 종료되었습니다.</blue>");
                    this.cancel();
                    return;
                }

                // 액션바(원래 방식 유지)
                int minutes = left / 60;
                int seconds = left % 60;

                player.spigot().sendMessage(
                        net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                        net.md_5.bungee.api.chat.TextComponent.fromLegacyText(
                                org.bukkit.ChatColor.GOLD + "경험치 부스트: " +
                                        minutes + "분 " + seconds + "초 남음, 증가율: " + boostRate + "%"
                        )
                );

                // 1초 감소
                remainingTimes.put(uuid, left - 1);
            }
        };

        task.runTaskTimer(plugin, 0L, 20L);
        activeBoostTasks.put(uuid, task);
    }
    // 지속시간 중첩 안됨
//    private void startBoost(Player player, int boostRate, int durationSec) {
//        UUID uuid = player.getUniqueId();
//
//        totalDurations.put(uuid, durationSec);
//        activeBoosts.put(uuid, boostRate);
//        remainingTimes.put(uuid, durationSec);
//
//        success(player, "경험치 부스트가 활성화되었습니다. <gold>" + durationSec + "초</gold> 동안 경험치 <gold>" + boostRate + "%</gold> 추가 획득.");
//
//        String s = plugin.getConfig().getString("Exp_buf_sound", "ENTITY_WITCH_AMBIENT");
//        playSoundSafe(player, s, Sound.ENTITY_WITCH_AMBIENT, 1.0f, 1.0f);
//
//        BukkitRunnable task = new BukkitRunnable() {
//            int left = durationSec;
//
//            @Override
//            public void run() {
//                if (left <= 0) {
//                    activeBoosts.remove(uuid);
//                    remainingTimes.remove(uuid);
//                    totalDurations.remove(uuid);
//                    activeBoostTasks.remove(uuid);
//
//                    info(player, "<blue>경험치 부스트가 종료되었습니다.</blue>");
//                    cancel();
//                    return;
//                }
//
//                remainingTimes.put(uuid, left);
//
//                int m = left / 60;
//                int sec = left % 60;
//
//                player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(ChatColor.GOLD + "경험치 부스트: " + m + "분 " + sec + "초 남음, 증가율: " + boostRate + "%"));
//                left--;
//            }
//        };
//
//        task.runTaskTimer(plugin, 0L, 20L);
//        activeBoostTasks.put(uuid, task);
//    }

    private int getMaxTotalSeconds() {
        return Math.max(plugin.getConfig().getInt(MaxDurationGuiService.PATH_MAX_SECONDS, 0), 0);
    }

    // =========================
    // 경험치 부스트 연산식
    // =========================
// 반환값: 실제로 늘어난 초(0이면 연장 실패 = 상한 도달)
    private int extendBoost(Player player, int requestAddSeconds) {
        UUID uuid = player.getUniqueId();

        int left = remainingTimes.getOrDefault(uuid, 0);
        int total = totalDurations.getOrDefault(uuid, 0);

        int max = Math.max(plugin.getConfig().getInt(MaxDurationGuiService.PATH_MAX_SECONDS, 0), 0);

        // 제한 없음(0) -> 그대로 누적
        if (max <= 0) {
            remainingTimes.put(uuid, left + requestAddSeconds);
            totalDurations.put(uuid, total + requestAddSeconds);
            return requestAddSeconds;
        }

        // 남은 시간 기준 상한
        int canAdd = Math.max(0, max - left);
        int realAdd = Math.min(requestAddSeconds, canAdd);

        if (realAdd <= 0) return 0;

        remainingTimes.put(uuid, left + realAdd);
        totalDurations.put(uuid, total + realAdd);
        return realAdd;
    }





    // =========================
    // 경험치 부스트 취소
    // =========================
    private boolean cancelBoost(Player target, boolean playEffect) {
        UUID uuid = target.getUniqueId();

        BukkitRunnable task = activeBoostTasks.remove(uuid);
        if (task != null) task.cancel();

        Integer removed = activeBoosts.remove(uuid);
        remainingTimes.remove(uuid);
        totalDurations.remove(uuid);

        if (removed == null) return false;

        if (playEffect) {
            warn(target, "경험치 부스트가 해제되었습니다.");
            target.playSound(target.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.8f);
            target.spawnParticle(Particle.SMOKE, target.getLocation().add(0, 1.0, 0), 10, 0.25, 0.35, 0.25, 0.01);
        }
        return true;
    }

    // =========================
    // 경험치 상태 출력
    // =========================
    private void sendBoostStatus(CommandSender sender, Player target) {
        UUID uuid = target.getUniqueId();
        Integer rate = activeBoosts.get(uuid);
        Integer left = remainingTimes.get(uuid);
        Integer total = totalDurations.get(uuid);

        if (rate == null || left == null || total == null) {
            error(sender, "<white>" + target.getName() + "</white>님은 활성화된 경험치 부스트가 없습니다.");
            return;
        }

        String Duration;
        if (total >= 60) {
            int totalminute = total / 60;
            int totalsecond = total % 60;
            Duration = totalminute + "분 " + totalsecond + "초";
        } else {
            Duration = total + "초";
        }

        String leftTime;
        if (left >= 60) {
            int leftminute = left / 60;
            int leftsecond = left % 60;
            leftTime = leftminute + "분 " + leftsecond + "초";
        } else {
            leftTime = left + "초";
        }

        info(sender, "<white>" + target.getName() + "</white> 님의 경험치 부스트 상태:");
        info(sender, "<gold> - 증가율:</gold> <white>" + rate + "%</white>");
        info(sender, "<gold> - 총 지속 시간:</gold> <white>" + Duration + "</white>");
        info(sender, "<gold> - 남은 시간:</gold> <white>" + leftTime + "</white>");
    }



    // =========================
    // 경험치 획득량 증가 반영
    // =========================
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerExpChange(PlayerExpChangeEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        Integer boostRate = activeBoosts.get(playerId);
        if (boostRate == null) return;

        int originalExp = event.getAmount();
        if (originalExp <= 0) return;

        int boostedExp = (originalExp * (100 + boostRate)) / 100;
        if (boostedExp != originalExp) {
            event.setAmount(boostedExp);
        }
    }

    // =========================
    // 주문서 생성: 템플릿 외형 복제 + PDC 박기
    // =========================
    private ItemStack createBoostScroll(int boostRate, int duration) {
        FileConfiguration cfg = plugin.getConfig();
        ItemStack base = cfg.getItemStack(TemplateGuiService.PATH_BOOST_TEMPLATE);
        ItemStack item = (base != null && !base.getType().isAir()) ? base.clone() : new ItemStack(Material.PAPER);
        item.setAmount(1);

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(KEY_TYPE, PersistentDataType.STRING, "BOOST_SCROLL");
        pdc.set(KEY_BOOST, PersistentDataType.INTEGER, boostRate);
        pdc.set(KEY_DURATION, PersistentDataType.INTEGER, duration);

        String perStr = String.valueOf(boostRate);
        String secStr = String.valueOf(duration);

        if (meta.hasDisplayName()) {
            meta.setDisplayName(replaceBoostPlaceholders(meta.getDisplayName(), perStr, secStr));
        }

        if (meta.hasLore() && meta.getLore() != null) {
            List<String> lore = new ArrayList<>(meta.getLore());
            for (int i = 0; i < lore.size(); i++) {
                lore.set(i, replaceBoostPlaceholders(lore.get(i), perStr, secStr));
            }
            meta.setLore(lore);
        }

        item.setItemMeta(meta);
        return item;
    }

    private String replaceBoostPlaceholders(String s, String per, String sec) {
        if (s == null) return null;
        return s
                .replace("boostper", per)
                .replace("second", sec)
                .replace("BOOSTPER", per)
                .replace("SECOND", sec);
    }

    private ItemStack createLevelupScroll(int levels) {
        FileConfiguration cfg = plugin.getConfig();
        ItemStack base = cfg.getItemStack(TemplateGuiService.PATH_LEVELUP_TEMPLATE);
        ItemStack item = (base != null && !base.getType().isAir()) ? base.clone() : new ItemStack(Material.PAPER);
        item.setAmount(1);

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(KEY_TYPE, PersistentDataType.STRING, "LEVELUP_SCROLL");
        pdc.set(KEY_LEVELS, PersistentDataType.INTEGER, levels);

        String lvStr = String.valueOf(levels);

        if (meta.hasDisplayName()) {
            meta.setDisplayName(replaceLevelPlaceholders(meta.getDisplayName(), lvStr));
        }

        if (meta.hasLore() && meta.getLore() != null) {
            List<String> lore = new ArrayList<>(meta.getLore());
            for (int i = 0; i < lore.size(); i++) {
                lore.set(i, replaceLevelPlaceholders(lore.get(i), lvStr));
            }
            meta.setLore(lore);
        }

        item.setItemMeta(meta);
        return item;
    }

    private String replaceLevelPlaceholders(String s, String levels) {
        if (s == null) return null;
        return s
                .replace("level", levels)
                .replace("LEVEL", levels);
    }

    // =========================
    // Tab Complete
    // =========================
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("경험치관리")) return Collections.emptyList();

        if (args.length == 1) {
            return filterPrefix(Arrays.asList("설정", "버프", "레벨업", "리로드", "버프확인", "버프중단"), args[0]);
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("설정")) {
            if (args.length == 2) {
                return filterPrefix(Arrays.asList("경험치버프", "레벨업", "최대지속시간", "최대지속시간설정"), args[1]);
            }
            if (args.length == 3 && args[1].equalsIgnoreCase("최대지속시간설정")) {
                return filterPrefix(Arrays.asList("중앙아이템설정"), args[2]);
            }
            return Collections.emptyList();
        }

        if (sub.equals("버프")) {
            if (args.length == 2) return filterPrefix(Arrays.asList("<경험치 증가율>"), args[1]); // 예시값
            if (args.length == 3) return filterPrefix(Arrays.asList("<시간>(초)"), args[2]);  // 예시값
            if (args.length == 4) return onlinePlayersPrefix(args[3]);
            return Collections.emptyList();
        }

        if (sub.equals("버프확인")) {
            if (args.length == 2) return onlinePlayersPrefix(args[1]);
            return Collections.emptyList();
        }

        if (sub.equals("버프중단")) {
            if (args.length == 2) return onlinePlayersPrefix(args[1]);
            return Collections.emptyList();
        }

        if (sub.equals("레벨업")) {
            if (args.length == 2) return filterPrefix(Arrays.asList("<레벨>"), args[1]); // 예시값
            if (args.length == 3) return onlinePlayersPrefix(args[2]);
            return Collections.emptyList();
        }

        return Collections.emptyList();
    }

    private List<String> onlinePlayersPrefix(String prefix) {
        String p = (prefix == null) ? "" : prefix.toLowerCase(Locale.ROOT);
        return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(p))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .collect(Collectors.toList());
    }

    private List<String> filterPrefix(List<String> candidates, String prefix) {
        String p = (prefix == null) ? "" : prefix.toLowerCase(Locale.ROOT);
        return candidates.stream()
                .filter(s -> s.toLowerCase(Locale.ROOT).startsWith(p))
                .collect(Collectors.toList());
    }

    // =========================
    // 유틸
    // =========================
    private Integer parseInt(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return null; }
    }

    private void giveOrDrop(Player p, ItemStack item) {
        Map<Integer, ItemStack> leftover = p.getInventory().addItem(item);
        if (!leftover.isEmpty()) {
            for (ItemStack it : leftover.values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), it);
            }
        }
    }

    private void playSoundSafe(Player player, String soundName, Sound fallback, float volume, float pitch) {
        Sound sound = fallback;
        if (soundName != null && !soundName.isBlank()) {
            try {
                sound = Sound.valueOf(soundName.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                sound = fallback;
            }
        }
        player.playSound(player.getLocation(), sound, volume, pitch);
    }
}
