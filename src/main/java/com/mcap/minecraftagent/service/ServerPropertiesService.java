package com.mcap.minecraftagent.service;


import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

@Service
public class ServerPropertiesService {
    private final String propertiesPath;
    private final Properties properties;

    public ServerPropertiesService(@Value("${server.properties.path}") String propertiesPath) {
        this.propertiesPath = propertiesPath;
        this.properties = new Properties();
        loadProperties();
    }

    private void loadProperties() {
        Resource resource = new FileSystemResource(propertiesPath);
        try (InputStream input = resource.getInputStream()) {
            properties.load(input);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load properties file: " + propertiesPath, e);
        }
    }

    public Map<String, String> getAllProperties() {
        Map<String, String> propsMap = new HashMap<>();
        for (String key : properties.stringPropertyNames()) {
            propsMap.put(key, properties.getProperty(key));
        }
        return propsMap;
    }

    public String getProperty(String key) {
        return properties.getProperty(key);
    }

    public void setProperty(String key, String value) {
        properties.setProperty(key, value);
        saveProperties();
    }

    private void saveProperties() {
        Resource resource = new FileSystemResource(propertiesPath);
        try (OutputStream output = new FileOutputStream(resource.getFile())) {
            properties.store(output, "Updated properties");
        } catch (IOException e) {
            throw new RuntimeException("Failed to save properties file: " + propertiesPath, e);
        }
    }

    public boolean deleteProperty(String key) {
        if (properties.containsKey(key)) {
            properties.remove(key);
            saveProperties();
            return true;
        }
        return false;
    }
}
