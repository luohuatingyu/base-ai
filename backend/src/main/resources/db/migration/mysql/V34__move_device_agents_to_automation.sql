-- 原位迁移设备 Agent 权限，菜单 ID 和既有角色授权保持不变。
UPDATE sys_menu SET permission = CONCAT('automation:device-agent:',
    SUBSTRING(permission, CHAR_LENGTH('operations:device-agent:') + 1))
WHERE permission LIKE 'operations:device-agent:%';

-- 页面迁入自动化目录，并统一新的同级与按钮排序。
UPDATE sys_menu
SET parent_id=(SELECT parent_lookup.id FROM (
        SELECT id FROM sys_menu WHERE permission='automation:catalog'
    ) AS parent_lookup),
    name='设备 Agent 管理',path='/automation/device-agents',component='DeviceAgentsView',sort_order=13
WHERE permission='automation:device-agent:list';

UPDATE sys_menu SET sort_order=131 WHERE permission='automation:device-agent:create';
UPDATE sys_menu SET sort_order=132 WHERE permission='automation:device-agent:update';
UPDATE sys_menu SET sort_order=133 WHERE permission='automation:device-agent:delete';
UPDATE sys_menu SET sort_order=134 WHERE permission='automation:device-agent:execute';

-- 已有角色如果持有任一设备 Agent 权限，则补齐自动化目录权限以保持菜单层级可见。
INSERT INTO sys_role_menu(role_id,menu_id)
SELECT DISTINCT agent_role.role_id,automation_menu.id
FROM sys_role_menu agent_role
JOIN sys_menu agent_menu ON agent_menu.id=agent_role.menu_id
JOIN sys_menu automation_menu ON automation_menu.permission='automation:catalog'
WHERE agent_menu.permission LIKE 'automation:device-agent:%'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_menu existing_role_menu
      WHERE existing_role_menu.role_id=agent_role.role_id
        AND existing_role_menu.menu_id=automation_menu.id
  );
