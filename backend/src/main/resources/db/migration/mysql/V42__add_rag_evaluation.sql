CREATE TABLE IF NOT EXISTS rag_evaluation_dataset (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(120) NOT NULL, description VARCHAR(500), owner_user_id BIGINT NOT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 INDEX idx_rag_eval_owner (owner_user_id)
);
CREATE TABLE IF NOT EXISTS rag_evaluation_item (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, dataset_id BIGINT NOT NULL, question TEXT NOT NULL, expected_answer TEXT, expected_sources JSON,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, INDEX idx_rag_eval_item_dataset (dataset_id)
);
CREATE TABLE IF NOT EXISTS rag_evaluation_run (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, dataset_id BIGINT NOT NULL, knowledge_base_id BIGINT NOT NULL, status VARCHAR(20) NOT NULL,
 total_count INT NOT NULL DEFAULT 0, completed_count INT NOT NULL DEFAULT 0, recall_at_k DECIMAL(10,4), mrr DECIMAL(10,4), citation_hit_rate DECIMAL(10,4),
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, completed_at DATETIME NULL, INDEX idx_rag_eval_run_dataset (dataset_id)
);
CREATE TABLE IF NOT EXISTS rag_evaluation_result (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, run_id BIGINT NOT NULL, item_id BIGINT NOT NULL, answer TEXT, citations JSON, recall_score DECIMAL(10,4), mrr_score DECIMAL(10,4), citation_score DECIMAL(10,4), error_message VARCHAR(500),
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, INDEX idx_rag_eval_result_run (run_id)
);
