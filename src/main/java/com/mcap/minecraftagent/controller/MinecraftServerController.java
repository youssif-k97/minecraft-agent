package com.mcap.minecraftagent.controller;

import com.mcap.minecraftagent.dto.*;
import com.mcap.minecraftagent.pojo.WorldConfig;
import com.mcap.minecraftagent.service.DatapackService;
import com.mcap.minecraftagent.service.MinecraftInfoService;
import com.mcap.minecraftagent.service.ServerPropertiesService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import com.mcap.minecraftagent.service.WorldManagementService;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/minecraft")
public class MinecraftServerController {

    private final WorldManagementService minecraftService;
    private final MinecraftInfoService infoService;
    private final DatapackService datapackService;
    private final ServerPropertiesService propertiesService;

    public MinecraftServerController(WorldManagementService minecraftService, MinecraftInfoService infoService,
                                     DatapackService datapackService, ServerPropertiesService propertiesService) {
        this.propertiesService = propertiesService;
        this.infoService = infoService;
        this.minecraftService = minecraftService;
        this.datapackService = datapackService;
    }
    @GetMapping("/worlds")
    public ResponseEntity<MinecraftWorldsResponse> getAllWorlds() {
        log.info("Entering getAllWorlds()");
        ResponseEntity<MinecraftWorldsResponse> response = ResponseEntity.ok(new MinecraftWorldsResponse(infoService.getAllWorlds()));
        log.info("Exiting getAllWorlds() with response: {}", response);
        return response;
    }

    @PostMapping("/worlds")
    public ResponseEntity createWorld(@RequestBody WorldConfig requestBody) {
        log.info("Entering createWorld() with requestBody: {}", requestBody);
        try {
            minecraftService.createWorld(requestBody);
        } catch (IOException e) {
            log.error("Error in createWorld(): {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to create world: " + e.getMessage());
        }
        ResponseEntity<String> response = ResponseEntity.ok().body("World created successfully");
        log.info("Exiting createWorld() with response: {}", response);
        return response;
    }

    @GetMapping("/worlds/{worldId}")
    public ResponseEntity<MinecraftWorld> getWorld(@PathVariable String worldId) {
        log.info("Entering getWorld() with worldId: {}", worldId);
        ResponseEntity<MinecraftWorld> response = infoService.getAllWorlds().stream()
                .filter(world -> world.getId().equals(worldId))
                .findFirst()
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
        log.info("Exiting getWorld() with response: {}", response);
        return response;
    }

    @PostMapping("/worlds/{worldId}/start")
    public ResponseEntity startServer(@PathVariable String worldId) {
        log.info("Entering startServer() with worldId: {}", worldId);
        try {
            minecraftService.startServer(worldId);
        } catch (IOException e) {
            log.error("Error in startServer(): {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to start server: " + e.getMessage());
        }
        ResponseEntity<String> response = ResponseEntity.ok().body("Server started successfully");
        log.info("Exiting startServer() with response: {}", response);
        return response;
    }

    @PostMapping("/worlds/{worldId}/stop")
    public ResponseEntity stopServer(@PathVariable String worldId) {
        try {
            minecraftService.stopServer(worldId);
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to stop server: " + e.getMessage());
        }
        return ResponseEntity.ok().body("Server stopped successfully");
    }

    @PostMapping("/worlds/{worldId}/restart")
    public ResponseEntity restartServer(@PathVariable String worldId) {
        try {
            minecraftService.restartServer(worldId);
        } catch (IOException | InterruptedException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to restart server: " + e.getMessage());
        }
        return ResponseEntity.ok().body("Server restarted successfully");
    }

    @DeleteMapping("/worlds/{worldId}/delete")
    public ResponseEntity deleteWorld(@PathVariable String worldId) {
        log.info("Entering deleteWorld() with worldId: {}", worldId);
        try {
            minecraftService.deleteWorld(worldId);
        } catch (IOException e) {
            log.error("Error in deleteWorld(): {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to delete world: " + e.getMessage());
        }
        ResponseEntity<String> response = ResponseEntity.ok().body("World deleted successfully");
        log.info("Exiting deleteWorld() with response: {}", response);
        return response;
    }

    @PostMapping("/worlds/{worldId}/ram")
    public ResponseEntity updateServerRam(@PathVariable String worldId, @RequestBody MinecraftWorld.Ram ram) {
        log.info("Entering updateServerRam() with worldId: {} and RAM details: {}", worldId, ram);
        try {
            minecraftService.updateServerRam(worldId, ram);
        } catch (IOException | InterruptedException e) {
            log.error("Error in updateServerRam(): {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to update server RAM: " + e.getMessage());
        }
        ResponseEntity<String> response = ResponseEntity.ok().body("Server RAM updated successfully");
        log.info("Exiting updateServerRam() with response: {}", response);
        return response;
    }

    @GetMapping("/worlds/{worldId}/datapacks")
    public ResponseEntity<DatapackResponse> getDatapacks(@PathVariable String worldId) {
        log.info("Entering getDatapacks() with worldId: {}", worldId);
        ResponseEntity<DatapackResponse> response = ResponseEntity.ok(new DatapackResponse(datapackService.getDatapacks(worldId)));
        log.info("Exiting getDatapacks() with response: {}", response);
        return response;
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

    @GetMapping("/worlds/{worldId}/properties")
    public ResponseEntity<Map<String, String>> getAllProperties(@PathVariable String worldId) {
        return ResponseEntity.ok(propertiesService.getAllProperties(worldId));
    }

    @PutMapping("/worlds/{worldId}/properties")
    public ResponseEntity<Void> updateProperties(
            @PathVariable String worldId,
            @RequestBody ServerPropertiesDto requestBody) {
        propertiesService.setProperty(worldId, requestBody.properties());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/worlds/{worldId}/backup")
    public ResponseEntity createBackup(@PathVariable String worldId) {
        try {
            minecraftService.createBackup(worldId);
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to create backup: " + e.getMessage());
        }
        return ResponseEntity.ok().body("Backup created successfully");
    }
}
