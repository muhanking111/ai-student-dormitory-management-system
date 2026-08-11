package com.example.dormitory.ai.dashboard;

import java.util.List;

public class UnsupportedMetricException extends RuntimeException {

    private final List<String> supportedMetricIds;

    public UnsupportedMetricException(String message) {
        this(message, List.of());
    }

    public UnsupportedMetricException(String message, List<String> supportedMetricIds) {
        super(message);
        this.supportedMetricIds = List.copyOf(supportedMetricIds);
    }

    public List<String> supportedMetricIds() {
        return supportedMetricIds;
    }
}
