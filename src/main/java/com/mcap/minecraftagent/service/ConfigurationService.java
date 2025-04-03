package com.mcap.minecraftagent.service;

import com.mcap.minecraftagent.pojo.WorldConfig;
import com.mcap.minecraftagent.repository.IWorldConfigRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ConfigurationService {
    private final IWorldConfigRepository worldConfigRepository;

    @PostConstruct
    public void init() {
        log.info("ConfigurationService initialized.");
    }

    public WorldConfig getConfig(String worldName) {
        log.info("Retrieving configuration for world: {}", worldName);
        return worldConfigRepository.findByWorldName(worldName)
                .orElse(null);
    }

    public List<WorldConfig> getAllConfigs() {
        return worldConfigRepository.findAll();
    }

    public void saveConfig(WorldConfig config) {
        log.info("Saving configuration for world: {}", config.getWorldName());
        if (config.getCreatedAt() == null) {
            config.setCreatedAt(LocalDateTime.now());
        }
        worldConfigRepository.save(config);
        log.info("Configuration saved successfully for world: {}", config.getWorldName());
    }

    public void updateServerStatus(String worldName, boolean isRunning) {
        log.info("Updating server status for world: {}, isRunning: {}", worldName, isRunning);
        WorldConfig config = getConfig(worldName);
        if (config != null) {
            config.setRunning(isRunning);
            if (isRunning) {
                config.setLastStarted(LocalDateTime.now());
            }
            worldConfigRepository.save(config);
            log.info("Server status updated for world: {}, isRunning: {}", worldName, isRunning);
        } else {
            log.warn("No configuration found for world: {}", worldName);
        }
    }

    public void updateServerRamInfo(String worldName, int minRam, int maxRam) {
        log.info("Updating server RAM info for world: {}, minRam: {}, maxRam: {}", worldName, minRam, maxRam);
        WorldConfig config = getConfig(worldName);
        if (config != null) {
            config.setMinMemory(minRam);
            config.setMaxMemory(maxRam);
            worldConfigRepository.save(config);
            log.info("Server RAM info updated for world: {}, minRam: {}, maxRam: {}", worldName, minRam, maxRam);
        } else {
            log.warn("No configuration found for world: {}", worldName);
        }
    }

    public void updateServerPort(String worldName, int portNumber) {
        log.info("Updating server port for world: {}, port: {}", worldName, portNumber);
        WorldConfig config = getConfig(worldName);
        if (config != null) {
            config.setPort(portNumber);
            worldConfigRepository.save(config);
            log.info("Server port updated for world: {}, port: {}", worldName, portNumber);
        } else {
            log.warn("No configuration found for world: {}", worldName);
        }
    }

    public void updateLastBackup(String worldName, String backupPath) {
        log.info("Updating last backup for world: {}, backupPath: {}", worldName, backupPath);
        WorldConfig config = getConfig(worldName);
        if (config != null) {
            config.setLastBackup(backupPath);
            worldConfigRepository.save(config);
            log.info("Last backup updated for world: {}, backupPath: {}", worldName, backupPath);
        } else {
            log.warn("No configuration found for world: {}", worldName);
        }
    }

    public void updateServerVersion(String worldName, String serverVersion) {
        log.info("Updating server version for world: {}, serverVersion: {}", worldName, serverVersion);
        WorldConfig config = getConfig(worldName);
        if (config != null) {
            config.setServerVersion(serverVersion);
            worldConfigRepository.save(config);
            log.info("Server version updated for world: {}, serverVersion: {}", worldName, serverVersion);
        } else {
            log.warn("No configuration found for world: {}", worldName);
        }
    }

    public void deleteConfig(String worldName) {
        log.info("Deleting configuration for world: {}", worldName);
        worldConfigRepository.deleteByWorldName(worldName);
        log.info("Configuration deleted for world: {}", worldName);
    }
}
