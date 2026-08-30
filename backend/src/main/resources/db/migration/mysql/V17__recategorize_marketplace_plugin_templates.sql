UPDATE workflow_node_template
SET functional_category = CASE
    WHEN node_type = 'PLUGIN_TRIGGER' THEN 'TRIGGER'
    WHEN node_type IN ('PLUGIN_MODEL', 'PLUGIN_AGENT_STRATEGY') THEN 'AI'
    WHEN node_type = 'PLUGIN_DATASOURCE' THEN 'DATA_STORAGE'
    WHEN LOWER(CONCAT_WS(' ', external_key, name, description, external_publisher))
        REGEXP 'kafka|rabbitmq|rabbit mq|mqtt|pulsar|message queue|event bus|pubsub|pub sub|sqs|消息队列'
        THEN 'MESSAGE_QUEUE'
    WHEN LOWER(CONCAT_WS(' ', external_key, name, description, external_publisher))
        REGEXP 'slack|discord|telegram|whatsapp|microsoft teams|twilio|email|e mail|sms|notification|notify|mailgun|sendgrid|通知|邮件|短信|即时消息'
        THEN 'NOTIFICATION'
    WHEN LOWER(CONCAT_WS(' ', external_key, name, description, external_publisher))
        REGEXP 'database|postgres|postgresql|mysql|mariadb|mongodb|redis|elasticsearch|opensearch|snowflake|clickhouse|supabase|dynamodb|bigquery|data warehouse|object storage|vector store|pinecone|qdrant|milvus|weaviate|chroma|(^|[^a-z0-9])s3([^a-z0-9]|$)|(^|[^a-z0-9])sql([^a-z0-9]|$)|数据库|缓存|数据仓库|对象存储|向量库'
        THEN 'DATA_STORAGE'
    WHEN LOWER(CONCAT_WS(' ', external_key, name, description, external_publisher))
        REGEXP 'document|pdf|docx|markdown|ocr|document extractor|file extractor|document parser|text extractor|knowledge base|文档|文本提取|文件解析|知识库'
        THEN 'TEXT_DOCUMENT'
    WHEN LOWER(CONCAT_WS(' ', external_key, name, description, external_publisher))
        REGEXP 'data transform|transformer|converter|convert|formatter|json|xml|yaml|csv|parser|encode|decode|serialize|deserialize|aggregate|数据转换|格式转换|编码|解码'
        THEN 'DATA_TRANSFORM'
    WHEN LOWER(CONCAT_WS(' ', external_key, name, description, external_publisher))
        REGEXP 'openai|anthropic|gemini|cohere|huggingface|ollama|large language model|(^|[^a-z0-9])llm([^a-z0-9]|$)|embedding|rerank|vision model|speech model|ai assistant|人工智能|大语言模型|嵌入模型|重排序'
        THEN 'AI'
    WHEN LOWER(CONCAT_WS(' ', external_key, name, description, external_publisher))
        REGEXP 'http|(^|[^a-z0-9])api([^a-z0-9]|$)|webhook|web search|search engine|scraper|scrape|crawler|crawl|browser|fetch|(^|[^a-z0-9])url([^a-z0-9]|$)|tavily|serpapi|network request|网络请求|网页搜索|网页抓取|接口调用'
        THEN 'NETWORK_API'
    ELSE 'BASIC'
END
WHERE template_source IN ('N8N', 'DIFY')
  AND functional_category = 'NETWORK_API'
  AND node_type IN ('PLUGIN_ACTION', 'PLUGIN_TRIGGER', 'PLUGIN_MODEL', 'PLUGIN_DATASOURCE',
                    'PLUGIN_AGENT_STRATEGY', 'PLUGIN_EXTENSION');
