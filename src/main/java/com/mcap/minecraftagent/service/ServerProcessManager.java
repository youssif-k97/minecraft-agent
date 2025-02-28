package com.mcap.minecraftagent.service;

import com.mcap.minecraftagent.config.MinecraftLogHandler;
import com.mcap.minecraftagent.pojo.WorldConfig;
import com.mcap.minecraftagent.util.LogTailer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import oshi.SystemInfo;
import oshi.software.os.OSProcess;
import oshi.software.os.OperatingSystem;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Component
public class ServerProcessManager {
    private final Map<String, Process> activeProcesses = new ConcurrentHashMap<>();
    @Getter
    private final Map<String, Integer> runningServers = new ConcurrentHashMap<>();
    private final Map<String, LogTailer> logTailers = new ConcurrentHashMap<>();
    private final ConfigurationService configService;
    private final MinecraftLogHandler logHandler;
    private final PlayerManagementService playerManagementService;
    private final RconClientService rconClientService;
    private final String baseDir;

    public ServerProcessManager(
            ConfigurationService configService, MinecraftLogHandler logHandler,
            PlayerManagementService playerManagementService, RconClientService rconClientService) {
        this.configService = configService;
        this.logHandler = logHandler;
        this.playerManagementService = playerManagementService;
        this.rconClientService = rconClientService;
        this.baseDir = System.getProperty("user.home") + System.getProperty("file.separator") + "minecraft-servers" + System.getProperty("file.separator");
    }

    @PostConstruct
    public void discoverRunningServers() {
        SystemInfo si = new SystemInfo();
        OperatingSystem os = si.getOperatingSystem();
        log.info("Starting Minecraft server process discovery...");
        os.getProcesses().stream()
                .filter(this::isJavaProcess)
                .forEach(this::processMinecraftServer);
        syncServerStatus();
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
        worldConfig.setLastStarted(java.time.LocalDateTime.now());
        configService.saveConfig(worldConfig);
        runningServers.put(worldName, process.getProcessID());
    }

    private void syncServerStatus() {
        List<WorldConfig> allConfigs = configService.getAllConfigs();
        allConfigs.forEach(config -> {
            config.setRunning(runningServers.containsKey(config.getWorldName()));
            configService.saveConfig(config);
        });
    }

    public void registerProcess(String worldName, Process process) {
        activeProcesses.put(worldName, process);
        runningServers.put(worldName, (int) process.pid());
    }


    public void removeProcess(String worldName) {
        activeProcesses.remove(worldName);
        runningServers.remove(worldName);
    }

    public Process startServer(String worldName, WorldConfig config) throws IOException {
        log.info("Starting server for world: {}", worldName);
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

        LogTailer logTailer = new LogTailer(
                worldName,
                baseDir,
                logHandler,
                playerManagementService,
                configService);
        logTailer.start();
        logTailers.put(worldName, logTailer);
        verifyServerStart(worldName, process, logTailer);
        registerProcess(worldName, process);
        log.info("Server process registered for world: {}", worldName);
        configService.updateServerStatus(worldName, true);
        log.info("Server for world {} started successfully with PID {}", worldName, process.pid());
        return process;
    }

    private void verifyServerStart(String worldName, Process process, LogTailer logTailer) {
        log.info("Server process started for world: {} with PID {}", worldName, process.pid());
        try {
            log.info("Waiting for server {} to complete initialization...", worldName);
            CompletableFuture<Void> serverReadyFuture = logTailer.getServerReadyFuture();
            serverReadyFuture.get(120, TimeUnit.SECONDS);
            log.info("Server {} is now fully initialized and ready", worldName);
        }catch (TimeoutException e) {
            handleFailedStart(process, worldName, "Server startup timed out");
        } catch (ExecutionException e) {
            handleFailedStart(process, worldName, "Server error: " + e.getCause().getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            handleFailedStart(process, worldName, "Startup verification interrupted");
        }
    }


    private void handleFailedStart(Process process, String worldName, String errorMessage) {
        removeProcess(worldName);
        process.destroyForcibly();
        log.error("Server startup failed for {}: {}", worldName, errorMessage);
        configService.updateServerStatus(worldName, false);
        throw new RuntimeException("Failed to start server: " + errorMessage);
    }

    public boolean stopServer(String worldName) {
        return  stopServerViaRcon(worldName)
                || stopServerViaProcess(worldName, activeProcesses.get(worldName))
                || stopServerViaProcessHandle(worldName, runningServers.get(worldName));
    }

    private boolean stopServerViaRcon(String worldName) {
        log.info("Attempting to stop server {} via RCON", worldName);
        boolean stopped = rconClientService.stopServer(worldName);
        if (stopped) {
            removeProcess(worldName);
            configService.updateServerStatus(worldName, false);
            log.info("Server {} stopped via RCON", worldName);
        }
        return stopped;
    }

    private boolean stopServerViaProcess(String worldName, Process serverProcess) {
        if (serverProcess == null || !serverProcess.isAlive()) {
            log.warn("Server Process for world {} not running", worldName);
            return false;
        }
        try {
            log.info("Attempting to stop server {} gracefully...", worldName);

            try (OutputStreamWriter writer = new OutputStreamWriter(serverProcess.getOutputStream());
                 BufferedWriter bufferedWriter = new BufferedWriter(writer)) {
                bufferedWriter.write("stop\n");
                bufferedWriter.flush();
            }

            if (serverProcess.waitFor(30, TimeUnit.SECONDS)) {
                log.info("Server {} stopped gracefully", worldName);
                removeProcess(worldName);
                configService.updateServerStatus(worldName, false);
                return true;
            }
        } catch (IOException | InterruptedException e) {
            log.error("Error stopping server {} via process", worldName, e);
            return false;
        }
        return false;
    }

    private boolean stopServerViaProcessHandle(String worldName, int pid){
        log.info("Falling back to process termination for world {}", worldName);

        ProcessHandle serverProcess = ProcessHandle.of(pid).orElse(null);
        if (serverProcess == null || !serverProcess.isAlive()) {
            log.info("Process for world {} is not running", worldName);
            removeProcess(worldName);
            configService.updateServerStatus(worldName, false);
            return true;
        }

        log.info("Attempting to terminate process for world {}", worldName);
        serverProcess.destroy();

        if (serverProcess.onExit().isDone()) {
            log.info("Server {} stopped after destroy() via process handle", worldName);
            removeProcess(worldName);
            configService.updateServerStatus(worldName, false);
            return true;
        }

        log.warn("Server {} still running, using destroyForcibly() via process handle", worldName);
        removeProcess(worldName);
        configService.updateServerStatus(worldName, false);
        return serverProcess.destroyForcibly();
    }

    @PreDestroy
    public void shutdownAllServers() {
        log.info("Initiating shutdown of all Minecraft servers...");

        activeProcesses.keySet().forEach(this::stopServer);

        log.info("All Minecraft servers have been shut down");
    }
}