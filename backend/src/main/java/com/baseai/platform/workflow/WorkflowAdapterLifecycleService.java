package com.baseai.platform.workflow;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.domain.SystemSetting;
import com.baseai.platform.repository.SystemSettingRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/** 持久化适配器期望状态，并用读写锁阻止有在途任务时关闭 Worker。 */
@Service
public class WorkflowAdapterLifecycleService {
    public static final String N8N_SETTING_KEY = "workflow.adapter.n8n.enabled";
    public static final String DIFY_SETTING_KEY = "workflow.adapter.dify.enabled";
    private static final Map<String, String> SETTING_KEYS = Map.of("N8N", N8N_SETTING_KEY, "DIFY", DIFY_SETTING_KEY);

    private final SystemSettingRepository settingRepository;
    private final WorkflowAdapterManagerClient managerClient;
    private final Map<String, ReentrantReadWriteLock> locks = Map.of(
        "N8N", new ReentrantReadWriteLock(true), "DIFY", new ReentrantReadWriteLock(true));

    /** 注入状态仓储与隔离容器控制客户端。 */
    public WorkflowAdapterLifecycleService(SystemSettingRepository settingRepository,
                                           WorkflowAdapterManagerClient managerClient) {
        this.settingRepository = settingRepository;
        this.managerClient = managerClient;
    }

    /** 查询两个来源的期望值、实际容器状态和当前在途请求数。 */
    public List<AdapterView> adapters() {
        return List.of(view("N8N"), view("DIFY"));
    }

    /** 切换单个来源；关闭时只要存在持锁中的 Worker 请求就立即拒绝。 */
    public AdapterView setEnabled(String rawSource, boolean enabled) {
        String source = source(rawSource);
        ReentrantReadWriteLock lock = locks.get(source);
        boolean acquired = enabled ? acquire(lock) : lock.writeLock().tryLock();
        if (!acquired) throw new BusinessException(409, "workflow.adapterBusy");
        try {
            SystemSetting setting = setting(source);
            WorkflowAdapterManagerClient.ManagerState state = managerClient.setEnabled(source, enabled);
            setting.setConfigValue(Boolean.toString(enabled));
            settingRepository.save(setting);
            return toView(source, enabled, state);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 在适配器启用且关闭写锁未持有时执行一次 Worker 操作。 */
    public <T> T withEnabled(String rawSource, Supplier<T> action) {
        String source = source(rawSource);
        ReentrantReadWriteLock.ReadLock lock = locks.get(source).readLock();
        lock.lock();
        try {
            requireEnabled(source);
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    /** 校验来源开关已开启，供市场查询在访问外部站点前快速失败。 */
    public void requireEnabled(String rawSource) {
        if (!desiredEnabled(source(rawSource))) throw new BusinessException(409, "workflow.adapterDisabled");
    }

    /** 定期使容器实际状态收敛到数据库期望值，进程重启后无需人工重复切换。 */
    @Scheduled(fixedDelayString = "${WORKFLOW_ADAPTER_RECONCILE_DELAY_MS:15000}")
    public void reconcile() {
        for (String source : SETTING_KEYS.keySet()) {
            try {
                WorkflowAdapterManagerClient.ManagerState state = managerClient.state(source);
                boolean desired = desiredEnabled(source);
                if (desired && "STOPPED".equals(state.status())) managerClient.setEnabled(source, true);
                if (!desired && "RUNNING".equals(state.status())) reconcileStop(source);
            } catch (RuntimeException ignored) {
                // 控制服务短暂不可用时保留期望状态，由下一个周期继续收敛。
            }
        }
    }

    /** 在排除在途读锁并阻止新任务进入后，修复意外仍在运行的关闭态容器。 */
    private void reconcileStop(String source) {
        ReentrantReadWriteLock.WriteLock lock = locks.get(source).writeLock();
        if (!lock.tryLock()) return;
        try {
            managerClient.setEnabled(source, false);
        } finally {
            lock.unlock();
        }
    }

    /** 判断系统参数键是否只能由节点管理专用接口维护。 */
    public static boolean isReservedKey(String key) { return SETTING_KEYS.containsValue(key); }

    /** 规范化并限制适配来源，避免任意字符串到达容器控制层。 */
    public static String source(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!SETTING_KEYS.containsKey(normalized)) throw new BusinessException("workflow.marketplaceSourceInvalid");
        return normalized;
    }

    /** 阻塞获取启用操作的写锁，并固定返回已获取状态。 */
    private boolean acquire(ReentrantReadWriteLock lock) {
        lock.writeLock().lock();
        return true;
    }

    /** 从持久化参数读取期望值，缺失时安全回退为关闭。 */
    private boolean desiredEnabled(String source) {
        return settingRepository.findByConfigKey(SETTING_KEYS.get(source))
            .map(SystemSetting::getConfigValue).map(Boolean::parseBoolean).orElse(false);
    }

    /** 返回指定来源当前完整视图。 */
    private AdapterView view(String source) {
        return toView(source, desiredEnabled(source), managerClient.state(source));
    }

    /** 合并期望值、manager 状态与本进程在途计数。 */
    private AdapterView toView(String source, boolean desiredEnabled,
                               WorkflowAdapterManagerClient.ManagerState state) {
        return new AdapterView(source, desiredEnabled, state.status(), locks.get(source).getReadLockCount(), "");
    }

    /** 读取由启动初始化器创建的系统托管参数。 */
    private SystemSetting setting(String source) {
        return settingRepository.findByConfigKey(SETTING_KEYS.get(source))
            .orElseThrow(() -> new BusinessException("workflow.adapterSettingMissing"));
    }

    /** 节点管理页展示的适配器生命周期状态。 */
    public record AdapterView(String source, boolean enabled, String status, int activeTasks, String error) {}
}
