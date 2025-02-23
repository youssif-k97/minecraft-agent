package com.mcap.minecraftagent.service;

import com.mcap.minecraftagent.pojo.MinecraftServerProcess;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class ServerProcessManager {
    private final Map<String, MinecraftServerProcess> activeProcesses = new ConcurrentHashMap<>();

    public void registerProcess(String worldName, Process process) {
        activeProcesses.put(worldName, new MinecraftServerProcess(process));
    }

    public MinecraftServerProcess getProcess(String worldName) {
        return activeProcesses.get(worldName);
    }

    public void removeProcess(String worldName) {
        activeProcesses.remove(worldName);
    }

    public boolean stopServer(String worldName) {
        MinecraftServerProcess serverProcess = activeProcesses.get(worldName);
        if (serverProcess == null) {
            log.warn("No process found for world: {}", worldName);
            return false;
        }

        Process process = serverProcess.getProcess();
        if (!process.isAlive()) {
            log.info("Process for world {} is already stopped", worldName);
            removeProcess(worldName);
            return true;
        }

        try {
            log.info("Attempting to stop server {} gracefully...", worldName);

            try (OutputStreamWriter writer = new OutputStreamWriter(process.getOutputStream());
                 BufferedWriter bufferedWriter = new BufferedWriter(writer)) {
                bufferedWriter.write("stop\n");
                bufferedWriter.flush();
            }


            if (process.waitFor(30, TimeUnit.SECONDS)) {
                log.info("Server {} stopped gracefully", worldName);
                removeProcess(worldName);
                return true;
            }

            log.warn("Server {} didn't stop gracefully, attempting destroy()", worldName);
            process.destroy();

            if (process.waitFor(10, TimeUnit.SECONDS)) {
                log.info("Server {} stopped after destroy()", worldName);
                removeProcess(worldName);
                return true;
            }

            log.warn("Server {} still running, using destroyForcibly()", worldName);
            process.destroyForcibly();
            boolean terminated = process.waitFor(5, TimeUnit.SECONDS);
            removeProcess(worldName);

            return terminated;

        } catch (IOException | InterruptedException e) {
            log.error("Error stopping server {}", worldName, e);
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            removeProcess(worldName);
            return false;
        }
    }

    public boolean isProcessRunning(String worldName) {
        MinecraftServerProcess serverProcess = activeProcesses.get(worldName);
        return serverProcess != null && serverProcess.getProcess().isAlive();
    }

    @PreDestroy
    public void shutdownAllServers() {
        log.info("Initiating shutdown of all Minecraft servers...");

        activeProcesses.keySet().forEach(this::stopServer);

        log.info("All Minecraft servers have been shut down");
    }
}
