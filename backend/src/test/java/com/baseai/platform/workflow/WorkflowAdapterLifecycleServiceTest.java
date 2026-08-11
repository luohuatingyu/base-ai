package com.baseai.platform.workflow;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.domain.SystemSetting;
import com.baseai.platform.repository.SystemSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowAdapterLifecycleServiceTest {
    private SystemSettingRepository repository;
    private WorkflowAdapterManagerClient manager;
    private WorkflowAdapterLifecycleService service;
    private SystemSetting n8n;
    private SystemSetting dify;

    /** 初始化两个相互独立的持久化开关和容器控制替身。 */
    @BeforeEach
    void setUp() {
        repository = mock(SystemSettingRepository.class);
        manager = mock(WorkflowAdapterManagerClient.class);
        service = new WorkflowAdapterLifecycleService(repository, manager);
        n8n = setting(WorkflowAdapterLifecycleService.N8N_SETTING_KEY, true);
        dify = setting(WorkflowAdapterLifecycleService.DIFY_SETTING_KEY, false);
        when(repository.findByConfigKey(WorkflowAdapterLifecycleService.N8N_SETTING_KEY)).thenReturn(Optional.of(n8n));
        when(repository.findByConfigKey(WorkflowAdapterLifecycleService.DIFY_SETTING_KEY)).thenReturn(Optional.of(dify));
    }

    /** 两个适配器必须分别读取期望值和实际容器状态。 */
    @Test
    void returnsIndependentAdapterStates() {
        when(manager.state("N8N")).thenReturn(state("N8N", "RUNNING"));
        when(manager.state("DIFY")).thenReturn(state("DIFY", "STOPPED"));

        var adapters = service.adapters();

        assertTrue(adapters.get(0).enabled());
        assertEquals("RUNNING", adapters.get(0).status());
        assertFalse(adapters.get(1).enabled());
        assertEquals("STOPPED", adapters.get(1).status());
    }

    /** 关闭状态必须在执行 Worker 调用前失败，不得进入核心动作。 */
    @Test
    void disabledAdapterRejectsWorkerAction() {
        BusinessException exception = assertThrows(BusinessException.class,
            () -> service.withEnabled("DIFY", () -> "unexpected"));

        assertEquals("workflow.adapterDisabled", exception.getMessageKey());
    }

    /** 存在持读锁的在途任务时关闭必须返回冲突且不得调用容器 manager。 */
    @Test
    void activeTaskPreventsWorkerShutdown() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            executor.submit(() -> service.withEnabled("N8N", () -> {
                entered.countDown();
                try {
                    release.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }));
            assertTrue(entered.await(1, TimeUnit.SECONDS));

            BusinessException exception = assertThrows(BusinessException.class,
                () -> service.setEnabled("N8N", false));

            assertEquals(409, exception.getStatus());
            assertEquals("workflow.adapterBusy", exception.getMessageKey());
            verify(manager, never()).setEnabled("N8N", false);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    /** 无在途任务时关闭应先提交固定来源命令并保存 false 期望值。 */
    @Test
    void disablesIdleAdapterAndPersistsDesiredState() {
        when(manager.setEnabled("N8N", false)).thenReturn(state("N8N", "DISABLING"));

        var result = service.setEnabled("N8N", false);

        assertFalse(result.enabled());
        assertEquals("false", n8n.getConfigValue());
        verify(repository).save(n8n);
    }

    /** manager 拒绝命令时不得提前写入新的期望状态。 */
    @Test
    void managerFailureDoesNotPersistDesiredState() {
        when(manager.setEnabled("N8N", false)).thenThrow(new BusinessException("workflow.adapterManagerUnavailable"));

        BusinessException exception = assertThrows(BusinessException.class,
            () -> service.setEnabled("N8N", false));

        assertEquals("workflow.adapterManagerUnavailable", exception.getMessageKey());
        assertEquals("true", n8n.getConfigValue());
        verify(repository, never()).save(n8n);
    }

    /** 构建最小系统托管适配器参数。 */
    private SystemSetting setting(String key, boolean enabled) {
        SystemSetting setting = new SystemSetting();
        setting.setConfigKey(key);
        setting.setConfigValue(Boolean.toString(enabled));
        return setting;
    }

    /** 构建容器 manager 状态。 */
    private WorkflowAdapterManagerClient.ManagerState state(String source, String status) {
        return new WorkflowAdapterManagerClient.ManagerState(source, status, "");
    }
}
