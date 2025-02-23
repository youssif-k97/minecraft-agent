package com.mcap.minecraftagent.service;

import com.mcap.minecraftagent.dto.MinecraftWorld;
import com.mcap.minecraftagent.pojo.WorldConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.scheduling.annotation.Scheduled;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class MinecraftInfoService {
    private final ServerPropertiesService propertiesService;
    private final ConfigurationService configService;
    private final WorldManagementService worldManagementService;
    private final CacheManager cacheManager;

    public MinecraftInfoService(ServerPropertiesService propertiesService, ConfigurationService configService
    , WorldManagementService worldManagementService, CacheManager cacheManager) {
        this.propertiesService = propertiesService;
        this.configService = configService;
        this.worldManagementService = worldManagementService;
        this.cacheManager = cacheManager;
    }


    @Cacheable(value = "minecraftWorlds", unless = "#result.isEmpty()")
    public List<MinecraftWorld> getAllWorlds() {
        return fetchWorldsData();
    }

    private List<MinecraftWorld> fetchWorldsData() {
        log.info("Discovering servers...");
        worldManagementService.discoverRunningServers();
        log.info("Fetching all Minecraft worlds.");
        List<MinecraftWorld> worlds = configService.getAllConfigs().stream()
                .map(this::getWorldDetails)
                .collect(Collectors.toList());
        log.info("Fetched {} worlds.", worlds.size());
        return worlds;
    }

    private MinecraftWorld getWorldDetails(WorldConfig config) {
        MinecraftWorld details = new MinecraftWorld();
        details.setId(config.getWorldName());  // Using worldName as ID
        details.setName(config.getWorldName());
        details.setActive(config.isRunning());
        details.setPlayers(config.getPlayers() != null ? config.getPlayers() : new ArrayList<>());
        try {
            details.setProperties(propertiesService.getAllProperties(config.getWorldName()));
        } catch (Exception e) {
            details.setProperties(new HashMap<>());
            log.error("Failed to get properties for world: " + config.getWorldName(), e);
        }

        details.setPort(config.getPort());

        MinecraftWorld.Ram ram = new MinecraftWorld.Ram();
        ram.setMin(config.getMinMemory());
        ram.setMax(config.getMaxMemory());
        details.setRam(ram);

        return details;
    }
}
