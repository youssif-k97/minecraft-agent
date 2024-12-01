package com.mcap.minecraftagent.service;

import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.ArrayList;

@Service
public class MinecraftServerService {
    private static final Logger logger = LoggerFactory.getLogger(MinecraftServerService.class);

    @Value("${minecraft.mscs.path}")
    private String mscsPath;
    private static final List<String> ALLOWED_COMMANDS = List.of(
            "start", "stop", "restart", "status", "list", "backup",
            "connected", "running", "worlds", "version"
    );

    public record CommandResult(
            int exitCode,
            List<String> output,
            List<String> errors
    ) {}

    /**
     * Execute an MSCS command for a specific server
     * @param serverName The name of the Minecraft server
     * @param command The MSCS command to execute
     * @return The command execution result
     * @throws IllegalArgumentException if the command is not allowed
     */
    public CommandResult executeCommand(String serverName, String command) {
        if (!ALLOWED_COMMANDS.contains(command)) {
            throw new IllegalArgumentException("Command not allowed: " + command);
        }

        List<String> output = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        try {
            ProcessBuilder processBuilder = new ProcessBuilder(
                    mscsPath,
                    command,
                    serverName
            );

            processBuilder.environment().put("PATH", System.getenv("PATH"));

            Process process = processBuilder.start();

            try (BufferedReader stdInput = new BufferedReader(new InputStreamReader(process.getInputStream()));
                 BufferedReader stdError = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {

                String line;
                while ((line = stdInput.readLine()) != null) {
                    output.add(line);
                }
                while ((line = stdError.readLine()) != null) {
                    errors.add(line);
                }
            }

            boolean completed = process.waitFor(15, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                throw new RuntimeException("Command timed out");
            }

            int exitCode = process.exitValue();
            logger.info("Command '{}' for server '{}' completed with exit code: {}",
                    command, serverName, exitCode);

            return new CommandResult(exitCode, output, errors);

        } catch (Exception e) {
            logger.error("Error executing command '{}' for server '{}'", command, serverName, e);
            throw new RuntimeException("Failed to execute command: " + e.getMessage(), e);
        }
    }

    /**
     * Get the status of all Minecraft servers
     * @return Status information for all servers
     */
    public CommandResult getServers() {
        return executeCommand(" ", "list");
    }

    /**
     * Get the status of all Minecraft servers
     * @return Status information for all servers
     */
    public CommandResult getAllServersStatus() {
        return executeCommand(" ", "status");
    }

    /**
     * Start a specific Minecraft server
     * @param serverName The name of the server to start
     */
    public CommandResult startServer(String serverName) {
        return executeCommand(serverName, "start");
    }

    /**
     * Stop a specific Minecraft server
     * @param serverName The name of the server to stop
     */
    public CommandResult stopServer(String serverName) {
        return executeCommand(serverName, "stop");
    }

    /**
     * Get list of connected players
     * @param serverName The name of the server
     */
    public CommandResult getConnectedPlayers(String serverName) {
        return executeCommand(serverName, "connected");
    }

    /**
     * Create a backup of the server
     * @param serverName The name of the server to backup
     */
    public CommandResult createBackup(String serverName) {
        return executeCommand(serverName, "backup");
    }
}
