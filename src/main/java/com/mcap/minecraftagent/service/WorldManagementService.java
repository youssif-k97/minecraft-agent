package com.mcap.minecraftagent.service;

import com.mcap.minecraftagent.config.MinecraftLogHandler;
import com.mcap.minecraftagent.dto.MinecraftWorld;
import com.mcap.minecraftagent.pojo.WorldConfig;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import oshi.SystemInfo;
import oshi.software.os.OSProcess;
import oshi.software.os.OperatingSystem;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
@Service
public class WorldManagementService {
    private final ConfigurationService configService;
    private final MinecraftDownloadService downloadService;
    private final ServerProcessManager processManager;
    private final MinecraftLogHandler logHandler;
    private final String baseDir;
    private Map<String, Integer> runningServers;
    private final int maxRam;
    private final ServerPropertiesService serverPropertiesService;
    private final CacheManager cacheManager;

    public WorldManagementService(
            ConfigurationService configService,
            MinecraftDownloadService downloadService,
            ServerProcessManager processManager,
            MinecraftLogHandler logHandler,
            ServerPropertiesService serverPropertiesService,
            CacheManager cacheManager) {
        this.configService = configService;
        this.downloadService = downloadService;
        this.processManager = processManager;
        this.logHandler = logHandler;
        this.cacheManager = cacheManager;
        this.baseDir = System.getProperty("user.home") + System.getProperty("file.separator") + "minecraft-servers" + System.getProperty("file.separator");
        new File(baseDir).mkdirs();
        this.maxRam = 4096;
        this.serverPropertiesService = serverPropertiesService;
    }

    @PostConstruct
    public void discoverRunningServers() {
        SystemInfo si = new SystemInfo();
        OperatingSystem os = si.getOperatingSystem();
        this.runningServers = new ConcurrentHashMap<>();
        log.info("Starting Minecraft server process discovery...");
        os.getProcesses().stream()
                .filter(this::isJavaProcess)
                .forEach(this::processMinecraftServer);
        syncServerStatus();
    }


    private void syncServerStatus() {
        List<WorldConfig> allConfigs = configService.getAllConfigs();
        allConfigs.forEach(config -> {
            config.setRunning(runningServers.containsKey(config.getWorldName()));
            try {
                configService.saveConfig(config);
            } catch (IOException e) {
                log.error("Failed to sync server status", e);
            }
        });
    }

    private boolean isJavaProcess(OSProcess process) {
        return process.getName().contains("java");
    }

    private void processMinecraftServer(OSProcess process) {
        try {
            List<String> args = process.getArguments();
            if (isMinecraftServer(args)) {
                log.info("Found potential Minecraft server process: PID {}", process.getProcessID());
                extractWorldName(args).ifPresentOrElse(worldName -> handleDiscoveredServer(process, worldName),
                        () -> log.warn("The Minecraft server with pid {} was not started by agent", process.getProcessID()));
            }
        } catch (Exception e) {
            log.error("Error processing process {}: {}", process.getProcessID(), e.getMessage());
        }
    }

    private boolean isMinecraftServer(List<String> args) {
        return args.contains("server.jar") &&
                args.contains("nogui");
    }

    private Optional<String> extractWorldName(List<String> args) {
        return args.stream()
                .filter(arg -> arg.startsWith("worldref="))
                .findFirst()
                .map(worldName -> worldName.substring("worldref=".length()));
    }

