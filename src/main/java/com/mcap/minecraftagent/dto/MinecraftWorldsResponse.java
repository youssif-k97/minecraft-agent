package com.mcap.minecraftagent.dto;

import java.util.List;
public record MinecraftWorldsResponse(
        List<MinecraftWorld> worlds
) {}