-- 供应商地址按文本、图片和视频操作分开配置；旧数据默认两级同步。
ALTER TABLE sys_llm_provider
    MODIFY COLUMN base_url VARCHAR(500) NULL,
    ADD COLUMN image_url VARCHAR(500) NULL,
    ADD COLUMN video_url VARCHAR(500) NULL,
    ADD COLUMN image_same_as_base BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN video_same_as_image BOOLEAN NOT NULL DEFAULT TRUE;
