package com.baseai.platform.aivideo;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.security.AuthContext;
import com.baseai.platform.security.AuthUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 提供 AI 视频项目、内容、资产、任务和发布流程的统一业务服务。 */
@Service
public class AiVideoService {
    private static final Set<String> RESOURCES = Set.of("projects", "scripts", "storyboards", "assets", "tasks", "renders", "videos", "reviews", "publishes");
    private static final Set<String> ASSET_TYPES = Set.of("CHARACTER", "SCENE", "ITEM", "COSTUME", "MOTION", "STYLE", "VOICE", "SOUND");
    private static final Set<String> PROJECT_STATUSES = Set.of("DRAFT", "ACTIVE", "ARCHIVED");
    private static final Set<String> TASK_STATUSES = Set.of("QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED");
    private static final Set<String> REVIEW_STATUSES = Set.of("PENDING", "APPROVED", "REJECTED");
    private static final Set<String> PUBLISH_STATUSES = Set.of("DRAFT", "PUBLISHING", "PUBLISHED", "FAILED");
    private static final Set<String> VIDEO_STATUSES = Set.of("DRAFT", "PREVIEW", "REVIEW", "PUBLISHED");
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    /** 注入系统 MySQL 查询模板。 */
    public AiVideoService(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
        this(jdbc, new ObjectMapper());
    }

