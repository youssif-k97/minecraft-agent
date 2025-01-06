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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
@Service
public class WorldManagementService {
    private final ConfigurationService configService;
    private final MinecraftDownloadService downloadService;
    private final String baseDir;
    private final Map<String, Long> runningServers = new ConcurrentHashMap<>();

    public WorldManagementService(
            ConfigurationService configService,
            MinecraftDownloadService downloadService) {
        this.configService = configService;
        this.downloadService = downloadService;
        this.baseDir = System.getProperty("user.home") + System.getProperty("file.separator") + "minecraft-servers" + System.getProperty("file.separator");
        new File(baseDir).mkdirs();
    }

    @PostConstruct
    public void discoverRunningServers() {
        log.info("Starting Minecraft server process discovery...");
        ProcessHandle.allProcesses()
                .filter(this::isJavaProcess)
                .forEach(this::processMinecraftServer);
    }

    private boolean isJavaProcess(ProcessHandle process) {
        return process.info().command().orElse("").contains("java");
    }

    private void processMinecraftServer(ProcessHandle process) {
        try {
            String[] args = process.info().arguments().orElse(new String[0]);
            if (isMinecraftServer(args)) {
                log.info("Found potential Minecraft server process: PID {}", process.pid());
                extractWorldName(args).ifPresentOrElse(worldName -> handleDiscoveredServer(process, worldName),
                        () -> log.warn("The Minecraft server with pid {} was not started by agent", process.pid()));
            }
        } catch (Exception e) {
            log.error("Error processing process {}: {}", process.pid(), e.getMessage());
        }
    }

    private boolean isMinecraftServer(String[] args) {
        return Arrays.asList(args).contains("server.jar") &&
                Arrays.asList(args).contains("nogui");
    }

    private Optional<String> extractWorldName(String[] args) {
        return Arrays.stream(args)
                .filter(arg -> arg.startsWith("worldname="))
                .findFirst()
                .map(worldName -> worldName.substring("worldname=".length()));
    }

    private void handleDiscoveredServer(ProcessHandle process, String worldName) {
        log.info("Discovered Minecraft server: {} (PID: {})", worldName, process.pid());
        WorldConfig worldConfig = configService.getConfig(worldName);
        if (worldConfig == null) {
            throw new IllegalStateException("World configuration not found: " + worldName);
        }
        worldConfig.setRunning(true);
        worldConfig.setLastStarted(LocalDateTime.now());
        try {
            configService.saveConfig(worldConfig);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        runningServers.put(worldName, process.pid());
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
        configService.saveConfig(config);
        Files.write(Paths.get(worldDir, "eula.txt"), "eula=true".getBytes());
        String portProp = "server-port=" + config.getPort();
        Files.write(Paths.get(worldDir, "server.properties"), portProp.getBytes());
        log.info("World {} created successfully", config.getWorldName());
    }

    public void startServer(String worldName) throws IOException {
        log.info("Starting server for world: {}", worldName);
        WorldConfig config = configService.getConfig(worldName);
        if (config == null) {
            log.error("Start server failed: World {} not found", worldName);
            throw new IllegalStateException("World not found");
        }
        if (runningServers.containsKey(worldName)) {
            log.warn("Start server aborted: World {} is already running", worldName);
            throw new IllegalStateException("Server already running");
        }
    
        String worldDir = baseDir + worldName;
        ProcessBuilder pb = new ProcessBuilder(
                "java",
                "-Xmx" + config.getMaxMemory() + "M",
                "-Xms" + config.getMinMemory() + "M",
                "-jar",
                "server.jar",
                "nogui",
                "worldref=" + worldName
        )
                .directory(new File(worldDir))
                .redirectErrorStream(true)
                .inheritIO();
        Process process = pb.start();
        runningServers.put(worldName, process.pid());
        configService.updateServerStatus(worldName, true);
        log.info("Server for world {} started successfully with PID {}", worldName, process.pid());
    }

    public void stopServer(String worldName) throws IOException {
        log.info("Stopping server for world: {}", worldName);
        ProcessHandle process = ProcessHandle.of(runningServers.get(worldName)).orElse(null);
        if (process != null) {
            process.destroy();
            log.info("Server for world {} stopped successfully", worldName);
            runningServers.remove(worldName);
            configService.updateServerStatus(worldName, false);
        } else {
            log.warn("Stop server failed: No running server found for world {}", worldName);
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