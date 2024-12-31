package com.mcap.minecraftagent.dto;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record MinecraftWorld(
        String id,
        String name,
        boolean isActive,
        List<String> players,
        Set<String> whitelistPlayers,
        Set<String> blacklistPlayers,
        Map<String, String> properties,
        Map<String, String> customProperties
) {}
