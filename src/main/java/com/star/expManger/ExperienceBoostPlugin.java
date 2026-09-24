package com.star.expManger;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public class ExperienceBoostPlugin extends JavaPlugin {

    private TemplateGuiService templateGuiService;
    private ScrollService scrollService;
    MaxDurationGuiService maxGui = new MaxDurationGuiService(this);

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.templateGuiService = new TemplateGuiService(this);
        this.scrollService = new ScrollService(this, templateGuiService, maxGui);
        this.maxGui = new MaxDurationGuiService(this);

        // 이벤트 등록
        getServer().getPluginManager().registerEvents(templateGuiService, this);
        getServer().getPluginManager().registerEvents(scrollService, this);
        getServer().getPluginManager().registerEvents(maxGui, this);

        // 커맨드 등록
        PluginCommand cmd = getCommand("경험치관리");
        if (cmd != null) {
            cmd.setExecutor(scrollService);
            cmd.setTabCompleter(scrollService);
        } else {
            getLogger().severe("plugin.yml에 '경험치관리' 커맨드가 등록되어 있지 않습니다.");
        }
    }

    public TemplateGuiService getTemplateGuiService() {
        return templateGuiService;
    }

    public ScrollService getScrollService() {
        return scrollService;
    }
}
