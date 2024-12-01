package com.mcap.minecraftagent.controller;

import com.mcap.minecraftagent.service.ServerPropertiesService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/properties")
public class PropertiesController {
    private final ServerPropertiesService propertiesService;

    @Autowired
    public PropertiesController(ServerPropertiesService propertiesService) {
        this.propertiesService = propertiesService;
    }

    @GetMapping
    public ResponseEntity<Map<String, String>> getAllProperties() {
        return ResponseEntity.ok(propertiesService.getAllProperties());
    }

    @GetMapping("/{key}")
    public ResponseEntity<String> getProperty(@PathVariable String key) {
        String value = propertiesService.getProperty(key);
        if (value == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(value);
    }

    @PutMapping("/{key}")
    public ResponseEntity<Void> updateProperty(
            @PathVariable String key,
            @RequestBody String value) {
        propertiesService.setProperty(key, value);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{key}")
    public ResponseEntity<Void> deleteProperty(@PathVariable String key) {
        boolean deleted = propertiesService.deleteProperty(key);
        if (!deleted) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().build();
    }

    @PutMapping
    public ResponseEntity<Void> updateMultipleProperties(
            @RequestBody Map<String, String> properties) {
        properties.forEach(propertiesService::setProperty);
        return ResponseEntity.ok().build();
    }
}
