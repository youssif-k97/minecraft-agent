package com.mcap.minecraftagent.pojo;

import java.io.BufferedWriter;

public class MinecraftServerProcess {
    private final Process process;


    public MinecraftServerProcess(Process process) {
        this.process = process;
    }

    public Process getProcess() {
        return process;
    }

}
