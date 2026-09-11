INSERT INTO sys_menu(parent_id,name,type,path,component,icon,permission,sort_order,visible,enabled,created_at,updated_at)
SELECT page.id,'SSH 终端','BUTTON',NULL,NULL,NULL,'operations:server:shell',138,FALSE,TRUE,
       CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)
FROM sys_menu page
WHERE page.permission='operations:server:list'
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission='operations:server:shell');

INSERT INTO sys_role_menu(role_id,menu_id)
SELECT role.id,menu.id FROM sys_role role JOIN sys_menu menu ON menu.permission='operations:server:shell'
WHERE role.code='ADMIN'
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu existing WHERE existing.role_id=role.id AND existing.menu_id=menu.id);
