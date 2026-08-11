package com.example.dormitory.ai.config;

/** Kill Switch 状态或版本在人工复核后发生变化。 */
public final class RuntimeSwitchConflictException extends RuntimeException {

    public RuntimeSwitchConflictException() {
        super("Kill Switch 状态或版本已变化");
    }
}
