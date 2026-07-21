-- Test Data for H2 Database
-- BCrypt hash for 'test123': $2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy

-- Insert test departments
INSERT INTO sys_department (id, parent_id, name, code, sort_order, enabled) VALUES
(1, NULL, 'Test Department', 'TEST_DEPT', 1, TRUE),
(2, 1, 'Test Sub Department', 'TEST_SUB_DEPT', 1, TRUE);

-- Insert test positions
INSERT INTO sys_position (id, name, code, sort_order, enabled) VALUES
(1, 'Test Position', 'TEST_POS', 1, TRUE),
(2, 'Manager', 'MANAGER', 2, TRUE);

-- Insert test roles
INSERT INTO sys_role (id, code, name, enabled, sort_order) VALUES
(1, 'ADMIN', 'Administrator', TRUE, 1),
(2, 'USER', 'Regular User', TRUE, 2),
(3, 'GUEST', 'Guest User', TRUE, 3);

-- Insert test menus
INSERT INTO sys_menu (id, parent_id, name, type, path, component, icon, permission, sort_order, visible, enabled) VALUES
(1, NULL, 'Dashboard', 'MENU', '/dashboard', 'Dashboard', 'dashboard', 'view:dashboard', 1, TRUE, TRUE),
(2, NULL, 'System', 'MENU', '/system', 'System', 'system', NULL, 2, TRUE, TRUE),
(3, 2, 'User Management', 'MENU', '/system/users', 'UserManagement', 'user', 'system:user:list', 1, TRUE, TRUE),
(4, 2, 'Role Management', 'MENU', '/system/roles', 'RoleManagement', 'role', 'system:role:list', 2, TRUE, TRUE);

-- Insert test users
INSERT INTO sys_user (id, username, display_name, password_hash, enabled, department_id) VALUES
(1, 'admin', 'Test Administrator', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', TRUE, 1),
(2, 'testuser', 'Test User', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', TRUE, 1),
(3, 'disabled', 'Disabled User', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', FALSE, 2);

-- Assign roles to users
INSERT INTO sys_user_role (user_id, role_id) VALUES
(1, 1),  -- admin has ADMIN role
(2, 2),  -- testuser has USER role
(3, 3);  -- disabled has GUEST role

-- Assign positions to users
INSERT INTO sys_user_position (user_id, position_id) VALUES
(1, 2),  -- admin is Manager
(2, 1);  -- testuser has Test Position

-- Assign menus to roles
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1, 1), (1, 2), (1, 3), (1, 4),  -- ADMIN has all menus
(2, 1), (2, 3),                   -- USER has Dashboard and User Management
(3, 1);                           -- GUEST has only Dashboard

-- Insert test dictionary types
INSERT INTO sys_dictionary_type (id, name, code, enabled, sort_order) VALUES
(1, 'Test Type', 'TEST_TYPE', TRUE, 1),
(2, 'Gender', 'GENDER', TRUE, 2);

-- Insert test dictionary data
INSERT INTO sys_dictionary_data (id, type_id, label, value, sort_order, enabled) VALUES
(1, 1, 'Test Value 1', 'VALUE1', 1, TRUE),
(2, 1, 'Test Value 2', 'VALUE2', 2, TRUE),
(3, 2, 'Male', 'M', 1, TRUE),
(4, 2, 'Female', 'F', 2, TRUE);

-- Insert test system settings
INSERT INTO sys_setting (setting_key, setting_value, description) VALUES
('system.name', 'Test System', 'System name for testing'),
('system.version', '1.0.0', 'System version');

-- Insert test LLM providers
INSERT INTO llm_provider (id, name, code, base_url, api_key_encrypted, enabled, sort_order) VALUES
(1, 'Test Provider', 'TEST_PROVIDER', 'http://localhost:8000', 'encrypted_key_123', TRUE, 1),
(2, 'OpenAI', 'OPENAI', 'https://api.openai.com', 'encrypted_openai_key', TRUE, 2);

-- Insert test LLM models
INSERT INTO llm_model (id, provider_id, name, model_name, model_type, enabled, sort_order) VALUES
(1, 1, 'Test Model', 'test-model-v1', 'CHAT', TRUE, 1),
(2, 2, 'GPT-4', 'gpt-4', 'CHAT', TRUE, 2);

-- Insert test LLM routes
INSERT INTO llm_route (id, name, model_id, weight, enabled) VALUES
(1, 'Default Route', 1, 100, TRUE),
(2, 'Premium Route', 2, 80, TRUE);

-- Insert test login logs
INSERT INTO sys_login_log (id, username, ip_address, user_agent, success, message) VALUES
(1, 'admin', '127.0.0.1', 'Test Browser', TRUE, 'Login successful'),
(2, 'testuser', '192.168.1.100', 'Mozilla/5.0', TRUE, 'Login successful'),
(3, 'unknown', '10.0.0.1', 'Unknown', FALSE, 'User not found');
