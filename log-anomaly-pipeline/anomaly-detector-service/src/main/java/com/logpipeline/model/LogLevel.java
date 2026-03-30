package com.logpipeline.model;

public enum LogLevel {
    DEBUG, INFO, WARN, ERROR, FATAL;

    public boolean isError() {
        return this == ERROR || this == FATAL;
    }
}
