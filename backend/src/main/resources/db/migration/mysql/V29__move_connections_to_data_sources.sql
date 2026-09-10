-- 将既有连接维护权限原位迁移到运维数据源域，保留菜单 ID 和角色授权关系。
UPDATE sys_menu SET permission = CONCAT('operations:data-source:',
    SUBSTRING(permission, CHAR_LENGTH('automation:workflow:connection:') + 1))
WHERE permission LIKE 'automation:workflow:connection:%';

UPDATE sys_menu
SET parent_id=(SELECT parent_lookup.id FROM
        (SELECT id FROM sys_menu WHERE permission='operations:catalog') AS parent_lookup),
    name='数据源管理',
    path='/data-sources',
    component='DataSourcesView',
    icon='Link',
    sort_order=11
WHERE type='MENU' AND permission='operations:data-source:list';

UPDATE sys_menu SET sort_order=12
WHERE type='MENU' AND permission='operations:data-sync:list';
UPDATE sys_menu SET sort_order=13
WHERE type='MENU' AND permission='operations:server:list';
UPDATE sys_menu SET sort_order=14
WHERE type='MENU' AND permission='operations:device-agent:list';

INSERT INTO sys_menu(parent_id,name,type,path,component,icon,permission,sort_order,visible,enabled,created_at,updated_at)
SELECT page.id,'测试数据源','BUTTON',NULL,NULL,NULL,'operations:data-source:test',115,FALSE,TRUE,
       CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)
FROM sys_menu page
WHERE page.permission='operations:data-source:list'
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission='operations:data-source:test');

-- 已有数据源授权补齐运维目录，但不扩展任何资源操作权限。
INSERT INTO sys_role_menu(role_id,menu_id)
SELECT DISTINCT granted.role_id,catalog.id
FROM sys_role_menu granted
JOIN sys_menu child ON child.id=granted.menu_id
JOIN sys_menu catalog ON catalog.permission='operations:catalog'
WHERE child.permission LIKE 'operations:data-source:%' AND child.id<>catalog.id
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu existing
                  WHERE existing.role_id=granted.role_id AND existing.menu_id=catalog.id);
