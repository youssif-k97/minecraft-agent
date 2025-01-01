package com.mcap.minecraftagent.service;

import com.mcap.minecraftagent.dto.MinecraftWorld;
import com.mcap.minecraftagent.pojo.WorldConfig;
import lombok.extern.slf4j.Slf4j;
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

    public MinecraftInfoService(ServerPropertiesService propertiesService, ConfigurationService configService) {
        this.propertiesService = propertiesService;
        this.configService = configService;
    }


    @Cacheable(value = "minecraftWorlds", unless = "#result.isEmpty()")
    public List<MinecraftWorld> getAllWorlds() {
        return configService.getAllConfigs().stream()
                .map(this::getWorldDetails)
                .collect(Collectors.toList());
    }

    public MinecraftWorld getWorldDetails(WorldConfig config) {
        MinecraftWorld details = new MinecraftWorld();
        details.setId(config.getWorldName());  // Using worldName as ID
        details.setName(config.getWorldName());
        details.setActive(config.isRunning());
        details.setPlayers(new ArrayList<>()); // TODO: Implement player list
        details.setProperties(propertiesService.getAllProperties(config.getWorldName()));
        details.setPort(config.getPort());

        MinecraftWorld.Ram ram = new MinecraftWorld.Ram();
        ram.setMin(config.getMinMemory());
        ram.setMax(config.getMaxMemory());
        details.setRam(ram);

        return details;
    }

    @Scheduled(fixedRate = 60000)
    @CacheEvict(value = {"minecraftWorlds", "playersList"}, allEntries = true)
    public void evictCache() {
    }
}
