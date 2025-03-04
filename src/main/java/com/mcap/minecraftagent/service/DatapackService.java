package com.mcap.minecraftagent.service;

import com.mcap.minecraftagent.dto.Datapack;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class DatapackService {

    private static final int BUFFER_SIZE = 8192;

    private final String baseDir = System.getProperty("user.home") + System.getProperty("file.separator") + "minecraft-servers" + System.getProperty("file.separator");
    private CacheManager cacheManager;
    public DatapackService(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }
    

    public Path downloadDatapack(String worldId, String datapackName, String datapackUrl) throws IOException {
        String worldDatapacksDir = baseDir + worldId + System.getProperty("file.separator") + "datapacks";
        URL url = new URL(datapackUrl);
        Path targetPath = Paths.get(worldDatapacksDir, datapackName);

        // Create target directory if it doesn't exist
        log.info("Creating directory: {}", worldDatapacksDir);
        Files.createDirectories(Paths.get(worldDatapacksDir));

        log.info("Starting download of datapack from URL: {} to path: {}", datapackUrl, targetPath);
        try (BufferedInputStream in = new BufferedInputStream(url.openStream());
             BufferedOutputStream out = new BufferedOutputStream(Files.newOutputStream(targetPath))) {
            log.info("Download of datapack '{}' completed successfully.", datapackName);

            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        }
        evictCache(worldId);
        return targetPath;
    }
    public void removeDatapack(String worldId, String datapackName) {
        String worldDatapacksDir = baseDir + worldId + System.getProperty("file.separator") + "datapacks";
        // Loop through the datapack files in the directory and add them to the list
        File datapacksDir = new File(worldDatapacksDir);
        if (datapacksDir.exists() && datapacksDir.isDirectory()) {
            File[] files = datapacksDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isFile()) {
                        if (file.getName().equals(datapackName)) {
                            if (file.delete()) {
                                log.info("Datapack '{}' removed successfully.", datapackName);
                            } else {
                                log.warn("Failed to remove datapack '{}'.", datapackName);
                            }
                        }
                    }
                }
            }
        }
        evictCache(worldId);
    }
    @Cacheable(value = "datapacks", key = "#worldId")
    public List<Datapack> getDatapacks(String worldId) {
        String worldDatapacksDir = baseDir + worldId + System.getProperty("file.separator") + "datapacks";
        List<Datapack> datapacks = new ArrayList<>();
        // Loop through the datapack files in the directory and add them to the list
        File datapacksDir = new File(worldDatapacksDir);
        if (datapacksDir.exists() && datapacksDir.isDirectory()) {
            File[] files = datapacksDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isFile()) {
                        datapacks.add(new Datapack(file.getName(), "random date"));
                    }
                }
            }
        }
        log.info("Found {} datapacks in directory: {}", datapacks.size(), worldDatapacksDir);
        return datapacks;
    }

    public void evictCache(String worldId) {
        log.info("Evicting cache for world: {}", worldId);
        cacheManager.getCache("datapacks").evict(worldId);
    }

}
