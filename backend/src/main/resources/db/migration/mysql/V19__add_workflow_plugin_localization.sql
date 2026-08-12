ALTER TABLE workflow_marketplace_component
    ADD COLUMN localization_json LONGTEXT NULL AFTER description;

UPDATE workflow_marketplace_component SET localization_json='{}' WHERE localization_json IS NULL;

ALTER TABLE workflow_marketplace_component
    MODIFY COLUMN localization_json LONGTEXT NOT NULL;

ALTER TABLE workflow_node_template
    ADD COLUMN localization_json LONGTEXT NULL AFTER description;

UPDATE workflow_node_template SET localization_json='{}' WHERE localization_json IS NULL;

ALTER TABLE workflow_node_template
    MODIFY COLUMN localization_json LONGTEXT NOT NULL;
