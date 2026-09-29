CREATE TABLE IF NOT EXISTS ai_video_project (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(80) NOT NULL,
    name VARCHAR(160) NOT NULL,
    description VARCHAR(1000) NULL,
    cover_url VARCHAR(500) NULL,
    aspect_ratio VARCHAR(20) NOT NULL DEFAULT '16:9',
    resolution VARCHAR(20) NOT NULL DEFAULT '1920x1080',
    fps INT NOT NULL DEFAULT 25,
    style VARCHAR(120) NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    owner_user_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_ai_video_project_owner_code (owner_user_id, code),
    INDEX idx_ai_video_project_owner_status (owner_user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_video_script (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    title VARCHAR(200) NOT NULL,
    content LONGTEXT NOT NULL,
    version INT NOT NULL DEFAULT 1,
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    owner_user_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    INDEX idx_ai_video_script_project (project_id, updated_at),
    CONSTRAINT fk_ai_video_script_project FOREIGN KEY (project_id) REFERENCES ai_video_project(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_video_storyboard (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    script_id BIGINT NULL,
    shot_no INT NOT NULL,
    title VARCHAR(200) NOT NULL,
    scene_description VARCHAR(2000) NULL,
    dialogue TEXT NULL,
    narration TEXT NULL,
    camera VARCHAR(120) NULL,
    duration_ms INT NOT NULL DEFAULT 5000,
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    owner_user_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    INDEX idx_ai_video_storyboard_project (project_id, shot_no),
    CONSTRAINT fk_ai_video_storyboard_project FOREIGN KEY (project_id) REFERENCES ai_video_project(id) ON DELETE CASCADE,
    CONSTRAINT fk_ai_video_storyboard_script FOREIGN KEY (script_id) REFERENCES ai_video_script(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_video_asset (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    asset_type VARCHAR(32) NOT NULL,
    name VARCHAR(200) NOT NULL,
    description VARCHAR(1000) NULL,
    preview_url VARCHAR(500) NULL,
    resource_url VARCHAR(500) NULL,
    metadata_json JSON NULL,
    tags VARCHAR(500) NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    owner_user_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    INDEX idx_ai_video_asset_project_type (project_id, asset_type),
    CONSTRAINT fk_ai_video_asset_project FOREIGN KEY (project_id) REFERENCES ai_video_project(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_video_task (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    task_type VARCHAR(32) NOT NULL,
    name VARCHAR(200) NOT NULL,
    prompt TEXT NULL,
    provider_route_id BIGINT NULL,
    input_json JSON NULL,
    output_json JSON NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
    progress INT NOT NULL DEFAULT 0,
    error_message VARCHAR(1000) NULL,
    requested_by BIGINT NOT NULL,
    started_at DATETIME(6) NULL,
    finished_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    INDEX idx_ai_video_task_project_status (project_id, status, updated_at),
    CONSTRAINT fk_ai_video_task_project FOREIGN KEY (project_id) REFERENCES ai_video_project(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_video_render (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    name VARCHAR(200) NOT NULL,
    config_json JSON NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
    progress INT NOT NULL DEFAULT 0,
    output_url VARCHAR(500) NULL,
    error_message VARCHAR(1000) NULL,
    requested_by BIGINT NOT NULL,
    started_at DATETIME(6) NULL,
    finished_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    INDEX idx_ai_video_render_project_status (project_id, status, updated_at),
    CONSTRAINT fk_ai_video_render_project FOREIGN KEY (project_id) REFERENCES ai_video_project(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_video_video (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    render_id BIGINT NULL,
    title VARCHAR(200) NOT NULL,
    version INT NOT NULL DEFAULT 1,
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    media_url VARCHAR(500) NULL,
    cover_url VARCHAR(500) NULL,
    duration_ms INT NULL,
    description VARCHAR(1000) NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    INDEX idx_ai_video_video_project_status (project_id, status, updated_at),
    CONSTRAINT fk_ai_video_video_project FOREIGN KEY (project_id) REFERENCES ai_video_project(id) ON DELETE CASCADE,
    CONSTRAINT fk_ai_video_video_render FOREIGN KEY (render_id) REFERENCES ai_video_render(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_video_review (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    video_id BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    comment VARCHAR(1000) NULL,
    reviewer_user_id BIGINT NULL,
    reviewed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    INDEX idx_ai_video_review_video_status (video_id, status),
    CONSTRAINT fk_ai_video_review_video FOREIGN KEY (video_id) REFERENCES ai_video_video(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_video_publish (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    video_id BIGINT NOT NULL,
    channel VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    external_id VARCHAR(200) NULL,
    external_url VARCHAR(500) NULL,
    error_message VARCHAR(1000) NULL,
    requested_by BIGINT NOT NULL,
    published_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    INDEX idx_ai_video_publish_video_status (video_id, status),
    CONSTRAINT fk_ai_video_publish_video FOREIGN KEY (video_id) REFERENCES ai_video_video(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
