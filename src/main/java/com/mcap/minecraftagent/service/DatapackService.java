package com.mcap.minecraftagent.service;

import com.mcap.minecraftagent.dto.Datapack;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Service
public class DatapackService {

    private static final int BUFFER_SIZE = 8192;

    private final String baseDir = System.getProperty("user.home") + System.getProperty("file.separator") + "minecraft-servers" + System.getProperty("file.separator");

    public Path downloadDatapack(String worldId, String datapackName, String datapackUrl) throws IOException {
        String worldDatapacksDir = baseDir + worldId + System.getProperty("file.separator") + "datapacks";
        URL url = new URL(datapackUrl);
        Path targetPath = Paths.get(worldDatapacksDir, datapackName);

        // Create target directory if it doesn't exist
        Files.createDirectories(Paths.get(worldDatapacksDir));

        try (BufferedInputStream in = new BufferedInputStream(url.openStream());
             BufferedOutputStream out = new BufferedOutputStream(Files.newOutputStream(targetPath))) {

            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        }

        return targetPath;
    }
    public void removeDatapack(String worldId, String datapackName) {
        String worldDatapacksDir = baseDir + worldId + System.getProperty("file.separator") + "datapacks";
        // Loop through the datapack files in the directory and add them to the list
        File datapacksDir = new File(worldDatapacksDir);
        if (datapacksDir.exists() && datapacksDir.isDirectory()) {
            File[] files = datapacksDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isFile()) {
                        if (file.getName().equals(datapackName)) {
                            file.delete();
                        }
                    }
                }
            }
        }
    }
    public List<Datapack> getDatapacks(String worldId) {
        String worldDatapacksDir = baseDir + worldId + System.getProperty("file.separator") + "datapacks";
        List<Datapack> datapacks = new ArrayList<>();
        // Loop through the datapack files in the directory and add them to the list
        File datapacksDir = new File(worldDatapacksDir);
        if (datapacksDir.exists() && datapacksDir.isDirectory()) {
            File[] files = datapacksDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isFile()) {
                        datapacks.add(new Datapack(file.getName(), "random date"));
                    }
                }
            }
        }
        return datapacks;
    }

}
