package com.mcap.minecraftagent.service;

import com.mcap.minecraftagent.dto.MinecraftWorld;
import com.mcap.minecraftagent.dto.MinecraftWorldsResponse;
import com.mcap.minecraftagent.pojo.WorldConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
@Service
public class WorldManagementService {
    private final ConfigurationService configService;
    private final MinecraftDownloadService downloadService;
    private final ServerProcessManager processManager;
    private final String baseDir;
    private final int maxRam;
    private final ServerPropertiesService serverPropertiesService;

    public WorldManagementService(
            ConfigurationService configService, MinecraftDownloadService downloadService,
            ServerProcessManager processManager, ServerPropertiesService serverPropertiesService) {
        this.configService = configService;
        this.downloadService = downloadService;
        this.processManager = processManager;
        this.baseDir = System.getProperty("user.home") + System.getProperty("file.separator") + "minecraft-servers" + System.getProperty("file.separator");
        new File(baseDir).mkdirs();
        this.maxRam = 4096;
        this.serverPropertiesService = serverPropertiesService;
    }

    public MinecraftWorldsResponse getAllWorlds() {
        log.info("Fetching all Minecraft worlds.");
        List<MinecraftWorld> worlds = configService.getAllConfigs().stream()
                .map(this::mapToWorldResponse)
                .collect(Collectors.toList());
        log.info("Fetched {} worlds.", worlds.size());
        return new MinecraftWorldsResponse(worlds);
    }

    public MinecraftWorld getWorld(String worldName) {
        log.info("Fetching world details for: {}", worldName);
        WorldConfig config = configService.getConfig(worldName);
        if (config == null) {
            return null;
        }
        return mapToWorldResponse(config);
    }

    private MinecraftWorld mapToWorldResponse(WorldConfig config) {
        MinecraftWorld details = new MinecraftWorld();
        details.setId(config.getWorldName());  // Using worldName as ID
        details.setName(config.getWorldName());
        details.setActive(config.isRunning());
        details.setPort(config.getPort());

        MinecraftWorld.Ram ram = new MinecraftWorld.Ram();
        ram.setMin(config.getMinMemory());
        ram.setMax(config.getMaxMemory());

        details.setRam(ram);

        return details;
    }

    public void createWorld(WorldConfig config) throws IOException {
        String worldDir = baseDir + config.getWorldName();
        log.info("Creating new world: {} at directory: {}", config.getWorldName(), worldDir);
        if (new File(worldDir).exists()) {
            log.error("World creation failed: {} already exists", config.getWorldName());
            throw new IllegalStateException("World already exists");
        }
        if (configService.getAllConfigs().stream().anyMatch(w -> w.getPort() == config.getPort())) {
            log.error("World creation failed: Port {} is already in use", config.getPort());
            throw new IllegalStateException("Port already in use");
        }
        new File(worldDir).mkdirs();
        log.info("Downloading server jar for world: {}", config.getWorldName());
        downloadService.downloadServerJar(config.getServerVersion(), worldDir);
        log.info("Saving configuration for world: {}", config.getWorldName());
        String rconPassword = generateRconPassword();
        config.setRconPassword(rconPassword);
        configService.saveConfig(config);
        Files.write(Paths.get(worldDir, "eula.txt"), "eula=true".getBytes());
        
        StringBuilder propertiesContent = new StringBuilder();
        propertiesContent.append("server-port=").append(config.getPort()).append("\n");
        propertiesContent.append("enable-rcon=true\n");
        propertiesContent.append("rcon.port=").append(config.getPort() + 10).append("\n"); 
        propertiesContent.append("rcon.password=").append(config.getRconPassword()).append("\n");
        
        Files.write(Paths.get(worldDir, "server.properties"), propertiesContent.toString().getBytes());
        log.info("World {} created successfully", config.getWorldName());
        startServer(config.getWorldName());
        this.serverPropertiesService.evictPropertiesCache(config.getWorldName());
    }

