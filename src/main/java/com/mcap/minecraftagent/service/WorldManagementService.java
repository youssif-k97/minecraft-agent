package com.mcap.minecraftagent.service;

import com.mcap.minecraftagent.dto.MinecraftWorld;
import com.mcap.minecraftagent.pojo.WorldConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
@Service
public class WorldManagementService {
    private final ConfigurationService configService;
    private final MinecraftDownloadService downloadService;
    private final String baseDir;
    private final Map<String, Process> runningServers = new ConcurrentHashMap<>();

    public WorldManagementService(
            ConfigurationService configService,
            MinecraftDownloadService downloadService) {
        this.configService = configService;
        this.downloadService = downloadService;
        this.baseDir = System.getProperty("user.home") + System.getProperty("file.separator") + "minecraft-servers" + System.getProperty("file.separator");
        new File(baseDir).mkdirs();
    }

    @PreDestroy
    public void cleanup() {
        runningServers.forEach((worldName, process) -> {
            try {
                process.destroy();
                configService.updateServerStatus(worldName, false);
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.error("Failed to stop server: " + worldName, e);
            }
        });
    }

    public void createWorld(WorldConfig config) throws IOException {
        String worldDir = baseDir + config.getWorldName();
        if (new File(worldDir).exists()) {
            throw new IllegalStateException("World already exists");
        }

        // Create world directory and download server
        new File(worldDir).mkdirs();
        downloadService.downloadServerJar(config.getServerVersion(), worldDir);

        // Save configuration
        configService.saveConfig(config);

        // Accept EULA
        Files.write(Paths.get(worldDir, "eula.txt"), "eula=true".getBytes());
    }

    public void startServer(String worldName) throws IOException {
        WorldConfig config = configService.getConfig(worldName);
        if (config == null) throw new IllegalStateException("World not found");
        if (runningServers.containsKey(worldName)) throw new IllegalStateException("Server already running");

        String worldDir = baseDir + worldName;
        ProcessBuilder pb = new ProcessBuilder(
                "java",
                "-Xmx" + config.getMaxMemory() + "M",
                "-Xms" + config.getMinMemory() + "M",
                "-jar",
                "server.jar",
                "nogui"
        );
        pb.directory(new File(worldDir));
        pb.redirectErrorStream(true);
        pb.inheritIO();
        Process process = pb.start();
        runningServers.put(worldName, process);
        configService.updateServerStatus(worldName, true);
    }

    public void stopServer(String worldName) throws IOException {
        Process process = runningServers.get(worldName);
        if (process != null) {
            process.destroy();
            runningServers.remove(worldName);
            configService.updateServerStatus(worldName, false);
        }
    }

    public void restartServer(String worldName) throws IOException, InterruptedException {
        stopServer(worldName);
        Thread.sleep(5000); // Wait for server to fully stop
        startServer(worldName);
    }

    public String createBackup(String worldName) throws IOException {
        String worldDir = baseDir + worldName;
        String backupDir = baseDir + "backups" + System.getProperty("file.separator") + worldName;
        new File(backupDir).mkdirs();

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"));
        String backupFile = backupDir + System.getProperty("file.separator") + worldName + "_" + timestamp + ".zip";

        // Create zip backup
        try (FileOutputStream fos = new FileOutputStream(backupFile);
             ZipOutputStream zos = new ZipOutputStream(fos)) {
            Files.walk(Paths.get(worldDir))
                    .filter(path -> !path.toString().contains("backups"))
                    .forEach(path -> {
                        try {
                            String zipEntry = Paths.get(worldDir).relativize(path).toString();
                            zos.putNextEntry(new ZipEntry(zipEntry));
                            if (Files.isRegularFile(path)) {
                                Files.copy(path, zos);
                            }
                            zos.closeEntry();
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        }
        configService.updateLastBackup(worldName, backupFile);
        return backupFile;
    }

    public void deleteWorld(String worldName) throws IOException {
        String worldDir = baseDir + worldName;
        if (runningServers.containsKey(worldName)) {
            stopServer(worldName);
        }
        Files.walk(Paths.get(worldDir))
                .sorted((a, b) -> b.toString().length() - a.toString().length())
                .forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
        Files.deleteIfExists(Paths.get(worldDir));
        configService.deleteConfig(worldName);
    }

    public void updateServerRam(String worldName, MinecraftWorld.Ram ram) throws IOException, InterruptedException {
        configService.updateServerRamInfo(worldName, ram.getMin(), ram.getMax());
        restartServer(worldName);
    }
}