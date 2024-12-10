package com.mcap.minecraftagent.dto;

import java.util.List;
import java.util.Map;

public record MinecraftWorld(
        String id,
        String name,
        boolean isActive,
        List<String> players,
        Map<String, String> properties
) {}
