package com.mcap.minecraftagent.pojo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class WorldConfig {
    // Basic settings
    private String worldName;
    private String serverVersion;
    private int port = 25565;
    private int minMemory = 1024;
    private int maxMemory = 2048;

    // Runtime state
    private boolean isRunning;

    // Metadata
    private LocalDateTime createdAt;
    private LocalDateTime lastStarted;
    private String lastBackup;
}
