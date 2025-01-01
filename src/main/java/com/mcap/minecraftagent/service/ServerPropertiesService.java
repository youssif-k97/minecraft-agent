package com.mcap.minecraftagent.service;

import lombok.extern.slf4j.Slf4j;
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
    private final String baseDir;

    public ServerPropertiesService(ConfigurationService configService) {
        this.configService = configService;
        this.baseDir = System.getProperty("user.home") + System.getProperty("file.separator") + "minecraft-servers" + System.getProperty("file.separator");
    }
    private Properties loadProperties(String worldId) {
        String propertiesPath = baseDir + worldId + System.getProperty("file.separator") + "server.properties";
        Properties properties = new Properties();
        Resource resource = new FileSystemResource(propertiesPath);
        try (InputStream input = resource.getInputStream()) {
            properties.load(input);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load properties file: " + propertiesPath, e);
        }
        return properties;
    }

    public Map<String, String> getAllProperties(String worldId) {
        Properties properties = loadProperties(worldId);
        Map<String, String> propsMap = new HashMap<>();
        for (String key : properties.stringPropertyNames()) {
            propsMap.put(key, properties.getProperty(key));
        }
        return propsMap;
    }

    public void setProperty(String worldId, Map<String, String> properties) {
        String propertiesPath = baseDir + worldId + System.getProperty("file.separator") + "server.properties";
        Properties propFile = loadProperties(worldId);
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            if (entry.getKey().equals("server-port")) {
                try {
                    this.configService.updateServerPort(worldId, Integer.parseInt(entry.getValue()));
                } catch (IOException e) {
                    log.error("Failed to update server port", e);
                    throw new RuntimeException(e);
                }
            }
            propFile.setProperty(entry.getKey(), entry.getValue());
        }
        saveProperties(propertiesPath, propFile);
    }

    private void saveProperties(String propertiesPath, Properties properties) {
        Resource resource = new FileSystemResource(propertiesPath);
        try (OutputStream output = new FileOutputStream(resource.getFile())) {
            properties.store(output, "Updated properties");
        } catch (IOException e) {
            throw new RuntimeException("Failed to save properties file: " + propertiesPath, e);
        }
    }
}
