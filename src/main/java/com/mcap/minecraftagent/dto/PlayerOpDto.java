package com.mcap.minecraftagent.dto;

public record PlayerOpDto(
        String uuid,
        String name,
        boolean op,
        int level,
        boolean bypassesPlayerLimit
) {}
