-- 原位迁移权限 KEY，菜单 ID 与 sys_role_menu 关系保持不变。
UPDATE sys_menu SET permission = CONCAT('ai:model:knowledge-base:',
    SUBSTRING(permission, CHAR_LENGTH('knowledge:base:') + 1))
WHERE permission LIKE 'knowledge:base:%';
UPDATE sys_menu SET permission = CONCAT('ai:model:',
    SUBSTRING(permission, CHAR_LENGTH('model:') + 1))
WHERE permission LIKE 'model:%';
UPDATE sys_menu SET permission = CONCAT('automation:workflow:',
    SUBSTRING(permission, CHAR_LENGTH('workflow:') + 1))
WHERE permission LIKE 'workflow:%';
UPDATE sys_menu SET permission = CONCAT('operations:data-sync:',
    SUBSTRING(permission, CHAR_LENGTH('data-sync:') + 1))
WHERE permission LIKE 'data-sync:%';
UPDATE sys_menu SET permission = CONCAT('operations:server:',
    SUBSTRING(permission, CHAR_LENGTH('server:') + 1))
WHERE permission LIKE 'server:%';
UPDATE sys_menu SET permission = CONCAT('operations:device-agent:',
    SUBSTRING(permission, CHAR_LENGTH('automation:device-agent:') + 1))
WHERE permission LIKE 'automation:device-agent:%';
UPDATE sys_menu SET permission = CONCAT('operations:task:',
    SUBSTRING(permission, CHAR_LENGTH('system:task:') + 1))
WHERE permission LIKE 'system:task:%';
UPDATE sys_menu SET permission = CONCAT('operations:session:',
    SUBSTRING(permission, CHAR_LENGTH('system:session:') + 1))
WHERE permission LIKE 'system:session:%';
UPDATE sys_menu SET permission = CONCAT('operations:audit:',
    SUBSTRING(permission, CHAR_LENGTH('system:audit:') + 1))
WHERE permission LIKE 'system:audit:%';
UPDATE sys_menu SET permission = CONCAT('system:mail:',
    SUBSTRING(permission, CHAR_LENGTH('mail:') + 1))
WHERE permission LIKE 'mail:%';

-- 新目录使用缺失插入，保证迁移在隔离验证和人工恢复场景下可重复执行。
INSERT INTO sys_menu(parent_id,name,type,path,component,icon,permission,sort_order,visible,enabled,created_at,updated_at)
SELECT NULL,'运维管理','CATALOG','/operations',NULL,'Monitor','operations:catalog',30,TRUE,TRUE,
       CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission='operations:catalog');

INSERT INTO sys_menu(parent_id,name,type,path,component,icon,permission,sort_order,visible,enabled,created_at,updated_at)
SELECT (SELECT parent_lookup.id FROM (SELECT id FROM sys_menu WHERE permission='operations:catalog') AS parent_lookup),'监控审计','CATALOG',
       '/operations/monitoring',NULL,'DataAnalysis','operations:monitoring:catalog',20,TRUE,TRUE,
       CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission='operations:monitoring:catalog');

INSERT INTO sys_menu(parent_id,name,type,path,component,icon,permission,sort_order,visible,enabled,created_at,updated_at)
SELECT (SELECT parent_lookup.id FROM (SELECT id FROM sys_menu WHERE permission='system:catalog') AS parent_lookup),'访问控制','CATALOG',
       '/system/access',NULL,'Lock','system:access:catalog',10,TRUE,TRUE,
       CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission='system:access:catalog');

INSERT INTO sys_menu(parent_id,name,type,path,component,icon,permission,sort_order,visible,enabled,created_at,updated_at)
SELECT (SELECT parent_lookup.id FROM (SELECT id FROM sys_menu WHERE permission='system:catalog') AS parent_lookup),'组织管理','CATALOG',
       '/system/organization',NULL,'OfficeBuilding','system:organization:catalog',20,TRUE,TRUE,
       CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission='system:organization:catalog');

-- 固定四个一级目录及全部二级目录的位置和显示顺序。
UPDATE sys_menu SET parent_id=NULL,name='AI 能力',path='/ai',sort_order=10 WHERE permission='ai:catalog';
UPDATE sys_menu SET parent_id=NULL,name='自动化',path='/automation',sort_order=20 WHERE permission='automation:catalog';
UPDATE sys_menu SET parent_id=NULL,name='运维管理',path='/operations',sort_order=30 WHERE permission='operations:catalog';
UPDATE sys_menu SET parent_id=NULL,name='系统管理',path='/system',sort_order=40 WHERE permission='system:catalog';

UPDATE sys_menu SET parent_id=(SELECT parent_lookup.id FROM (SELECT id FROM sys_menu WHERE permission='ai:catalog') AS parent_lookup),
    name='模型管理',path='/models',sort_order=20
WHERE permission='ai:model:catalog';
UPDATE sys_menu SET parent_id=(SELECT parent_lookup.id FROM (SELECT id FROM sys_menu WHERE permission='automation:catalog') AS parent_lookup),
    name='工作流',path='/workflow',sort_order=20
