package com.mcap.minecraftagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mcap.minecraftagent.pojo.WorldConfig;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ConfigurationService {
    private final String baseDir;
    private final ObjectMapper objectMapper;
    private final Map<String, WorldConfig> configCache = new ConcurrentHashMap<>();
    private final Path configDir;

    public ConfigurationService() {
        this.baseDir = System.getProperty("user.home") + System.getProperty("file.separator") + "minecraft-servers" + System.getProperty("file.separator");
        this.configDir = Paths.get(baseDir, "configs");
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule());
    }

    @PostConstruct
    public void init() throws IOException {
        log.info("Initializing ConfigurationService and loading configurations...");
        Files.createDirectories(configDir);
        // Load all existing configurations into cache
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(configDir, "*.json")) {
            for (Path path : stream) {
                try {
                    WorldConfig config = loadConfigFile(path);
                    configCache.put(config.getWorldName(), config);
                    log.info("Loaded configuration for world: {}", config.getWorldName());
                } catch (IOException e) {
                    log.error("Failed to load config: " + path, e);
                }
            }
        }
        log.info("ConfigurationService initialization completed.");
    }

    public WorldConfig getConfig(String worldName) {
        log.info("Retrieving configuration for world: {}", worldName);
        return configCache.get(worldName);
    }

    public List<WorldConfig> getAllConfigs() {
        return new ArrayList<>(configCache.values());
    }

    public void saveConfig(WorldConfig config) throws IOException {
        log.info("Saving configuration for world: {}", config.getWorldName());
        if (config.getCreatedAt() == null) {
            config.setCreatedAt(LocalDateTime.now());
        }
    
        Path configPath = configDir.resolve(config.getWorldName() + ".json");
        objectMapper.writeValue(configPath.toFile(), config);
        configCache.put(config.getWorldName(), config);
        log.info("Configuration saved successfully for world: {}", config.getWorldName());
    }

    public void updateServerStatus(String worldName, boolean isRunning) throws IOException {
        log.info("Updating server status for world: {}, isRunning: {}", worldName, isRunning);
        WorldConfig config = getConfig(worldName);
        if (config != null) {
            config.setRunning(isRunning);
            if (isRunning) {
                config.setLastStarted(LocalDateTime.now());
            }
            saveConfig(config);
            log.info("Server status updated for world: {}, isRunning: {}", worldName, isRunning);
        } else {
            log.warn("No configuration found for world: {}", worldName);
        }
    }

    public void updateServerRamInfo(String worldName, int minRam, int maxRam) throws IOException {
        log.info("Updating server RAM info for world: {}, minRam: {}, maxRam: {}", worldName, minRam, maxRam);
        WorldConfig config = getConfig(worldName);
        if (config != null) {
            config.setMinMemory(minRam);
            config.setMaxMemory(maxRam);
            saveConfig(config);
            log.info("Server RAM info updated for world: {}, minRam: {}, maxRam: {}", worldName, minRam, maxRam);
        } else {
            log.warn("No configuration found for world: {}", worldName);
        }
    }

    public void updateServerPort(String worldName, int portNumber) throws IOException {
        log.info("Updating server port for world: {}, port: {}", worldName, portNumber);
        WorldConfig config = getConfig(worldName);
        if (config != null) {
            config.setPort(portNumber);
            saveConfig(config);
            log.info("Server port updated for world: {}, port: {}", worldName, portNumber);
        } else {
            log.warn("No configuration found for world: {}", worldName);
        }
    }

    public void updateLastBackup(String worldName, String backupPath) throws IOException {
        log.info("Updating last backup for world: {}, backupPath: {}", worldName, backupPath);
        WorldConfig config = getConfig(worldName);
        if (config != null) {
            config.setLastBackup(backupPath);
            saveConfig(config);
            log.info("Last backup updated for world: {}, backupPath: {}", worldName, backupPath);
        } else {
            log.warn("No configuration found for world: {}", worldName);
        }
    }

    public void deleteConfig(String worldName) throws IOException {
        log.info("Deleting configuration for world: {}", worldName);
        Path configPath = configDir.resolve(worldName + ".json");
        Files.deleteIfExists(configPath);
        configCache.remove(worldName);
        log.info("Configuration deleted for world: {}", worldName);
    }

    private WorldConfig loadConfigFile(Path path) throws IOException {
        return objectMapper.readValue(path.toFile(), WorldConfig.class);
    }
}
