-- 将套餐管理目录放到自动化目录之后，避免修改已执行的 V49 迁移。
UPDATE sys_menu
SET sort_order=50
WHERE permission='package:catalog';
