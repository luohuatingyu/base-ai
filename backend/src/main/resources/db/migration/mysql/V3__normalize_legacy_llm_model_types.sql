UPDATE sys_llm_model
SET model_type = CASE LOWER(TRIM(model_type))
    WHEN 'text' THEN 'text_model'
    WHEN 'vision' THEN 'vision_model'
END
WHERE LOWER(TRIM(model_type)) IN ('text', 'vision');
