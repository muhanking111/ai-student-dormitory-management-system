package com.example.dormitory.ai.application.run;

import com.example.dormitory.ai.application.run.AiRunRecords.Event;

public interface AiRunEventPublisher {

    void publish(Event event);
}