    private void handleDiscoveredServer(OSProcess process, String worldName) {
        log.info("Discovered Minecraft server: {} (PID: {})", worldName, process.getProcessID());
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
        runningServers.put(worldName, process.getProcessID());
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
        var cache = this.cacheManager.getCache("minecraftWorlds");
        if (cache != null) {
            cache.clear();
        }
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
                .redirectErrorStream(true);
        Process process = pb.start();

        CompletableFuture<Void> serverStartedFuture = new CompletableFuture<>();
        Thread logThread = new Thread(() -> {
            try {
                startLogCapture(process, worldName, serverStartedFuture);
            } catch (Exception e) {
                log.error("Error in log capture thread for world {}", worldName, e);
                serverStartedFuture.completeExceptionally(e);
            } finally {
                try {
                    config.setRunning(false);
                    configService.saveConfig(config);
                } catch (IOException ex) {
                    log.error("Error updating config after server stop for world {}", worldName, ex);
                }
            }
        }, "LogCapture-" + worldName);
        logThread.setDaemon(true);
        logThread.start();

        verifyServerStart(process, worldName, serverStartedFuture);
        processManager.registerProcess(worldName, process);
        log.info("Server process registered for world: {}", worldName);
        runningServers.put(worldName, (int) process.pid());
        configService.updateServerStatus(worldName, true);
        log.info("Server for world {} started successfully with PID {}", worldName, process.pid());
        var cache = this.cacheManager.getCache("minecraftWorlds");
        if (cache != null) {
            cache.clear();
        }
    }

    private void verifyServerStart(Process process, String worldName, CompletableFuture<Void> serverStartedFuture) throws IOException {
        long timeout = 120000; // 2 minutes timeout
        try {
            serverStartedFuture.get(timeout, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            handleFailedStart(process, worldName, "Server startup timed out");
        } catch (ExecutionException e) {
            handleFailedStart(process, worldName, "Server error: " + e.getCause().getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            handleFailedStart(process, worldName, "Startup verification interrupted");
        }
        if (!process.isAlive()) {
            handleFailedStart(process, worldName, "Server process terminated unexpectedly");
        }
    }

    private void handleFailedStart(Process process, String worldName, String errorMessage) throws IOException {
        processManager.removeProcess(worldName);
        process.destroyForcibly();
        log.error("Server startup failed for {}: {}", worldName, errorMessage);
        configService.updateServerStatus(worldName, false);
        throw new RuntimeException("Failed to start server: " + errorMessage);
    }

    public void stopServer(String worldName) throws IOException {
        log.info("Requesting stop for server: {}", worldName);

        if (!processManager.stopServer(worldName)) {
            throw new IOException("Failed to stop server: " + worldName);
        }
        runningServers.remove(worldName);
        configService.updateServerStatus(worldName, false);
        var cache = this.cacheManager.getCache("minecraftWorlds");
        if (cache != null) {
            cache.clear();
        }
    }

    public void restartServer(String worldName) throws IOException, InterruptedException {
        stopServer(worldName);
        Thread.sleep(5000); // Wait for server to fully stop
        startServer(worldName);
    }

    private void startLogCapture(Process process, String worldName, CompletableFuture<Void> serverStartedFuture) {
        new BufferedReader(new InputStreamReader(process.getInputStream())).lines()
                .forEach(line -> {
                    log.info("[{}] {}", worldName, line);
                    logHandler.broadcastLog(worldName, line);
                    if (line.contains("Done") || line.contains("For help, type \"help\"")) {
                        serverStartedFuture.complete(null);
                    }
                    if (line.contains("Stopping server") || line.contains("Server thread/ERROR")) {
                        serverStartedFuture.completeExceptionally(
                                new RuntimeException("Server error detected: " + line));
                    }
                });
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

    public boolean uploadWorldForDownload(String worldName, String uploadUrl) throws IOException {
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

        try {
            URL url = new URL(uploadUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setDoOutput(true);
            connection.setRequestMethod("PUT");
            connection.setRequestProperty("Content-Type", "application/zip");

            // Get file size for Content-Length header
            long fileSize = Files.size(Path.of(tempZipFile));
            connection.setRequestProperty("Content-Length", String.valueOf(fileSize));

            // Upload the file
            try (InputStream input = Files.newInputStream(Path.of(tempZipFile));
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
            return success;

        } finally {
            // Clean up temporary zip file
            Files.deleteIfExists(Path.of(tempZipFile));
        }
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