ALTER TABLE workflow_node_run
    ADD COLUMN default_node_name VARCHAR(120) NOT NULL DEFAULT '' AFTER node_name;

ALTER TABLE workflow_node_run
    ADD COLUMN localization_json LONGTEXT NULL AFTER default_node_name;

UPDATE workflow_node_run SET localization_json='{}' WHERE localization_json IS NULL;

ALTER TABLE workflow_node_run
    MODIFY COLUMN localization_json LONGTEXT NOT NULL;