    /** 注入 JSON 编解码器，用于校验媒体生成任务的结构化输出。 */
    public AiVideoService(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** 返回当前用户可见的工作台统计。 */
    @Transactional(readOnly = true)
    public Map<String, Object> overview() {
        AuthUser user = AuthContext.require();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projects", count("ai_video_project", "owner_user_id", user));
        result.put("assets", count("ai_video_asset", "owner_user_id", user));
        result.put("tasks", count("ai_video_task", "requested_by", user));
        result.put("videos", count("ai_video_video", "created_by", user));
        result.put("runningTasks", scalar("SELECT COUNT(*) FROM ai_video_task t WHERE t.status IN ('QUEUED','RUNNING')" + ownerClause("t.requested_by", user), user));
        result.put("failedTasks", scalar("SELECT COUNT(*) FROM ai_video_task t WHERE t.status='FAILED'" + ownerClause("t.requested_by", user), user));
        return result;
    }

    /** 按资源和动作校验工作台权限，兼容现有菜单生成的细粒度权限。 */
    public void requirePermission(String resource, String action) {
        requirePermission(resource, action, null, null);
    }

    /** 按资源、资产分类和资源编号校验细粒度权限。 */
    public void requirePermission(String resource, String action, String assetType, Long id) {
        String normalized = resource(resource);
        String permission = switch (normalized) {
            case "projects" -> "ai:video:project:" + action;
            case "scripts" -> "ai:video:script:" + action;
            case "storyboards" -> "ai:video:storyboard:" + action;
            case "assets" -> assetPermission(assetType, action, id);
            case "tasks" -> "ai:video:task:" + action;
            case "renders" -> "ai:video:render:" + action;
            case "videos" -> "ai:video:video:" + action;
            case "reviews" -> "ai:video:review:" + action;
            case "publishes" -> "ai:video:publish:" + action;
            default -> "ai:video:dashboard:list";
        };
        if (!AuthContext.require().hasPermission(permission)) throw BusinessException.forbidden("auth.permissionDenied");
    }

    /** 将资产资源映射到角色、场景、物品等独立权限前缀。 */
    private String assetPermission(String assetType, String action, Long id) {
        String type = assetType;
        if ((type == null || type.isBlank()) && id != null) type = jdbc.queryForObject("SELECT asset_type FROM ai_video_asset WHERE id=?", String.class, id);
        if (type == null || type.isBlank()) return "ai:video:asset:catalog";
        String prefix = switch (type.toUpperCase()) {
            case "CHARACTER" -> "character";
            case "SCENE" -> "scene";
            case "ITEM" -> "item";
            case "COSTUME" -> "costume";
            case "MOTION" -> "motion";
            case "STYLE" -> "style";
            case "VOICE" -> "voice";
            case "SOUND" -> "sound";
            default -> throw new BusinessException("aiVideo.assetTypeInvalid");
        };
        return "ai:video:" + prefix + ":" + action;
    }

    /** 查询资源列表；资源名称来自固定白名单，避免动态 SQL 注入。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String resource, Long projectId, String assetType) {
        String normalized = resource(resource);
        AuthUser user = AuthContext.require();
        List<Object> args = new ArrayList<>();
        String sql = switch (normalized) {
            case "projects" -> "SELECT * FROM ai_video_project p WHERE 1=1" + ownerClause("p.owner_user_id", user);
            case "scripts" -> childSql("ai_video_script", "s", user);
            case "storyboards" -> childSql("ai_video_storyboard", "s", user);
            case "assets" -> childSql("ai_video_asset", "a", user);
            case "tasks" -> childSql("ai_video_task", "t", user);
            case "renders" -> childSql("ai_video_render", "r", user);
            case "videos" -> childSql("ai_video_video", "v", user);
            case "reviews" -> reviewSql(user, projectId, args);
            case "publishes" -> publishSql(user, projectId, args);
            default -> throw new BusinessException("aiVideo.resourceInvalid");
        };
        if (projectId != null && !normalized.equals("projects") && !normalized.equals("reviews") && !normalized.equals("publishes")) {
            sql += " AND " + columnAlias(normalized) + ".project_id=?";
            args.add(projectId);
        }
        if (normalized.equals("assets") && assetType != null && !assetType.isBlank()) {
            String type = enumValue(assetType, ASSET_TYPES, "aiVideo.assetTypeInvalid");
            sql += " AND a.asset_type=?";
            args.add(type);
        }
        sql += " ORDER BY " + orderAlias(normalized) + ".updated_at DESC," + orderAlias(normalized) + ".id DESC";
        return jdbc.query(sql, (rs, row) -> row(rs), args.toArray());
    }

    /** 创建指定类型的工作台资源。 */
    @Transactional
    public Map<String, Object> create(String resource, Map<String, Object> payload, String assetType) {
        String normalized = resource(resource);
        AuthUser user = AuthContext.require();
        return switch (normalized) {
            case "projects" -> insertProject(payload, user);
            case "scripts" -> insertScript(payload, user);
            case "storyboards" -> insertStoryboard(payload, user);
            case "assets" -> insertAsset(payload, assetType, user);
            case "tasks" -> insertTask(payload, user);
            case "renders" -> insertRender(payload, user);
            case "videos" -> insertVideo(payload, user);
            case "reviews" -> insertReview(payload, user);
            case "publishes" -> insertPublish(payload, user);
            default -> throw new BusinessException("aiVideo.resourceInvalid");
        };
    }

    /** 将已完成的视频生成任务安全回填为项目成片，保留项目和分镜来源。 */
    @Transactional
    public Map<String, Object> promoteGeneration(Map<String, Object> payload) {
        AuthUser user = AuthContext.require();
        long generationTaskId = requiredLong(payload, "generationTaskId");
        long projectId = requiredLong(payload, "projectId");
        requireOwned("projects", projectId);
        Map<String, Object> task = generationTask(generationTaskId, user.id());
        if (!"SUCCEEDED".equals(task.get("status")) || !"video_model".equals(task.get("generationType"))) {
            throw new BusinessException("aiVideo.generationNotSucceeded");
        }
        String mediaUrl = mediaUrl(payload, "mediaUrl");
        if (!outputContainsUrl(task.get("outputJson"), mediaUrl)) throw new BusinessException("aiVideo.generationOutputNotFound");
        Long storyboardId = optionalLong(payload, "storyboardId");
        if (storyboardId != null) requireStoryboard(projectId, storyboardId);
        Integer duplicate = jdbc.queryForObject("SELECT COUNT(*) FROM ai_video_video WHERE project_id=? AND media_url=?", Integer.class, projectId, mediaUrl);
        if (duplicate != null && duplicate > 0) throw new BusinessException("aiVideo.generationAlreadyLinked");
        String source = "AI generation task #" + generationTaskId + (storyboardId == null ? "" : ", storyboard #" + storyboardId);
        String prompt = text(task, "prompt", 800);
        String description = source + (prompt == null ? "" : "\n" + prompt);
        Map<String, Object> video = new LinkedHashMap<>();
        video.put("projectId", projectId);
        video.put("title", required(payload, "title", 200));
        video.put("version", integer(payload, "version", 1, 1, 10000));
        video.put("status", value(payload, "status", "DRAFT"));
        video.put("mediaUrl", mediaUrl);
        video.put("coverUrl", text(payload, "coverUrl", 500));
        video.put("durationMs", integer(payload, "durationMs", 0, 0, 36000000));
        video.put("description", description.length() > 1000 ? description.substring(0, 1000) : description);
        return insertVideo(video, user);
    }

    /** 更新指定类型的工作台资源。 */
    @Transactional
    public Map<String, Object> update(String resource, Long id, Map<String, Object> payload) {
        String normalized = resource(resource);
        requireOwned(normalized, id);
        switch (normalized) {
            case "projects" -> jdbc.update("UPDATE ai_video_project SET code=?,name=?,description=?,cover_url=?,aspect_ratio=?,resolution=?,fps=?,style=?,status=?,updated_at=NOW(6) WHERE id=?",
                text(payload, "code", 80), required(payload, "name", 160), text(payload, "description", 1000), text(payload, "coverUrl", 500), value(payload, "aspectRatio", "16:9"), value(payload, "resolution", "1920x1080"), integer(payload, "fps", 25, 1, 120), text(payload, "style", 120), enumValue(value(payload, "status", "DRAFT"), PROJECT_STATUSES, "aiVideo.projectStatusInvalid"), id);
            case "scripts" -> jdbc.update("UPDATE ai_video_script SET title=?,content=?,version=?,status=?,updated_at=NOW(6) WHERE id=?",
                required(payload, "title", 200), value(payload, "content", ""), integer(payload, "version", 1, 1, 10000), value(payload, "status", "DRAFT"), id);
            case "storyboards" -> jdbc.update("UPDATE ai_video_storyboard SET shot_no=?,title=?,scene_description=?,dialogue=?,narration=?,camera=?,duration_ms=?,status=?,updated_at=NOW(6) WHERE id=?",
                integer(payload, "shotNo", 1, 1, 100000), required(payload, "title", 200), text(payload, "sceneDescription", 2000), text(payload, "dialogue", 5000), text(payload, "narration", 5000), text(payload, "camera", 120), integer(payload, "durationMs", 5000, 100, 3600000), value(payload, "status", "DRAFT"), id);
            case "assets" -> jdbc.update("UPDATE ai_video_asset SET name=?,description=?,preview_url=?,resource_url=?,metadata_json=?,tags=?,status=?,updated_at=NOW(6) WHERE id=?",
                required(payload, "name", 200), text(payload, "description", 1000), text(payload, "previewUrl", 500), text(payload, "resourceUrl", 500), text(payload, "metadataJson", 20000), text(payload, "tags", 500), value(payload, "status", "DRAFT"), id);
            case "tasks" -> jdbc.update("UPDATE ai_video_task SET name=?,prompt=?,provider_route_id=?,input_json=?,updated_at=NOW(6) WHERE id=? AND status IN ('QUEUED','FAILED','CANCELLED')",
                required(payload, "name", 200), text(payload, "prompt", 10000), longValue(payload, "providerRouteId"), text(payload, "inputJson", 20000), id);
            case "renders" -> jdbc.update("UPDATE ai_video_render SET name=?,config_json=?,updated_at=NOW(6) WHERE id=? AND status IN ('QUEUED','FAILED','CANCELLED')",
                required(payload, "name", 200), text(payload, "configJson", 20000), id);
            case "videos" -> updateVideo(id, payload);
            case "reviews" -> jdbc.update("UPDATE ai_video_review SET status=?,comment=?,reviewer_user_id=?,reviewed_at=IF(? IN ('APPROVED','REJECTED'),NOW(6),NULL),updated_at=NOW(6) WHERE id=?",
                enumValue(value(payload, "status", "PENDING"), REVIEW_STATUSES, "aiVideo.reviewStatusInvalid"), text(payload, "comment", 1000), user().id(), value(payload, "status", "PENDING"), id);
            case "publishes" -> updatePublish(id, payload);
            default -> throw new BusinessException("aiVideo.resourceInvalid");
        }
        return find(normalized, id);
    }

    /** 删除指定资源；父项目删除时由数据库级联清理子资源。 */
    @Transactional
    public void delete(String resource, Long id) {
        String normalized = resource(resource);
        requireOwned(normalized, id);
        jdbc.update("DELETE FROM " + table(normalized) + " WHERE id=?", id);
    }

    /** 取消排队或运行中的生成任务。 */
    @Transactional
    public Map<String, Object> cancelTask(Long id) {
        requireOwned("tasks", id);
        jdbc.update("UPDATE ai_video_task SET status='CANCELLED',error_message='',updated_at=NOW(6) WHERE id=? AND status IN ('QUEUED','RUNNING')", id);
        return find("tasks", id);
    }

    /** 将失败或取消的任务重新放回队列，保留输入参数便于追踪。 */
    @Transactional
    public Map<String, Object> retryTask(Long id) {
        requireOwned("tasks", id);
        jdbc.update("UPDATE ai_video_task SET status='QUEUED',progress=0,error_message='',started_at=NULL,finished_at=NULL,updated_at=NOW(6) WHERE id=? AND status IN ('FAILED','CANCELLED')", id);
        return find("tasks", id);
    }

    /** 提交成片审核结果。 */
    @Transactional
    public Map<String, Object> reviewVideo(Long videoId, Map<String, Object> payload) {
        requireOwned("videos", videoId);
        requireVideoMedia(videoId);
        Map<String, Object> command = new LinkedHashMap<>(payload == null ? Map.of() : payload);
        command.put("videoId", videoId);
        Long existing = jdbc.query("SELECT id FROM ai_video_review WHERE video_id=? ORDER BY id DESC LIMIT 1",
            rs -> rs.next() ? rs.getLong(1) : null, videoId);
        Map<String, Object> result = existing == null ? insertReview(command, user()) : update("reviews", existing, command);
        jdbc.update("UPDATE ai_video_video SET status='REVIEW',updated_at=NOW(6) WHERE id=? AND status<>'PUBLISHED'", videoId);
        return result;
    }

    /** 将审核通过的成片标记为已发布，保留渠道和外部编号。 */
    @Transactional
    public Map<String, Object> markPublished(Long id) {
        requireOwned("publishes", id);
        Long review = jdbc.queryForObject("SELECT COUNT(*) FROM ai_video_publish x JOIN ai_video_video v ON v.id=x.video_id JOIN ai_video_review r ON r.video_id=v.id WHERE x.id=? AND r.status='APPROVED'", Long.class, id);
        if (review == null || review == 0) throw new BusinessException("aiVideo.reviewRequired");
        jdbc.update("UPDATE ai_video_publish SET status='PUBLISHED',published_at=NOW(6),updated_at=NOW(6) WHERE id=?", id);
        jdbc.update("UPDATE ai_video_video v JOIN ai_video_publish p ON p.video_id=v.id SET v.status='PUBLISHED',v.updated_at=NOW(6) WHERE p.id=?", id);
        return find("publishes", id);
    }

    private Map<String, Object> insertProject(Map<String, Object> p, AuthUser user) {
        return insert("INSERT INTO ai_video_project(code,name,description,cover_url,aspect_ratio,resolution,fps,style,status,owner_user_id) VALUES (?,?,?,?,?,?,?,?,?,?)",
            new Object[]{required(p, "code", 80), required(p, "name", 160), text(p, "description", 1000), text(p, "coverUrl", 500), value(p, "aspectRatio", "16:9"), value(p, "resolution", "1920x1080"), integer(p, "fps", 25, 1, 120), text(p, "style", 120), enumValue(value(p, "status", "DRAFT"), PROJECT_STATUSES, "aiVideo.projectStatusInvalid"), user.id()}, "projects");
    }

    private Map<String, Object> insertScript(Map<String, Object> p, AuthUser user) {
        long projectId = projectId(p); return insert("INSERT INTO ai_video_script(project_id,title,content,version,status,owner_user_id) VALUES (?,?,?,?,?,?)",
            new Object[]{projectId, required(p, "title", 200), value(p, "content", ""), integer(p, "version", 1, 1, 10000), value(p, "status", "DRAFT"), user.id()}, "scripts");
    }

    private Map<String, Object> insertStoryboard(Map<String, Object> p, AuthUser user) {
        long projectId = projectId(p); return insert("INSERT INTO ai_video_storyboard(project_id,script_id,shot_no,title,scene_description,dialogue,narration,camera,duration_ms,status,owner_user_id) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            new Object[]{projectId, longValue(p, "scriptId"), integer(p, "shotNo", 1, 1, 100000), required(p, "title", 200), text(p, "sceneDescription", 2000), text(p, "dialogue", 5000), text(p, "narration", 5000), text(p, "camera", 120), integer(p, "durationMs", 5000, 100, 3600000), value(p, "status", "DRAFT"), user.id()}, "storyboards");
    }

    private Map<String, Object> insertAsset(Map<String, Object> p, String assetType, AuthUser user) {
        String type = enumValue(value(p, "assetType", assetType), ASSET_TYPES, "aiVideo.assetTypeInvalid"); long projectId = projectId(p);
        return insert("INSERT INTO ai_video_asset(project_id,asset_type,name,description,preview_url,resource_url,metadata_json,tags,status,owner_user_id) VALUES (?,?,?,?,?,?,?,?,?,?)",
            new Object[]{projectId, type, required(p, "name", 200), text(p, "description", 1000), text(p, "previewUrl", 500), text(p, "resourceUrl", 500), text(p, "metadataJson", 20000), text(p, "tags", 500), value(p, "status", "DRAFT"), user.id()}, "assets");
    }

    private Map<String, Object> insertTask(Map<String, Object> p, AuthUser user) {
        String type = value(p, "taskType", "VIDEO").toUpperCase(); if (!Set.of("SCRIPT", "IMAGE", "VIDEO", "TTS", "MUSIC", "UPSCALE").contains(type)) throw new BusinessException("aiVideo.taskTypeInvalid");
        long projectId = projectId(p); return insert("INSERT INTO ai_video_task(project_id,task_type,name,prompt,provider_route_id,input_json,status,progress,requested_by) VALUES (?,?,?,?,?,?,?,?,?)",
            new Object[]{projectId, type, required(p, "name", 200), text(p, "prompt", 10000), longValue(p, "providerRouteId"), text(p, "inputJson", 20000), "QUEUED", 0, user.id()}, "tasks");
    }

    private Map<String, Object> insertRender(Map<String, Object> p, AuthUser user) {
        long projectId = projectId(p); return insert("INSERT INTO ai_video_render(project_id,name,config_json,status,progress,requested_by) VALUES (?,?,?,?,?,?)",
            new Object[]{projectId, required(p, "name", 200), text(p, "configJson", 20000), "QUEUED", 0, user.id()}, "renders");
    }

    private Map<String, Object> insertVideo(Map<String, Object> p, AuthUser user) {
        long projectId = projectId(p);
        String media = text(p, "mediaUrl", 500);
        if (media != null) media = mediaUrl(Map.of("mediaUrl", media), "mediaUrl");
        return insert("INSERT INTO ai_video_video(project_id,render_id,title,version,status,media_url,cover_url,duration_ms,description,created_by) VALUES (?,?,?,?,?,?,?,?,?,?)",
            new Object[]{projectId, longValue(p, "renderId"), required(p, "title", 200), integer(p, "version", 1, 1, 10000), videoStatus(p, "status", "DRAFT"), media, text(p, "coverUrl", 500), integer(p, "durationMs", 0, 0, 36000000), text(p, "description", 1000), user.id()}, "videos");
    }

    private Map<String, Object> insertReview(Map<String, Object> p, AuthUser user) {
        long videoId = requiredLong(p, "videoId"); requireOwned("videos", videoId);
        return insert("INSERT INTO ai_video_review(video_id,status,comment,reviewer_user_id,reviewed_at) VALUES (?,?,?,?,IF(? IN ('APPROVED','REJECTED'),NOW(6),NULL))",
            new Object[]{videoId, enumValue(value(p, "status", "PENDING"), REVIEW_STATUSES, "aiVideo.reviewStatusInvalid"), text(p, "comment", 1000), user.id(), value(p, "status", "PENDING")}, "reviews");
    }

    private Map<String, Object> insertPublish(Map<String, Object> p, AuthUser user) {
        long videoId = requiredLong(p, "videoId"); requireOwned("videos", videoId);
        String status = publishStatus(p, "status", "DRAFT");
        if ("PUBLISHED".equals(status)) requireApprovedReview(videoId);
        return insert("INSERT INTO ai_video_publish(video_id,channel,status,external_id,external_url,error_message,requested_by,published_at) VALUES (?,?,?,?,?,?,?,IF(?='PUBLISHED',NOW(6),NULL))",
            new Object[]{videoId, required(p, "channel", 64), status, text(p, "externalId", 200), text(p, "externalUrl", 500), text(p, "errorMessage", 1000), user.id(), status}, "publishes");
    }

    /** 更新成片时校验状态和媒体地址，避免空成片进入审核或发布阶段。 */
    private int updateVideo(long id, Map<String, Object> payload) {
        String status = videoStatus(payload, "status", "DRAFT");
        String mediaUrl = text(payload, "mediaUrl", 500);
        if (mediaUrl != null) mediaUrl = mediaUrl(Map.of("mediaUrl", mediaUrl), "mediaUrl");
        if (Set.of("REVIEW", "PUBLISHED").contains(status) && (mediaUrl == null || mediaUrl.isBlank())) requireVideoMedia(id);
        return jdbc.update("UPDATE ai_video_video SET title=?,status=?,media_url=?,cover_url=?,duration_ms=?,description=?,updated_at=NOW(6) WHERE id=?",
            required(payload, "title", 200), status, mediaUrl, text(payload, "coverUrl", 500), integer(payload, "durationMs", 0, 0, 36000000), text(payload, "description", 1000), id);
    }

    /** 更新发布状态时复用审核约束，防止直接编辑绕过审核流程。 */
    private int updatePublish(long id, Map<String, Object> payload) {
        String status = publishStatus(payload, "status", "DRAFT");
        Long videoId = jdbc.queryForObject("SELECT video_id FROM ai_video_publish WHERE id=?", Long.class, id);
        if ("PUBLISHED".equals(status) && videoId != null) requireApprovedReview(videoId);
        return jdbc.update("UPDATE ai_video_publish SET channel=?,status=?,external_id=?,external_url=?,error_message=?,published_at=IF(?='PUBLISHED',NOW(6),NULL),updated_at=NOW(6) WHERE id=?",
            required(payload, "channel", 64), status, text(payload, "externalId", 200), text(payload, "externalUrl", 500), text(payload, "errorMessage", 1000), status, id);
    }

    /** 查询已完成的媒体生成任务，未知和越权任务使用相同的资源错误。 */
    private Map<String, Object> generationTask(long id, long ownerId) {
        try {
            return jdbc.queryForMap("SELECT generation_type generationType,status,output_json outputJson,prompt FROM ai_media_generation_task WHERE id=? AND owner_user_id=?", id, ownerId);
        } catch (org.springframework.dao.EmptyResultDataAccessException exception) {
            throw BusinessException.notFound("aiVideo.generationTaskNotFound");
        }
    }

    /** 确认分镜属于待回填的项目，防止跨项目写入来源信息。 */
    private void requireStoryboard(long projectId, long storyboardId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM ai_video_storyboard WHERE id=? AND project_id=?", Integer.class, storyboardId, projectId);
        if (count == null || count == 0) throw BusinessException.notFound("aiVideo.resourceNotFound");
    }

    /** 校验生成结果中确实存在前端请求回填的媒体地址，防止伪造任务结果。 */
    private boolean outputContainsUrl(Object outputJson, String mediaUrl) {
        if (outputJson == null) return false;
        try {
            JsonNode root = outputJson instanceof JsonNode node ? node : mapper.readTree(String.valueOf(outputJson));
            return containsUrl(root, mediaUrl);
        } catch (Exception exception) {
            return false;
        }
    }

    /** 递归读取供应商输出中的 value 和 OSS 备份地址。 */
    private boolean containsUrl(JsonNode node, String mediaUrl) {
        if (node == null || node.isNull()) return false;
        if (node.isTextual()) return mediaUrl.equals(node.asText());
        if (node.isArray()) { for (JsonNode item : node) if (containsUrl(item, mediaUrl)) return true; return false; }
        if (node.isObject()) { for (JsonNode item : node) if (containsUrl(item, mediaUrl)) return true; }
        return false;
    }

    /** 只允许可持久化的 HTTP(S) 媒体地址。 */
    private String mediaUrl(Map<String, Object> payload, String key) {
        String value = required(payload, key, 500);
        try {
            java.net.URI uri = java.net.URI.create(value);
            if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) throw new IllegalArgumentException();
        } catch (Exception exception) { throw new BusinessException("aiVideo.mediaUrlInvalid"); }
        return value;
    }

    /** 校验成片已有媒体地址。 */
    private void requireVideoMedia(long videoId) {
        String mediaUrl = jdbc.queryForObject("SELECT media_url FROM ai_video_video WHERE id=?", String.class, videoId);
        if (mediaUrl == null || mediaUrl.isBlank()) throw new BusinessException("aiVideo.mediaRequired");
    }

    /** 校验已通过审核后才允许完成发布。 */
    private void requireApprovedReview(long videoId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM ai_video_review WHERE video_id=? AND status='APPROVED'", Integer.class, videoId);
        if (count == null || count == 0) throw new BusinessException("aiVideo.reviewRequired");
    }

    /** 读取正数可选关联编号。 */
    private Long optionalLong(Map<String, Object> payload, String key) {
        Object value = payload == null ? null : payload.get(key);
        if (value == null || value.toString().isBlank()) return null;
        if (!(value instanceof Number) || ((Number) value).longValue() <= 0) throw new BusinessException("aiVideo.numberInvalid", key);
        return ((Number) value).longValue();
    }

    /** 校验成片状态枚举。 */
    private String videoStatus(Map<String, Object> payload, String key, String fallback) {
        return enumValue(value(payload, key, fallback), VIDEO_STATUSES, "aiVideo.videoStatusInvalid");
    }

    /** 校验发布状态枚举。 */
    private String publishStatus(Map<String, Object> payload, String key, String fallback) {
        return enumValue(value(payload, key, fallback), PUBLISH_STATUSES, "aiVideo.publishStatusInvalid");
    }

    private Map<String, Object> insert(String sql, Object[] values, String resource) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            return statement;
        }, keys);
        Number key = keys.getKey(); if (key == null) throw new BusinessException("aiVideo.saveFailed");
        return find(resource, key.longValue());
    }

    private Map<String, Object> find(String resource, long id) {
        String normalized = resource(resource); requireOwned(normalized, id);
        return jdbc.queryForObject("SELECT * FROM " + table(normalized) + " WHERE id=?", (rs, row) -> row(rs), id);
    }

    private void requireOwned(String resource, long id) {
        if (id <= 0) throw BusinessException.notFound("aiVideo.resourceNotFound");
        String normalized = resource(resource); AuthUser user = AuthContext.require(); String table = table(normalized);
        String sql = switch (normalized) {
            case "reviews", "publishes" -> "SELECT COUNT(*) FROM " + table + " x JOIN ai_video_video v ON v.id=x.video_id JOIN ai_video_project p ON p.id=v.project_id WHERE x.id=?" + ownerClause("p.owner_user_id", user);
            case "projects" -> "SELECT COUNT(*) FROM " + table + " x WHERE x.id=?" + ownerClause("x.owner_user_id", user);
            default -> "SELECT COUNT(*) FROM " + table + " x JOIN ai_video_project p ON p.id=x.project_id WHERE x.id=?" + ownerClause("p.owner_user_id", user);
        };
        Long count = jdbc.queryForObject(sql, Long.class, id); if (count == null || count == 0) throw BusinessException.notFound("aiVideo.resourceNotFound");
    }

    private String childSql(String table, String alias, AuthUser user) {
        return "SELECT " + alias + ".* FROM " + table + " " + alias + " JOIN ai_video_project p ON p.id=" + alias + ".project_id WHERE 1=1" + ownerClause("p.owner_user_id", user);
    }

    private String reviewSql(AuthUser user, Long projectId, List<Object> args) {
        String sql = "SELECT r.* FROM ai_video_review r JOIN ai_video_video v ON v.id=r.video_id JOIN ai_video_project p ON p.id=v.project_id WHERE 1=1" + ownerClause("p.owner_user_id", user);
        if (projectId != null) { sql += " AND v.project_id=?"; args.add(projectId); } return sql;
    }

    private String publishSql(AuthUser user, Long projectId, List<Object> args) {
        String sql = "SELECT x.* FROM ai_video_publish x JOIN ai_video_video v ON v.id=x.video_id JOIN ai_video_project p ON p.id=v.project_id WHERE 1=1" + ownerClause("p.owner_user_id", user);
        if (projectId != null) { sql += " AND v.project_id=?"; args.add(projectId); } return sql;
    }

    private long projectId(Map<String, Object> payload) { long id = requiredLong(payload, "projectId"); requireOwned("projects", id); return id; }
    private long requiredLong(Map<String, Object> p, String key) { Object value = p == null ? null : p.get(key); if (!(value instanceof Number) || ((Number) value).longValue() <= 0) throw new BusinessException("aiVideo.fieldRequired", key); return ((Number) value).longValue(); }
    private Long longValue(Map<String, Object> p, String key) { Object value = p == null ? null : p.get(key); return value instanceof Number ? ((Number) value).longValue() : null; }
    private int integer(Map<String, Object> p, String key, int fallback, int min, int max) { Object value = p == null ? null : p.get(key); int result = value instanceof Number ? ((Number) value).intValue() : fallback; if (result < min || result > max) throw new BusinessException("aiVideo.numberInvalid", key); return result; }
    private String required(Map<String, Object> p, String key, int max) { String value = value(p, key, "").trim(); if (value.isBlank()) throw new BusinessException("aiVideo.fieldRequired", key); if (value.length() > max) throw new BusinessException("aiVideo.fieldTooLong", key, max); return value; }
    private String text(Map<String, Object> p, String key, int max) { String value = value(p, key, ""); return value.length() > max ? value.substring(0, max) : (value.isBlank() ? null : value); }
    private String value(Map<String, Object> p, String key, String fallback) { Object value = p == null ? null : p.get(key); return value == null ? fallback : String.valueOf(value); }
    private String enumValue(String value, Set<String> allowed, String message) { String normalized = value == null ? "" : value.toUpperCase(); if (!allowed.contains(normalized)) throw new BusinessException(message); return normalized; }
    private String resource(String value) { if (value == null || !RESOURCES.contains(value)) throw new BusinessException("aiVideo.resourceInvalid"); return value; }
    private String table(String resource) { return switch (resource(resource)) { case "projects" -> "ai_video_project"; case "scripts" -> "ai_video_script"; case "storyboards" -> "ai_video_storyboard"; case "assets" -> "ai_video_asset"; case "tasks" -> "ai_video_task"; case "renders" -> "ai_video_render"; case "videos" -> "ai_video_video"; case "reviews" -> "ai_video_review"; case "publishes" -> "ai_video_publish"; default -> throw new BusinessException("aiVideo.resourceInvalid"); }; }
    private String columnAlias(String resource) { return switch (resource) { case "scripts", "storyboards", "assets" -> resource.substring(0, resource.length() - 1).charAt(0) + ""; case "tasks" -> "t"; case "renders" -> "r"; case "videos" -> "v"; default -> "x"; }; }
    /** 返回列表 SQL 使用的主表别名。 */
    private String orderAlias(String resource) { return switch (resource(resource)) { case "projects" -> "p"; case "scripts", "storyboards" -> "s"; case "assets" -> "a"; case "tasks" -> "t"; case "renders" -> "r"; case "videos" -> "v"; case "reviews" -> "r"; case "publishes" -> "x"; default -> "x"; }; }
    private String ownerClause(String column, AuthUser user) { return user.roles().contains("ADMIN") ? "" : " AND " + column + "=" + user.id(); }
    private long count(String table, String ownerColumn, AuthUser user) { return scalar("SELECT COUNT(*) FROM " + table + " x WHERE 1=1" + ownerClause("x." + ownerColumn, user), user); }
    private long scalar(String sql, AuthUser user) { Long value = jdbc.queryForObject(sql, Long.class); return value == null ? 0L : value; }
    private AuthUser user() { return AuthContext.require(); }

    /** 将数据库列名转换为前端使用的 camelCase，避免前端重复处理字段。 */
    private Map<String, Object> row(ResultSet rs) throws java.sql.SQLException {
        ResultSetMetaData metadata = rs.getMetaData(); Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 1; i <= metadata.getColumnCount(); i++) result.put(camel(metadata.getColumnLabel(i)), rs.getObject(i));
        return result;
    }

    /** 将 snake_case 列名转换为 camelCase。 */
    private String camel(String name) { StringBuilder result = new StringBuilder(); boolean upper = false; for (char c : name.toCharArray()) { if (c == '_') upper = true; else { result.append(upper ? Character.toUpperCase(c) : c); upper = false; } } return result.toString(); }
}
