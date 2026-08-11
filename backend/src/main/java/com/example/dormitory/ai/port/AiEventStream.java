package com.example.dormitory.ai.port;

import com.example.dormitory.ai.domain.model.AiStreamEvent;

import java.util.function.Consumer;

public interface AiEventStream {

    void consume(Consumer<AiStreamEvent> consumer);

    boolean cancel();

    boolean isCancelled();
}