WHERE permission='automation:workflow:catalog';
UPDATE sys_menu SET parent_id=(SELECT parent_lookup.id FROM (SELECT id FROM sys_menu WHERE permission='operations:catalog') AS parent_lookup),
    name='监控审计',path='/operations/monitoring',sort_order=20
WHERE permission='operations:monitoring:catalog';
UPDATE sys_menu SET parent_id=(SELECT parent_lookup.id FROM (SELECT id FROM sys_menu WHERE permission='system:catalog') AS parent_lookup),
    name='访问控制',path='/system/access',sort_order=10
WHERE permission='system:access:catalog';
UPDATE sys_menu SET parent_id=(SELECT parent_lookup.id FROM (SELECT id FROM sys_menu WHERE permission='system:catalog') AS parent_lookup),
    name='组织管理',path='/system/organization',sort_order=20
WHERE permission='system:organization:catalog';
UPDATE sys_menu SET parent_id=(SELECT parent_lookup.id FROM (SELECT id FROM sys_menu WHERE permission='system:catalog') AS parent_lookup),
    name='邮件管理',path='/mail',sort_order=40
WHERE permission='system:mail:catalog';

-- 页面移动到新目录，按钮继续归属原页面。
UPDATE sys_menu SET parent_id=(SELECT parent_lookup.id FROM (SELECT id FROM sys_menu WHERE permission='ai:catalog') AS parent_lookup),sort_order=10
WHERE type='MENU' AND permission='ai:chat:invoke';
UPDATE sys_menu SET parent_id=(SELECT parent_lookup.id FROM (SELECT id FROM sys_menu WHERE permission='ai:model:catalog') AS parent_lookup)
WHERE type='MENU' AND permission LIKE 'ai:model:%';

UPDATE sys_menu SET parent_id=(SELECT parent_lookup.id FROM (SELECT id FROM sys_menu WHERE permission='automation:catalog') AS parent_lookup),
    sort_order=11
WHERE type='MENU' AND (permission LIKE 'automation:api-trigger:%'
    AND permission NOT LIKE 'automation:api-trigger-security:%');
UPDATE sys_menu SET parent_id=(SELECT parent_lookup.id FROM (SELECT id FROM sys_menu WHERE permission='automation:catalog') AS parent_lookup),
    sort_order=12
WHERE type='MENU' AND permission LIKE 'automation:api-trigger-security:%';
UPDATE sys_menu SET parent_id=(SELECT parent_lookup.id FROM (SELECT id FROM sys_menu WHERE permission='automation:workflow:catalog') AS parent_lookup)
WHERE type='MENU' AND permission LIKE 'automation:workflow:%';

UPDATE sys_menu SET parent_id=(SELECT parent_lookup.id FROM (SELECT id FROM sys_menu WHERE permission='operations:catalog') AS parent_lookup),
    sort_order=11
WHERE type='MENU' AND permission LIKE 'operations:data-sync:%';
UPDATE sys_menu SET parent_id=(SELECT parent_lookup.id FROM (SELECT id FROM sys_menu WHERE permission='operations:catalog') AS parent_lookup),
    sort_order=12
WHERE type='MENU' AND permission LIKE 'operations:server:%';
UPDATE sys_menu SET parent_id=(SELECT parent_lookup.id FROM (SELECT id FROM sys_menu WHERE permission='operations:catalog') AS parent_lookup),
    sort_order=13
WHERE type='MENU' AND permission LIKE 'operations:device-agent:%';
UPDATE sys_menu SET parent_id=(SELECT parent_lookup.id FROM (SELECT id FROM sys_menu WHERE permission='operations:monitoring:catalog') AS parent_lookup)
WHERE type='MENU' AND (permission LIKE 'operations:session:%'
    OR permission LIKE 'operations:audit:%' OR permission LIKE 'operations:task:%');

UPDATE sys_menu SET parent_id=(SELECT parent_lookup.id FROM (SELECT id FROM sys_menu WHERE permission='system:access:catalog') AS parent_lookup)
WHERE type='MENU' AND (permission LIKE 'system:user:%' OR permission LIKE 'system:role:%'
    OR permission LIKE 'system:menu:%' OR permission LIKE 'system:api-key:%');
UPDATE sys_menu SET parent_id=(SELECT parent_lookup.id FROM (SELECT id FROM sys_menu WHERE permission='system:organization:catalog') AS parent_lookup)
WHERE type='MENU' AND (permission LIKE 'system:department:%' OR permission LIKE 'system:position:%');
UPDATE sys_menu SET parent_id=(SELECT parent_lookup.id FROM (SELECT id FROM sys_menu WHERE permission='system:catalog') AS parent_lookup)
WHERE type='MENU' AND (permission LIKE 'system:dictionary:%' OR permission LIKE 'system:setting:%');
UPDATE sys_menu SET parent_id=(SELECT parent_lookup.id FROM (SELECT id FROM sys_menu WHERE permission='system:mail:catalog') AS parent_lookup)
WHERE type='MENU' AND permission LIKE 'system:mail:%';

