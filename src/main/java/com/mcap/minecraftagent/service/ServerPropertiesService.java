package com.mcap.minecraftagent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

@Slf4j
@Service
public class ServerPropertiesService {
    private final ConfigurationService configService;
    private final CacheManager cacheManager;
    private final String baseDir;

    public ServerPropertiesService(ConfigurationService configService, CacheManager cacheManager) {
        this.configService = configService;
        this.cacheManager = cacheManager;
        this.baseDir = System.getProperty("user.home") + System.getProperty("file.separator") + "minecraft-servers" + System.getProperty("file.separator");
    }
    private Properties loadProperties(String worldId) {
        String propertiesPath = baseDir + worldId + System.getProperty("file.separator") + "server.properties";
        log.info("Attempting to load properties file: {}", propertiesPath);
        Properties properties = new Properties();
        Resource resource = new FileSystemResource(propertiesPath);
        try (InputStream input = resource.getInputStream()) {
            properties.load(input);
            log.info("Successfully loaded properties file: {}", propertiesPath);
        } catch (IOException e) {
            log.error("Failed to load properties file: {}", propertiesPath, e);
            throw new RuntimeException("Failed to load properties file: " + propertiesPath, e);
        }
        return properties;
    }

    public Map<String, String> getAllProperties(String worldId) {
        log.info("Retrieving all properties for worldId: {}", worldId);
        Properties properties = loadProperties(worldId);
        Map<String, String> propsMap = new HashMap<>();
        for (String key : properties.stringPropertyNames()) {
            propsMap.put(key, properties.getProperty(key));
        }
        log.info("Successfully retrieved {} properties for worldId: {}", propsMap.size(), worldId);
        return propsMap;
    }

    public void setProperty(String worldId, Map<String, String> properties) {
        log.info("Updating properties for worldId: {}", worldId);
        String propertiesPath = baseDir + worldId + System.getProperty("file.separator") + "server.properties";
        Properties propFile = loadProperties(worldId);
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            log.info("Setting property: {} = {}", entry.getKey(), entry.getValue());
            if (entry.getKey().equals("server-port")) {
                log.info("Updating server port for worldId: {}", worldId);
                this.configService.updateServerPort(worldId, Integer.parseInt(entry.getValue()));
            }
            propFile.setProperty(entry.getKey(), entry.getValue());
        }
        log.info("Finished updating properties for worldId: {}", worldId);
        saveProperties(propertiesPath, propFile);
        var cache = this.cacheManager.getCache("minecraftWorlds");
        if (cache != null) {
            cache.clear();
        }
    }

    private void saveProperties(String propertiesPath, Properties properties) {
        log.info("Attempting to save properties to file: {}", propertiesPath);
        Resource resource = new FileSystemResource(propertiesPath);
        try (OutputStream output = new FileOutputStream(resource.getFile())) {
            properties.store(output, "Updated properties");
            log.info("Successfully saved properties to file: {}", propertiesPath);
        } catch (IOException e) {
            log.error("Failed to save properties file: {}", propertiesPath, e);
            throw new RuntimeException("Failed to save properties file: " + propertiesPath, e);
        }
    }
}
