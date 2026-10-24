CREATE TABLE IF NOT EXISTS ai_novel (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    owner_user_id BIGINT NOT NULL,
    title VARCHAR(200) NOT NULL,
    logline VARCHAR(1000) NULL,
    genre VARCHAR(80) NULL,
    tone VARCHAR(120) NULL,
    language VARCHAR(40) NOT NULL DEFAULT 'zh-CN',
    outline LONGTEXT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    INDEX idx_ai_novel_owner_updated (owner_user_id, updated_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_novel_chapter (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    novel_id BIGINT NOT NULL,
    parent_id BIGINT NULL,
    chapter_no INT NOT NULL,
    title VARCHAR(200) NOT NULL,
    synopsis VARCHAR(1000) NULL,
    content LONGTEXT NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    word_count INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_ai_novel_chapter_order (novel_id, chapter_no),
    INDEX idx_ai_novel_chapter_parent (novel_id, parent_id, chapter_no),
    CONSTRAINT fk_ai_novel_chapter_novel FOREIGN KEY (novel_id) REFERENCES ai_novel(id) ON DELETE CASCADE,
    CONSTRAINT fk_ai_novel_chapter_parent FOREIGN KEY (parent_id) REFERENCES ai_novel_chapter(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_novel_reference (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    novel_id BIGINT NOT NULL,
    reference_type VARCHAR(24) NOT NULL,
    name VARCHAR(200) NOT NULL,
    content LONGTEXT NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    INDEX idx_ai_novel_reference_novel (novel_id, reference_type, sort_order),
    CONSTRAINT fk_ai_novel_reference_novel FOREIGN KEY (novel_id) REFERENCES ai_novel(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE ai_video_script
    ADD COLUMN source_novel_id BIGINT NULL AFTER project_id,
    ADD COLUMN source_chapter_ids JSON NULL AFTER source_novel_id,
    ADD COLUMN source_type VARCHAR(24) NOT NULL DEFAULT 'MANUAL' AFTER source_chapter_ids,
    ADD INDEX idx_ai_video_script_source_novel (source_novel_id),
    ADD CONSTRAINT fk_ai_video_script_source_novel FOREIGN KEY (source_novel_id) REFERENCES ai_novel(id) ON DELETE SET NULL;
