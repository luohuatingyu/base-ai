package com.baseai.platform.controller;

import com.baseai.platform.repository.LoginLogRepository;
import com.baseai.platform.repository.OperationLogRepository;
import com.baseai.platform.security.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SystemMonitorControllerTest {
    private OperationLogRepository operationLogRepository;
    private LoginLogRepository loginLogRepository;
    private MockMvc mockMvc;

    /** 为每个测试创建隔离的控制器及仓储依赖。 */
    @BeforeEach
    void setUp() {
        operationLogRepository = mock(OperationLogRepository.class);
        loginLogRepository = mock(LoginLogRepository.class);
        when(operationLogRepository.findAll(any(Pageable.class))).thenReturn(Page.empty());
        when(loginLogRepository.findAll(any(Pageable.class))).thenReturn(Page.empty());
        SystemMonitorController controller = new SystemMonitorController(
            mock(SessionService.class), operationLogRepository, loginLogRepository);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    /** 未传分页参数时，日志接口应从第一页开始且每页返回五条。 */
    @ParameterizedTest
    @MethodSource("logEndpoints")
    void usesFiveAsDefaultPageSize(String path, boolean operationLog) throws Exception {
        mockMvc.perform(get(path))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.page").value(1))
            .andExpect(jsonPath("$.size").value(5));

        assertPageable(operationLog, 0, 5);
    }

    /** 显式分页大小应继续生效，并保持最小值和最大值限制。 */
    @ParameterizedTest
    @CsvSource({
        "/api/system/operation-logs, true, 10, 10",
        "/api/system/operation-logs, true, 0, 1",
        "/api/system/operation-logs, true, 101, 100",
        "/api/system/login-logs, false, 10, 10",
        "/api/system/login-logs, false, 0, 1",
        "/api/system/login-logs, false, 101, 100"
    })
    void keepsExplicitPageSizeCompatibility(String path, boolean operationLog, int requestedSize, int expectedSize)
            throws Exception {
        mockMvc.perform(get(path).param("page", "2").param("size", String.valueOf(requestedSize)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.page").value(2))
            .andExpect(jsonPath("$.size").value(expectedSize));

        assertPageable(operationLog, 1, expectedSize);
    }

    /** 提供需要验证默认分页行为的日志接口。 */
    private static Stream<Arguments> logEndpoints() {
        return Stream.of(
            Arguments.of("/api/system/operation-logs", true),
            Arguments.of("/api/system/login-logs", false)
        );
    }

    /** 验证控制器传递给对应仓储的分页参数。 */
    private void assertPageable(boolean operationLog, int expectedPage, int expectedSize) {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        if (operationLog) {
            verify(operationLogRepository).findAll(captor.capture());
        } else {
            verify(loginLogRepository).findAll(captor.capture());
        }
        assertEquals(expectedPage, captor.getValue().getPageNumber());
        assertEquals(expectedSize, captor.getValue().getPageSize());
    }
}