    private String generateRconPassword() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*";
        StringBuilder password = new StringBuilder();
        Random random = new Random();
        for (int i = 0; i < 12; i++) {
            password.append(chars.charAt(random.nextInt(chars.length())));
        }
        return password.toString();
    }

    public void startServer(String worldName) throws IOException {
        log.info("Starting server for world: {}", worldName);
        WorldConfig config = configService.getConfig(worldName);
        processManager.startServer(worldName, config);
    }

    public void stopServer(String worldName) throws IOException {
        log.info("Requesting stop for server: {}", worldName);

        if (!processManager.stopServer(worldName)) {
            throw new IOException("Failed to stop server: " + worldName);
        }
    }

    public void restartServer(String worldName) throws IOException, InterruptedException {
        stopServer(worldName);
        Thread.sleep(5000); // Wait for server to fully stop
        startServer(worldName);
    }

    public String createBackup(String worldName) throws IOException {
        log.info("Creating backup for world: {}", worldName);
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
                            log.error("Backup creation failed for world {}: {}", worldName, e.getMessage(), e);
                            throw new UncheckedIOException(e);
                        }
                    });
        }
        log.info("Backup for world {} created successfully: {}", worldName, backupFile);
        configService.updateLastBackup(worldName, backupFile);
        return backupFile;
    }

    public void uploadWorldForDownload(String worldName, String uploadUrl) throws IOException {
        log.info(("Uploading world for world: {}"), worldName);
        String worldDir = baseDir + worldName;

        String tempZipFile = worldDir + ".zip";

        try (FileOutputStream fos = new FileOutputStream(tempZipFile);
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
                            log.error("Backup creation failed for world {}: {}", worldName, e.getMessage(), e);
                            throw new UncheckedIOException(e);
                        }
                    });
        }

        Path tempZipPath = Path.of(tempZipFile);
        try {
            URL url = new URL(uploadUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setDoOutput(true);
            connection.setRequestMethod("PUT");
            connection.setRequestProperty("Content-Type", "application/zip");

            // Get file size for Content-Length header
            long fileSize = Files.size(tempZipPath);
            connection.setRequestProperty("Content-Length", String.valueOf(fileSize));

            // Upload the file
            try (InputStream input = Files.newInputStream(tempZipPath);
                 OutputStream output = connection.getOutputStream()) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = input.read(buffer)) != -1) {
                    output.write(buffer, 0, bytesRead);
                }
            }

            // Check response
            int responseCode = connection.getResponseCode();
            boolean success = responseCode >= 200 && responseCode < 300;

            connection.disconnect();

        } finally {
            Files.deleteIfExists(tempZipPath);
        }
    }

    public void deleteWorld(String worldName) throws IOException {
        String worldDir = baseDir + worldName;
        if (processManager.getRunningServers().containsKey(worldName)) {
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
        if (!isRamValid(ram)) {
            log.error("Invalid RAM configuration: {}", ram);
            throw new IllegalArgumentException("Invalid RAM configuration");
        }
        configService.updateServerRamInfo(worldName, ram.getMin(), ram.getMax());
        restartServer(worldName);
    }

    private boolean isRamValid(MinecraftWorld.Ram ram) {
        return ram.getMin() >= 512 && ram.getMax() <= this.maxRam && ram.getMax() >= ram.getMin();
    }

    public void updateServerPort(String worldName, int port) throws IOException, InterruptedException {
        WorldConfig config = configService.getConfig(worldName);
        if (config == null) {
            log.error("Update port failed: World {} not found", worldName);
            throw new IllegalStateException("World not found");
        }
        if (configService.getAllConfigs().stream().anyMatch(w -> w.getPort() == port)) {
            log.error("Update port failed: Port {} is already in use", port);
            throw new IllegalStateException("Port already in use");
        }
        serverPropertiesService.setProperty(worldName, Map.of("server-port", String.valueOf(port)));
        restartServer(worldName);
    }
}