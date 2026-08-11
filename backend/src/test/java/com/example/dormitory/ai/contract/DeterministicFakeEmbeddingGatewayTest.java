package com.example.dormitory.ai.contract;

import com.example.dormitory.ai.infrastructure.fake.DeterministicFakeEmbeddingGateway;
import com.example.dormitory.ai.port.EmbeddingGateway;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class DeterministicFakeEmbeddingGatewayTest {

    @Test
    void sameContentProducesSameVersionedVectorWithoutNetwork() {
        DeterministicFakeEmbeddingGateway gateway = new DeterministicFakeEmbeddingGateway();
        EmbeddingGateway.EmbeddingRequest request =
                new EmbeddingGateway.EmbeddingRequest(List.of("宿舍安全", "维修流程"), "fake-embedding-v1");

        var first = gateway.embed(request);
        var second = gateway.embed(request);

        assertEquals(first, second);
        assertEquals("fake-embedding-v1", first.modelAlias());
        assertEquals(2, first.vectors().size());
        assertEquals(8, first.vectors().getFirst().values().size());
        assertNotEquals(first.vectors().getFirst().values(), first.vectors().getLast().values());
    }
}
