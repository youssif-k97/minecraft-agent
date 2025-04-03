package com.mcap.minecraftagent.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class MinecraftWorld{
    private String id;
    private String name;
    private String serverVersion;
    @JsonProperty("isActive")
    private boolean isActive;
    private int port;
    private Ram ram;

    @Data
    public static class Ram {
        private int min;
        private int max;
    }
}
