package com.mcap.minecraftagent.service;

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

@Service
public class ServerPropertiesService {
    private Properties loadProperties(String worldId) {
        String propertiesPath = "/opt/mscs/worlds/" + worldId + "/server.properties";
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
        String propertiesPath = "/opt/mscs/worlds/" + worldId + "/server.properties";
        Properties propFile = loadProperties(worldId);
        for (Map.Entry<String, String> entry : properties.entrySet()) {
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
