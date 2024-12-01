package com.mcap.minecraftagent.controller;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import com.mcap.minecraftagent.service.MinecraftServerService;
import com.mcap.minecraftagent.service.MinecraftServerService.CommandResult;

@RestController
@RequestMapping("/api/minecraft")
public class MinecraftServerController {

    private final MinecraftServerService minecraftService;

    public MinecraftServerController(MinecraftServerService minecraftService) {
        this.minecraftService = minecraftService;
    }

    @GetMapping("/servers")
    public ResponseEntity<CommandResult> getServers() {
        return ResponseEntity.ok(minecraftService.getServers());
    }

    @GetMapping("/servers/status")
    public ResponseEntity<CommandResult> getAllServersStatus() {
        return ResponseEntity.ok(minecraftService.getAllServersStatus());
    }


    @PostMapping("/servers/{serverName}/start")
    public ResponseEntity<CommandResult> startServer(@PathVariable String serverName) {
        return ResponseEntity.ok(minecraftService.startServer(serverName));
    }

    @PostMapping("/servers/{serverName}/stop")
    public ResponseEntity<CommandResult> stopServer(@PathVariable String serverName) {
        return ResponseEntity.ok(minecraftService.stopServer(serverName));
    }

    @GetMapping("/servers/{serverName}/players")
    public ResponseEntity<CommandResult> getConnectedPlayers(@PathVariable String serverName) {
        return ResponseEntity.ok(minecraftService.getConnectedPlayers(serverName));
    }

    @PostMapping("/servers/{serverName}/backup")
    public ResponseEntity<CommandResult> createBackup(@PathVariable String serverName) {
        return ResponseEntity.ok(minecraftService.createBackup(serverName));
    }

    @PostMapping("/servers/{serverName}/command")
    public ResponseEntity<CommandResult> executeCommand(
            @PathVariable String serverName,
            @RequestParam String command) {
        return ResponseEntity.ok(minecraftService.executeCommand(serverName, command));
    }
}
