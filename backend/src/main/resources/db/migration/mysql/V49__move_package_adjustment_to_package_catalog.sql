-- 将套餐调整迁移到独立的一级套餐管理目录，并保留页面权限编码。
INSERT INTO sys_menu(parent_id,name,type,path,component,icon,permission,sort_order,visible,enabled,created_at,updated_at)
SELECT NULL,'套餐管理','CATALOG','/packages',NULL,'Goods','package:catalog',15,TRUE,TRUE,
       CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission='package:catalog');

-- 固定目录属性，支持从部分执行状态安全恢复。
UPDATE sys_menu SET parent_id=NULL,name='套餐管理',type='CATALOG',path='/packages',component=NULL,
    icon='Goods',sort_order=15,visible=TRUE,enabled=TRUE
WHERE permission='package:catalog';

-- 页面继续使用原路径和权限，已有前端书签与授权保持兼容。
UPDATE sys_menu SET parent_id=(SELECT parent_lookup.id FROM (SELECT id FROM sys_menu WHERE permission='package:catalog') AS parent_lookup),
    sort_order=10
WHERE permission='ai:package-adjustment:list';

-- 角色已有页面权限时补齐新目录权限，确保迁移后页面仍出现在动态导航中。
INSERT INTO sys_role_menu(role_id,menu_id)
SELECT DISTINCT granted.role_id,catalog.id
FROM sys_role_menu granted
JOIN sys_menu child ON child.id=granted.menu_id
JOIN sys_menu catalog ON catalog.permission='package:catalog'
WHERE child.permission='ai:package-adjustment:list'
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu existing
                  WHERE existing.role_id=granted.role_id AND existing.menu_id=catalog.id);
