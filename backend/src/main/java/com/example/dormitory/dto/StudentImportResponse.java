package com.example.dormitory.dto;

import java.util.List;

public record StudentImportResponse(int importedCount, List<String> studentNos) {

    public StudentImportResponse {
        studentNos = List.copyOf(studentNos);
    }
}