-- 为已有角色补目录祖先；不插入任何页面或按钮授权。
INSERT INTO sys_role_menu(role_id,menu_id)
SELECT DISTINCT granted.role_id,catalog.id
FROM sys_role_menu granted
JOIN sys_menu child ON child.id=granted.menu_id
JOIN sys_menu catalog ON catalog.permission='ai:model:catalog'
WHERE child.permission LIKE 'ai:model:%' AND child.id<>catalog.id
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu existing
                  WHERE existing.role_id=granted.role_id AND existing.menu_id=catalog.id);

INSERT INTO sys_role_menu(role_id,menu_id)
SELECT DISTINCT granted.role_id,catalog.id
FROM sys_role_menu granted
JOIN sys_menu child ON child.id=granted.menu_id
JOIN sys_menu catalog ON catalog.permission='automation:workflow:catalog'
WHERE child.permission LIKE 'automation:workflow:%' AND child.id<>catalog.id
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu existing
                  WHERE existing.role_id=granted.role_id AND existing.menu_id=catalog.id);

INSERT INTO sys_role_menu(role_id,menu_id)
SELECT DISTINCT granted.role_id,catalog.id
FROM sys_role_menu granted
JOIN sys_menu child ON child.id=granted.menu_id
JOIN sys_menu catalog ON catalog.permission='operations:monitoring:catalog'
WHERE (child.permission LIKE 'operations:session:%' OR child.permission LIKE 'operations:audit:%'
       OR child.permission LIKE 'operations:task:%') AND child.id<>catalog.id
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu existing
                  WHERE existing.role_id=granted.role_id AND existing.menu_id=catalog.id);

INSERT INTO sys_role_menu(role_id,menu_id)
SELECT DISTINCT granted.role_id,catalog.id
FROM sys_role_menu granted
JOIN sys_menu child ON child.id=granted.menu_id
JOIN sys_menu catalog ON catalog.permission='system:access:catalog'
WHERE (child.permission LIKE 'system:user:%' OR child.permission LIKE 'system:role:%'
       OR child.permission LIKE 'system:menu:%' OR child.permission LIKE 'system:api-key:%')
  AND child.id<>catalog.id
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu existing
                  WHERE existing.role_id=granted.role_id AND existing.menu_id=catalog.id);

INSERT INTO sys_role_menu(role_id,menu_id)
SELECT DISTINCT granted.role_id,catalog.id
FROM sys_role_menu granted
JOIN sys_menu child ON child.id=granted.menu_id
JOIN sys_menu catalog ON catalog.permission='system:organization:catalog'
WHERE (child.permission LIKE 'system:department:%' OR child.permission LIKE 'system:position:%')
  AND child.id<>catalog.id
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu existing
                  WHERE existing.role_id=granted.role_id AND existing.menu_id=catalog.id);

INSERT INTO sys_role_menu(role_id,menu_id)
SELECT DISTINCT granted.role_id,catalog.id
FROM sys_role_menu granted
JOIN sys_menu child ON child.id=granted.menu_id
JOIN sys_menu catalog ON catalog.permission='system:mail:catalog'
WHERE child.permission LIKE 'system:mail:%' AND child.id<>catalog.id
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu existing
                  WHERE existing.role_id=granted.role_id AND existing.menu_id=catalog.id);

INSERT INTO sys_role_menu(role_id,menu_id)
SELECT DISTINCT granted.role_id,catalog.id
FROM sys_role_menu granted
JOIN sys_menu child ON child.id=granted.menu_id
JOIN sys_menu catalog ON catalog.permission='ai:catalog'
WHERE child.permission LIKE 'ai:%' AND child.id<>catalog.id
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu existing
                  WHERE existing.role_id=granted.role_id AND existing.menu_id=catalog.id);

INSERT INTO sys_role_menu(role_id,menu_id)
SELECT DISTINCT granted.role_id,catalog.id
FROM sys_role_menu granted
JOIN sys_menu child ON child.id=granted.menu_id
JOIN sys_menu catalog ON catalog.permission='automation:catalog'
WHERE child.permission LIKE 'automation:%' AND child.id<>catalog.id
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu existing
                  WHERE existing.role_id=granted.role_id AND existing.menu_id=catalog.id);

INSERT INTO sys_role_menu(role_id,menu_id)
SELECT DISTINCT granted.role_id,catalog.id
FROM sys_role_menu granted
JOIN sys_menu child ON child.id=granted.menu_id
JOIN sys_menu catalog ON catalog.permission='operations:catalog'
WHERE child.permission LIKE 'operations:%' AND child.id<>catalog.id
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu existing
                  WHERE existing.role_id=granted.role_id AND existing.menu_id=catalog.id);

INSERT INTO sys_role_menu(role_id,menu_id)
SELECT DISTINCT granted.role_id,catalog.id
FROM sys_role_menu granted
JOIN sys_menu child ON child.id=granted.menu_id
JOIN sys_menu catalog ON catalog.permission='system:catalog'
WHERE child.permission LIKE 'system:%' AND child.id<>catalog.id
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu existing
                  WHERE existing.role_id=granted.role_id AND existing.menu_id=catalog.id);
