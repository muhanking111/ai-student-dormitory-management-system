package com.example.dormitory.ai.domain;

import com.example.dormitory.ai.domain.model.ActorDescriptor;
import com.example.dormitory.ai.domain.model.ActorKind;
import com.example.dormitory.ai.domain.model.BusinessActorScope;
import com.example.dormitory.ai.domain.model.BusinessExecutionActor;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActorDescriptorTest {

    @Test
    void onlyARealUserDescriptorCanBecomeBusinessExecutionActor() {
        BusinessExecutionActor user = BusinessExecutionActor.from(ActorDescriptor.user(7L));

        assertEquals(7L, user.userId());
        assertThrows(IllegalArgumentException.class,
                () -> BusinessExecutionActor.from(ActorDescriptor.service("ingestion-worker", 7L, 7L)));
        assertThrows(IllegalArgumentException.class,
                () -> BusinessExecutionActor.from(ActorDescriptor.model("fake-model", 7L)));
        assertThrows(IllegalArgumentException.class,
                () -> BusinessExecutionActor.from(ActorDescriptor.system("outbox-worker")));
        assertTrue(java.util.Arrays.stream(BusinessExecutionActor.class.getDeclaredConstructors())
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers())));
    }

    @Test
    void actorKindsEnforceMutuallyExclusiveTrustedFields() {
        ActorDescriptor service = ActorDescriptor.service("risk-scan", 9L, 9L);

        assertEquals(ActorKind.SERVICE, service.kind());
        assertEquals("risk-scan", service.servicePrincipalCode());
        assertEquals(9L, service.initiatedByUserId());
        assertThrows(IllegalArgumentException.class,
                () -> new ActorDescriptor(ActorKind.USER, null, "forged", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ActorDescriptor(ActorKind.MODEL, 1L, "model", null, null));
    }

    @Test
    void businessScopeDefensivelyCopiesPermissionsAndResourceIds() {
        Set<String> permissions = new LinkedHashSet<>(Set.of("repair:read"));
        Map<String, Set<Long>> resources = new LinkedHashMap<>();
        resources.put("repair", new LinkedHashSet<>(Set.of(11L)));

        BusinessActorScope scope = new BusinessActorScope(ActorDescriptor.user(3L), permissions, resources);
        permissions.add("repair:write");
        resources.get("repair").add(12L);

        assertEquals(Set.of("repair:read"), scope.permissionCodes());
        assertEquals(Set.of(11L), scope.resourceIds().get("repair"));
        assertTrue(scope.canRead("repair", 11L));
        assertThrows(UnsupportedOperationException.class,
                () -> scope.permissionCodes().add("forged"));
    }
}
