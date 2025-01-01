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
        Files.createDirectories(configDir);
        // Load all existing configurations into cache
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(configDir, "*.json")) {
            for (Path path : stream) {
                try {
                    WorldConfig config = loadConfigFile(path);
                    configCache.put(config.getWorldName(), config);
                } catch (IOException e) {
                    log.error("Failed to load config: " + path, e);
                }
            }
        }
    }

    public WorldConfig getConfig(String worldName) {
        return configCache.get(worldName);
    }

    public List<WorldConfig> getAllConfigs() {
        return new ArrayList<>(configCache.values());
    }

    public void saveConfig(WorldConfig config) throws IOException {
        if (config.getCreatedAt() == null) {
            config.setCreatedAt(LocalDateTime.now());
        }

        Path configPath = configDir.resolve(config.getWorldName() + ".json");
        objectMapper.writeValue(configPath.toFile(), config);
        configCache.put(config.getWorldName(), config);
    }

    public void updateServerStatus(String worldName, boolean isRunning) throws IOException {
        WorldConfig config = getConfig(worldName);
        if (config != null) {
            config.setRunning(isRunning);
            if (isRunning) {
                config.setLastStarted(LocalDateTime.now());
            }
            saveConfig(config);
        }
    }

    public void updateServerRamInfo(String worldName, int minRam, int maxRam) throws IOException {
        WorldConfig config = getConfig(worldName);
        if (config != null) {
            config.setMinMemory(minRam);
            config.setMaxMemory(maxRam);
            saveConfig(config);
        }
    }

    public void updateServerPort(String worldName, int portNumber) throws IOException {
        WorldConfig config = getConfig(worldName);
        if (config != null) {
            config.setPort(portNumber);
            saveConfig(config);
        }
    }

    public void updateLastBackup(String worldName, String backupPath) throws IOException {
        WorldConfig config = getConfig(worldName);
        if (config != null) {
            config.setLastBackup(backupPath);
            saveConfig(config);
        }
    }

    public void deleteConfig(String worldName) throws IOException {
        Path configPath = configDir.resolve(worldName + ".json");
        Files.deleteIfExists(configPath);
        configCache.remove(worldName);
    }

    private WorldConfig loadConfigFile(Path path) throws IOException {
        return objectMapper.readValue(path.toFile(), WorldConfig.class);
    }
}
