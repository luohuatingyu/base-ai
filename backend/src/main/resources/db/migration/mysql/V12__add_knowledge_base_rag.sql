ALTER TABLE workflow_connection
    ADD COLUMN vector_status VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN' AFTER security_revision,
    ADD COLUMN vector_engine VARCHAR(32) NULL AFTER vector_status,
    ADD COLUMN vector_version VARCHAR(64) NULL AFTER vector_engine,
    ADD COLUMN vector_checked_at DATETIME(6) NULL AFTER vector_version,
    ADD COLUMN vector_error VARCHAR(500) NOT NULL DEFAULT '' AFTER vector_checked_at;

CREATE TABLE knowledge_base (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(80) NOT NULL,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(500) NOT NULL DEFAULT '',
    connection_id BIGINT NOT NULL,
    storage_type VARCHAR(24) NOT NULL,
    resource_mode VARCHAR(16) NOT NULL,
    resource_name VARCHAR(120) NOT NULL,
    embedding_model_id BIGINT NOT NULL,
    embedding_dimension INT NULL,
    distance_metric VARCHAR(16) NOT NULL DEFAULT 'COSINE',
    chunk_size INT NOT NULL DEFAULT 400,
    chunk_overlap INT NOT NULL DEFAULT 60,
    owner_user_id BIGINT NOT NULL,
    enabled BIT(1) NOT NULL DEFAULT b'1',
    voided BIT(1) NOT NULL DEFAULT b'0',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_knowledge_base_code UNIQUE (code),
    CONSTRAINT fk_knowledge_base_connection FOREIGN KEY (connection_id) REFERENCES workflow_connection(id),
    CONSTRAINT fk_knowledge_base_embedding_model FOREIGN KEY (embedding_model_id) REFERENCES sys_llm_model(id),
    INDEX idx_knowledge_base_owner (owner_user_id, voided, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE knowledge_document (
    id BIGINT NOT NULL AUTO_INCREMENT,
    knowledge_base_id BIGINT NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(120) NOT NULL DEFAULT 'application/octet-stream',
    content_hash CHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'INDEXING',
    chunk_count INT NOT NULL DEFAULT 0,
    error_message VARCHAR(500) NOT NULL DEFAULT '',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_knowledge_document_base FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_base(id),
    CONSTRAINT uk_knowledge_document_hash UNIQUE (knowledge_base_id, content_hash),
    INDEX idx_knowledge_document_base (knowledge_base_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE knowledge_chunk (
    id BIGINT NOT NULL AUTO_INCREMENT,
    knowledge_base_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    chunk_index INT NOT NULL,
    content_encrypted LONGTEXT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_knowledge_chunk_base FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_base(id),
    CONSTRAINT fk_knowledge_chunk_document FOREIGN KEY (document_id) REFERENCES knowledge_document(id),
    CONSTRAINT uk_knowledge_chunk_index UNIQUE (document_id, chunk_index),
    INDEX idx_knowledge_chunk_base (knowledge_base_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO workflow_node_template(code, name, node_type, description, config_encrypted, system_template,
    template_source, functional_category, enabled)
VALUES ('RAG', '知识库问答', 'RAG', '检索知识库上下文并调用文本模型生成带引用回答', '', b'1', 'SYSTEM', 'AI', b'1')
ON DUPLICATE KEY UPDATE code=VALUES(code);
