package com.mcap.minecraftagent.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Data
public class MinecraftWorld{
    private String id;
    private String name;
    @JsonProperty("isActive")
    private boolean isActive;
    private List<String> players;
    private Map<String, String> properties;
    private int port;
    private Ram ram;

    @Data
    public static class Ram {
        private int min;
        private int max;
    }
}
