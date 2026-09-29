-- 旧模型自动识别官方标识，自定义接入点可显式配置能力画像。
ALTER TABLE sys_llm_model ADD COLUMN generation_profile VARCHAR(80) NULL DEFAULT '';

-- 生成页使用独立菜单权限；保留原聊天权限以及历史任务数据。
INSERT INTO sys_menu(parent_id,name,type,path,component,icon,permission,sort_order,visible,enabled,created_at,updated_at)
SELECT id,'AI 生成','MENU','/ai-generation','AiGenerationView','Picture','ai:generation:invoke',13,TRUE,TRUE,NOW(),NOW()
FROM sys_menu WHERE permission='ai:catalog'
AND NOT EXISTS (SELECT 1 FROM sys_menu existing WHERE existing.permission='ai:generation:invoke');

-- 升级前有生成访问权的角色保持原有能力，之后可单独调整两项授权。
INSERT INTO sys_role_menu(role_id,menu_id)
SELECT DISTINCT granted.role_id,generation.id FROM sys_role_menu granted
JOIN sys_menu chat ON chat.id=granted.menu_id AND chat.permission='ai:chat:invoke'
JOIN sys_menu generation ON generation.permission='ai:generation:invoke'
WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu existing WHERE existing.role_id=granted.role_id AND existing.menu_id=generation.id);
