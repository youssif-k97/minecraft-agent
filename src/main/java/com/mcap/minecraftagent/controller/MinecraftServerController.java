package com.mcap.minecraftagent.controller;

import com.mcap.minecraftagent.dto.*;
import com.mcap.minecraftagent.service.DatapackService;
import com.mcap.minecraftagent.service.MinecraftInfoService;
import com.mcap.minecraftagent.service.ServerPropertiesService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import com.mcap.minecraftagent.service.MinecraftServerService;
import com.mcap.minecraftagent.service.MinecraftServerService.CommandResult;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

@RestController
@RequestMapping("/api/minecraft")
public class MinecraftServerController {

    private final MinecraftServerService minecraftService;
    private final MinecraftInfoService infoService;
    private final DatapackService datapackService;
    private final ServerPropertiesService propertiesService;

    public MinecraftServerController(MinecraftServerService minecraftService, MinecraftInfoService infoService,
                                     DatapackService datapackService, ServerPropertiesService propertiesService) {
        this.propertiesService = propertiesService;
        this.infoService = infoService;
        this.minecraftService = minecraftService;
        this.datapackService = datapackService;
    }
    @GetMapping("/worlds")
    public ResponseEntity<MinecraftWorldsResponse> getAllWorlds() {
        return ResponseEntity.ok(new MinecraftWorldsResponse(infoService.getAllWorlds()));
    }

    @GetMapping("/worlds/{worldId}")
    public ResponseEntity<MinecraftWorld> getWorld(@PathVariable String worldId) {
        return infoService.getAllWorlds().stream()
                .filter(world -> world.id().equals(worldId))
                .findFirst()
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/worlds/{worldId}/start")
    public ResponseEntity<CommandResult> startServer(@PathVariable String worldId) {
        return ResponseEntity.ok(minecraftService.startServer(worldId));
    }

    @PostMapping("/worlds/{worldId}/stop")
    public ResponseEntity<CommandResult> stopServer(@PathVariable String worldId) {
        return ResponseEntity.ok(minecraftService.stopServer(worldId));
    }

    @GetMapping("/worlds/{worldId}/datapacks")
    public ResponseEntity<DatapackResponse> getDatapacks(@PathVariable String worldId) {
        return ResponseEntity.ok(new DatapackResponse(datapackService.getDatapacks(worldId)));
    }

    @PostMapping("/worlds/{worldId}/datapacks")
    public ResponseEntity<String> downloadDatapacks(@PathVariable String worldId, @RequestBody Map<String, String> datapacks) {
        String datapackName = datapacks.get("name");
        String datapackUrl = datapacks.get("downloadUrl");
        try {
            Path downloadedFile = this.datapackService.downloadDatapack(worldId, datapackName, datapackUrl);
            return ResponseEntity.ok("File downloaded successfully to: " + downloadedFile);
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to download file: " + e.getMessage());
        }
    }

    @DeleteMapping("/worlds/{worldId}/datapacks/{datapackName}")
    public ResponseEntity<String> removeDatapack(@PathVariable String worldId, @PathVariable String datapackName) {
        this.datapackService.removeDatapack(worldId, datapackName);
        return ResponseEntity.ok("Datapack removed successfully");
    }

    @PutMapping("/worlds/{worldId}/properties")
    public ResponseEntity<Void> updateProperties(
            @PathVariable String worldId,
            @RequestBody ServerPropertiesDto requestBody) {
        propertiesService.setProperty(worldId, requestBody.properties());
        return ResponseEntity.ok().build();
    }

    @GetMapping("/worlds/{worldId}/properties")
    public ResponseEntity<Map<String, String>> getAllProperties(@PathVariable String worldId) {
        return ResponseEntity.ok(propertiesService.getAllProperties(worldId));
    }

    @PostMapping("/worlds/{worldId}/backup")
    public ResponseEntity<CommandResult> createBackup(@PathVariable String worldId) {
        return ResponseEntity.ok(minecraftService.createBackup(worldId));
    }

    @PostMapping("/servers/{serverName}/command")
    public ResponseEntity<CommandResult> executeCommand(
            @PathVariable String serverName,
            @RequestParam String command) {
        return ResponseEntity.ok(minecraftService.executeCommand(serverName, command));
    }
}
