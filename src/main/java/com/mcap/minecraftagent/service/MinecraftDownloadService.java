package com.mcap.minecraftagent.service;

import org.springframework.stereotype.Service;
import java.io.*;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Iterator;

@Service
public class MinecraftDownloadService {
    private static final String VERSION_MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest.json";

    public void downloadServerJar(String version, String destination) throws IOException {
        // Implementation to download server.jar for specific version
        String serverUrl = getServerUrlForVersion(version);
        try (ReadableByteChannel rbc = Channels.newChannel(new URL(serverUrl).openStream());
             FileOutputStream fos = new FileOutputStream(destination + System.getProperty("file.separator") + "server.jar")) {
            fos.getChannel().transferFrom(rbc, 0, Long.MAX_VALUE);
        }
    }

    private String getServerUrlForVersion(String version) {
        try {
            // Fetch JSON data
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode versionManifest = objectMapper.readTree(new URL(VERSION_MANIFEST));
            
            // Access "versions" array in the manifest
            Iterator<JsonNode> versions = versionManifest.get("versions").elements();
            
            while (versions.hasNext()) {
                JsonNode versionObj = versions.next();
                if (versionObj.get("id").asText().equals(version)) {
                    // Retrieve the version-specific URL
                    String versionInfoUrl = versionObj.get("url").asText();
    
                    // Fetch version-specific JSON data
                    JsonNode versionData = objectMapper.readTree(new URL(versionInfoUrl));
                    return versionData.get("downloads").get("server").get("url").asText();
                }
            }
        } catch (IOException e) {
            // Handle potential I/O errors
            e.printStackTrace();
        }
        
        return null; // Return null if version is not found or any exception occurs
    }
}