package com.baseai.platform.controller;

import com.baseai.platform.service.HealthService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HealthControllerTest {
    /** 存活检查不得依赖外部服务。 */
    @Test
    void livenessAlwaysReportsProcessUp() {
        HealthController controller = new HealthController(mock(HealthService.class));

        assertEquals(Map.of("status", "UP"), controller.live());
    }

    /** 所有依赖就绪时返回 HTTP 200。 */
    @Test
    void readinessReturnsOkWhenDependenciesAreAvailable() {
        HealthService service = mock(HealthService.class);
        when(service.isReady()).thenReturn(true);

        ResponseEntity<Map<String, String>> response = new HealthController(service).ready();

        assertEquals(200, response.getStatusCode().value());
        assertEquals("UP", response.getBody().get("status"));
    }

    /** 任一依赖失败时返回 HTTP 503 且不暴露依赖细节。 */
    @Test
    void readinessReturnsUnavailableWithoutDetails() {
        HealthService service = mock(HealthService.class);
        when(service.isReady()).thenReturn(false);

        ResponseEntity<Map<String, String>> response = new HealthController(service).ready();

        assertEquals(503, response.getStatusCode().value());
        assertEquals(Map.of("status", "DOWN"), response.getBody());
    }
}
