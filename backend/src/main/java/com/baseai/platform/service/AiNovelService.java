package com.baseai.platform.service;

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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** AI 小说工作台服务，负责原著、章节、创作参考资料和影视改编的持久化。 */
@Service
public class AiNovelService {
    private static final Set<String> STATUSES = Set.of("DRAFT", "ONGOING", "COMPLETED", "ARCHIVED");
    private static final Set<String> REFERENCE_TYPES = Set.of("CHARACTER", "WORLD", "STYLE", "RESEARCH");
    private static final Set<String> AI_OPERATIONS = Set.of("OUTLINE", "CHAPTER", "CONTINUE", "REWRITE", "POLISH", "SUMMARY");
    private static final String NOVEL_SELECT = "SELECT * FROM ai_novel WHERE id=?";
    private static final String WRITING_SYSTEM = """
        你是长篇小说主笔和编辑。你必须使用用户指定的语言写作，遵守作品的人物、世界观、文风和时间线。
        输出只包含可以直接放入小说正文的内容，不要 Markdown 代码围栏，不要解释你的步骤，不要虚构未给出的事实。
        内容要有具体的感官细节、人物动机和场景推进，避免重复套话、违法危险指导和现实人物隐私。
        """;
    private static final String EXTRACTION_SYSTEM = """
        你是小说影视改编编剧。请把原著章节改编成适合 AI 视频制作的分镜剧本。
        只返回合法 JSON 对象，不要 Markdown 代码围栏，不要额外说明。结构必须是：
        {"title":"改编剧本标题","logline":"一句话梗概","synopsis":"影视梗概","scenes":[{"title":"场景标题","sceneDescription":"可拍摄的环境、人物动作、光线和构图","dialogue":"角色名：对白，没有对白时为空字符串","narration":"旁白，没有旁白时为空字符串","camera":"景别和镜头运动","durationMs":5000}]}
        每个场景的 durationMs 必须是 1000 到 60000 的整数，保持人物和场景前后一致。
        """;

    private final JdbcTemplate jdbc;
    private final AiChatClient client;
    private final ObjectMapper mapper;

    /** 注入数据库、文本模型客户端和 JSON 编解码器。 */
    public AiNovelService(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc, AiChatClient client, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.client = client;
        this.mapper = mapper;
    }

    /** 查询当前用户可访问的原著作品，并按最近编辑时间倒序返回。 */
    public List<Map<String, Object>> listNovels() {
        AuthUser user = AuthContext.require();
        return jdbc.query("SELECT n.*, (SELECT COUNT(*) FROM ai_novel_chapter c WHERE c.novel_id=n.id) AS chapter_count "
                + "FROM ai_novel n WHERE 1=1" + ownerClause("n.owner_user_id", user)
                + " ORDER BY n.updated_at DESC, n.id DESC", (rs, row) -> row(rs));
    }

    /** 查询作品详情，并附带章节和写作参考资料，避免页面重复请求。 */
    public Map<String, Object> getNovel(long id) {
        requireNovel(id);
        Map<String, Object> novel = jdbc.queryForObject(NOVEL_SELECT, (rs, row) -> row(rs), id);
        if (novel == null) throw BusinessException.notFound("aiNovel.notFound");
        novel.put("chapters", listChapters(id));
        novel.put("references", listReferences(id));
        return novel;
    }

    /** 创建一个新的原著作品。 */
    @Transactional
    public Map<String, Object> createNovel(Map<String, Object> payload) {
        AuthUser user = AuthContext.require();
        String title = required(payload, "title", 200);
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO ai_novel(owner_user_id,title,logline,genre,tone,language,outline,status) VALUES (?,?,?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, user.id());
            statement.setString(2, title);
            statement.setString(3, text(payload, "logline", 1000));
            statement.setString(4, text(payload, "genre", 80));
            statement.setString(5, text(payload, "tone", 120));
            statement.setString(6, value(payload, "language", "zh-CN"));
            statement.setString(7, text(payload, "outline", 100000));
            statement.setString(8, enumValue(value(payload, "status", "DRAFT"), STATUSES, "aiNovel.statusInvalid"));
            return statement;
        }, keys);
        Number key = keys.getKey();
        if (key == null) throw new BusinessException("aiNovel.saveFailed");
        return getNovel(key.longValue());
    }

    /** 更新原著元数据和 AI 生成的大纲。 */
    @Transactional
    public Map<String, Object> updateNovel(long id, Map<String, Object> payload) {
        requireNovel(id);
        jdbc.update("UPDATE ai_novel SET title=?,logline=?,genre=?,tone=?,language=?,outline=?,status=?,updated_at=NOW(6) WHERE id=?",
            required(payload, "title", 200), text(payload, "logline", 1000), text(payload, "genre", 80),
            text(payload, "tone", 120), value(payload, "language", "zh-CN"), text(payload, "outline", 100000),
            enumValue(value(payload, "status", "DRAFT"), STATUSES, "aiNovel.statusInvalid"), id);
        return getNovel(id);
    }

    /** 删除原著及其章节和参考资料，子表由数据库级联清理。 */
    @Transactional
    public void deleteNovel(long id) {
        requireNovel(id);
        jdbc.update("DELETE FROM ai_novel WHERE id=?", id);
    }

    /** 查询作品章节，供左侧章节树和编辑器加载使用。 */
    public List<Map<String, Object>> listChapters(long novelId) {
        requireNovel(novelId);
        return jdbc.query("SELECT * FROM ai_novel_chapter WHERE novel_id=? ORDER BY chapter_no,id", (rs, row) -> row(rs), novelId);
    }

    /** 创建章节并计算初始字数，章节序号缺省时自动追加到末尾。 */
    @Transactional
    public Map<String, Object> createChapter(long novelId, Map<String, Object> payload) {
        requireNovel(novelId);
        int chapterNo = payload != null && payload.get("chapterNo") instanceof Number
            ? ((Number) payload.get("chapterNo")).intValue()
            : nextChapterNo(novelId);
        if (chapterNo < 1 || chapterNo > 100000) throw new BusinessException("aiNovel.chapterNumberInvalid");
        String content = text(payload, "content", 200000);
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO ai_novel_chapter(novel_id,parent_id,chapter_no,title,synopsis,content,status,word_count) VALUES (?,?,?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, novelId);
            setNullableLong(statement, 2, payload == null ? null : payload.get("parentId"));
            statement.setInt(3, chapterNo);
            statement.setString(4, required(payload, "title", 200));
            statement.setString(5, text(payload, "synopsis", 1000));
            statement.setString(6, content == null ? "" : content);
            statement.setString(7, enumValue(value(payload, "status", "DRAFT"), STATUSES, "aiNovel.statusInvalid"));
            statement.setInt(8, wordCount(content));
            return statement;
        }, keys);
        Number key = keys.getKey();
        if (key == null) throw new BusinessException("aiNovel.saveFailed");
        return findChapter(novelId, key.longValue());
    }

    /** 更新章节正文、摘要、标题和状态，并同步字数统计。 */
    @Transactional
    public Map<String, Object> updateChapter(long novelId, long chapterId, Map<String, Object> payload) {
        requireChapter(novelId, chapterId);
        String content = text(payload, "content", 200000);
        jdbc.update("UPDATE ai_novel_chapter SET parent_id=?,chapter_no=?,title=?,synopsis=?,content=?,status=?,word_count=?,updated_at=NOW(6) WHERE id=? AND novel_id=?",
            nullableLong(payload, "parentId"), integer(payload, "chapterNo", 1, 1, 100000), required(payload, "title", 200),
            text(payload, "synopsis", 1000), content == null ? "" : content,
            enumValue(value(payload, "status", "DRAFT"), STATUSES, "aiNovel.statusInvalid"), wordCount(content), chapterId, novelId);
        return findChapter(novelId, chapterId);
    }

    /** 删除章节并保留同一作品其他章节的顺序。 */
    @Transactional
    public void deleteChapter(long novelId, long chapterId) {
        requireChapter(novelId, chapterId);
        jdbc.update("DELETE FROM ai_novel_chapter WHERE id=? AND novel_id=?", chapterId, novelId);
    }

    /** 查询作品的人物、世界观和文风等写作参考资料。 */
    public List<Map<String, Object>> listReferences(long novelId) {
        requireNovel(novelId);
        return jdbc.query("SELECT * FROM ai_novel_reference WHERE novel_id=? ORDER BY reference_type,sort_order,id",
            (rs, row) -> row(rs), novelId);
    }

    /** 新增一条写作参考资料。 */
    @Transactional
    public Map<String, Object> createReference(long novelId, Map<String, Object> payload) {
        requireNovel(novelId);
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO ai_novel_reference(novel_id,reference_type,name,content,sort_order) VALUES (?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, novelId);
            statement.setString(2, enumValue(value(payload, "referenceType", "CHARACTER"), REFERENCE_TYPES, "aiNovel.referenceTypeInvalid"));
            statement.setString(3, required(payload, "name", 200));
            statement.setString(4, text(payload, "content", 200000));
            statement.setInt(5, integer(payload, "sortOrder", 0, 0, 100000));
            return statement;
        }, keys);
        Number key = keys.getKey();
        if (key == null) throw new BusinessException("aiNovel.saveFailed");
        return findReference(novelId, key.longValue());
    }

    /** 更新一条写作参考资料。 */
    @Transactional
    public Map<String, Object> updateReference(long novelId, long referenceId, Map<String, Object> payload) {
        requireReference(novelId, referenceId);
        jdbc.update("UPDATE ai_novel_reference SET reference_type=?,name=?,content=?,sort_order=?,updated_at=NOW(6) WHERE id=? AND novel_id=?",
            enumValue(value(payload, "referenceType", "CHARACTER"), REFERENCE_TYPES, "aiNovel.referenceTypeInvalid"),
            required(payload, "name", 200), text(payload, "content", 200000), integer(payload, "sortOrder", 0, 0, 100000), referenceId, novelId);
        return findReference(novelId, referenceId);
    }

    /** 删除一条写作参考资料。 */
    @Transactional
    public void deleteReference(long novelId, long referenceId) {
        requireReference(novelId, referenceId);
        jdbc.update("DELETE FROM ai_novel_reference WHERE id=? AND novel_id=?", referenceId, novelId);
    }

    /** 调用文本模型执行大纲、章节、续写、改写、润色或摘要操作。 */
    public Map<String, Object> write(long novelId, WriteRequest request) {
        requireNovel(novelId);
        String operation = enumValue(request == null ? null : request.operation(), AI_OPERATIONS, "aiNovel.operationInvalid");
        Map<String, Object> novel = jdbc.queryForObject(NOVEL_SELECT, (rs, row) -> row(rs), novelId);
        String chapterContext = request != null && request.chapterId() != null
            ? chapterContext(novelId, request.chapterId()) : "";
        String references = referenceContext(novelId);
        String instruction = text(request == null ? null : request.instruction(), 10000);
        String userPrompt = buildWritingPrompt(operation, novel, chapterContext, references, instruction);
        AuthUser user = AuthContext.require();
        AiChatClient.ChatResult result = client.chat("chat", "text_model",
            List.of(new AiChatClient.Message("system", WRITING_SYSTEM), new AiChatClient.Message("user", userPrompt)),
            0.65D, null, null, null, "AI_NOVEL", user.id(), null);
        // 大纲生成允许没有章节，不能使用拒绝 null 的 Map.of 返回结果。
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("operation", operation);
        response.put("chapterId", request == null ? null : request.chapterId());
        response.put("content", result.content());
        response.put("model", result.model());
        response.put("inputTokens", result.inputTokens());
        response.put("outputTokens", result.outputTokens());
        response.put("totalTokens", result.totalTokens());
        return response;
    }

    /** 从指定原著章节生成 AI 视频改编剧本、分镜并建立来源关系。 */
    @Transactional
    public Map<String, Object> extractToVideo(long novelId, ExtractRequest request) {
        requireNovel(novelId);
        if (request == null || request.projectId() == null || request.projectId() <= 0) throw new BusinessException("aiNovel.projectRequired");
        requireVideoProject(request.projectId());
        List<Long> chapterIds = request.chapterIds() == null ? List.of() : request.chapterIds().stream().filter(id -> id != null && id > 0).distinct().toList();
        if (chapterIds.isEmpty() || chapterIds.size() > 24) throw new BusinessException("aiNovel.chaptersRequired");
        String source = chapterIds.stream().map(id -> chapterContext(novelId, id)).reduce((left, right) -> left + "\n\n" + right).orElse("");
        Map<String, Object> novel = jdbc.queryForObject(NOVEL_SELECT, (rs, row) -> row(rs), novelId);
        int sceneCount = request.sceneCount() == null ? 6 : request.sceneCount();
        if (sceneCount < 1 || sceneCount > 24) throw new BusinessException("aiNovel.sceneCountInvalid");
        String prompt = "原著标题：" + value(novel, "title", "") + "\n原著梗概：" + value(novel, "logline", "")
            + "\n原著章节：\n" + source + "\n请提取为约 " + sceneCount + " 个镜头。";
        AuthUser user = AuthContext.require();
        AiChatClient.ChatResult result = client.chat("chat", "text_model",
            List.of(new AiChatClient.Message("system", EXTRACTION_SYSTEM), new AiChatClient.Message("user", prompt)),
            0.4D, null, null, null, "AI_NOVEL_TO_VIDEO", user.id(), null);
        JsonNode root = parseJson(result.content());
        String title = root.path("title").asText(value(novel, "title", "未命名改编剧本"));
        String content = result.content();
        String chapterJson;
        try { chapterJson = mapper.writeValueAsString(chapterIds); } catch (Exception ignored) { chapterJson = "[]"; }
        KeyHolder keys = new GeneratedKeyHolder();
        final String sourceChapterJson = chapterJson;
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO ai_video_script(project_id,source_novel_id,source_chapter_ids,source_type,title,content,version,status,owner_user_id) VALUES (?,?,?,?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, request.projectId());
            statement.setLong(2, novelId);
            statement.setString(3, sourceChapterJson);
            statement.setString(4, "NOVEL_EXTRACTION");
            statement.setString(5, title.length() > 200 ? title.substring(0, 200) : title);
            statement.setString(6, content);
            statement.setInt(7, 1);
            statement.setString(8, "DRAFT");
            statement.setLong(9, user.id());
            return statement;
        }, keys);
        Number key = keys.getKey();
        if (key == null) throw new BusinessException("aiNovel.videoExtractionFailed");
        long scriptId = key.longValue();
        int storyboardCount = insertStoryboards(root, request.projectId(), scriptId, user.id());
        return Map.of("scriptId", scriptId, "title", title, "content", content, "storyboardCount", storyboardCount,
            "model", result.model(), "inputTokens", result.inputTokens(), "outputTokens", result.outputTokens(), "totalTokens", result.totalTokens());
    }

    /** 生成适合不同写作操作的模型提示词，并加入作品上下文。 */
    private String buildWritingPrompt(String operation, Map<String, Object> novel, String chapterContext, String references, String instruction) {
        String base = "作品：" + value(novel, "title", "未命名作品") + "\n一句话梗概：" + value(novel, "logline", "")
            + "\n类型：" + value(novel, "genre", "") + "\n风格：" + value(novel, "tone", "")
            + "\n语言：" + value(novel, "language", "zh-CN") + "\n大纲：" + value(novel, "outline", "")
            + "\n写作参考：\n" + references + "\n当前章节：\n" + chapterContext;
        return switch (operation) {
            case "OUTLINE" -> base + "\n请根据创意和参考资料生成一份可执行的长篇小说大纲，包含主题、主要冲突、人物弧光、分卷和章节走向。" + instruction;
            case "CHAPTER" -> base + "\n请根据当前章节信息写出完整章节正文，直接从正文开始，保持叙事连贯。" + instruction;
            case "CONTINUE" -> base + "\n请从当前章节最后一句自然续写，推进冲突并保留人物口吻。" + instruction;
            case "REWRITE" -> base + "\n请按用户要求重写当前章节内容，保留有效事实和人物关系，输出完整替换稿。" + instruction;
            case "POLISH" -> base + "\n请润色当前章节，改善节奏、画面感和语言准确性，不改变情节事实。" + instruction;
            case "SUMMARY" -> base + "\n请总结当前章节的事件、人物变化、伏笔和下一章钩子，供作者继续创作。" + instruction;
            default -> throw new BusinessException("aiNovel.operationInvalid");
        };
    }

    /** 将 AI 返回的结构化分镜写入 AI 视频工作台。 */
    private int insertStoryboards(JsonNode root, long projectId, long scriptId, long ownerId) {
        JsonNode scenes = root.path("scenes");
        if (!scenes.isArray() || scenes.isEmpty()) return 0;
        int count = 0;
        for (JsonNode scene : scenes) {
            if (count >= 100) break;
            int duration = Math.max(1000, Math.min(60000, scene.path("durationMs").asInt(5000)));
            jdbc.update("INSERT INTO ai_video_storyboard(project_id,script_id,shot_no,title,scene_description,dialogue,narration,camera,duration_ms,status,owner_user_id) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                projectId, scriptId, count + 1, limit(scene.path("title").asText("场景 " + (count + 1)), 200),
                limit(scene.path("sceneDescription").asText(""), 2000), limit(scene.path("dialogue").asText(""), 5000),
                limit(scene.path("narration").asText(""), 5000), limit(scene.path("camera").asText("中景"), 120), duration, "DRAFT", ownerId);
            count++;
        }
        return count;
    }

    /** 从文本模型响应中提取 JSON 对象，解析失败时返回空对象并保留原始正文。 */
    private JsonNode parseJson(String content) {
        String source = content == null ? "" : content.trim();
        int start = source.indexOf('{');
        int end = source.lastIndexOf('}');
        if (start < 0 || end <= start) return mapper.createObjectNode();
        try { return mapper.readTree(source.substring(start, end + 1)); } catch (Exception ignored) { return mapper.createObjectNode(); }
    }

    /** 查询并校验当前用户拥有的原著。 */
    private void requireNovel(long id) {
        if (id <= 0) throw BusinessException.notFound("aiNovel.notFound");
        AuthUser user = AuthContext.require();
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM ai_novel WHERE id=?" + ownerClause("owner_user_id", user), Long.class, id);
        if (count == null || count == 0) throw BusinessException.notFound("aiNovel.notFound");
    }

    /** 查询并校验属于指定原著的章节。 */
    private void requireChapter(long novelId, long chapterId) {
        requireNovel(novelId);
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM ai_novel_chapter WHERE id=? AND novel_id=?", Long.class, chapterId, novelId);
        if (count == null || count == 0) throw BusinessException.notFound("aiNovel.chapterNotFound");
    }

    /** 查询并校验属于指定原著的参考资料。 */
    private void requireReference(long novelId, long referenceId) {
        requireNovel(novelId);
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM ai_novel_reference WHERE id=? AND novel_id=?", Long.class, referenceId, novelId);
        if (count == null || count == 0) throw BusinessException.notFound("aiNovel.referenceNotFound");
    }

    /** 校验视频项目归属，防止原著跨用户写入视频项目。 */
    private void requireVideoProject(long projectId) {
        AuthUser user = AuthContext.require();
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM ai_video_project WHERE id=?" + ownerClause("owner_user_id", user), Long.class, projectId);
        if (count == null || count == 0) throw BusinessException.notFound("aiVideo.resourceNotFound");
    }

    /** 查找章节并转换字段名，供接口返回统一 camelCase 结构。 */
    private Map<String, Object> findChapter(long novelId, long chapterId) {
        Map<String, Object> row = jdbc.queryForObject("SELECT * FROM ai_novel_chapter WHERE id=? AND novel_id=?", (rs, index) -> row(rs), chapterId, novelId);
        if (row == null) throw BusinessException.notFound("aiNovel.chapterNotFound");
        return row;
    }

    /** 查找参考资料并转换字段名。 */
    private Map<String, Object> findReference(long novelId, long referenceId) {
        Map<String, Object> row = jdbc.queryForObject("SELECT * FROM ai_novel_reference WHERE id=? AND novel_id=?", (rs, index) -> row(rs), referenceId, novelId);
        if (row == null) throw BusinessException.notFound("aiNovel.referenceNotFound");
        return row;
    }

    /** 返回章节上下文并在发送给模型前限制正文长度。 */
    private String chapterContext(long novelId, long chapterId) {
        requireChapter(novelId, chapterId);
        Map<String, Object> chapter = findChapter(novelId, chapterId);
        return "第" + value(chapter, "chapterNo", "") + "章《" + value(chapter, "title", "") + "》\n摘要："
            + value(chapter, "synopsis", "") + "\n正文：" + limit(value(chapter, "content", ""), 50000);
    }

    /** 汇总人物、世界观和文风资料，作为每次 AI 写作的固定上下文。 */
    private String referenceContext(long novelId) {
        return listReferences(novelId).stream()
            .map(item -> "[" + value(item, "referenceType", "") + "] " + value(item, "name", "") + "：" + limit(value(item, "content", ""), 3000))
            .reduce((left, right) -> left + "\n" + right).orElse("");
    }

    /** 计算下一章序号。 */
    private int nextChapterNo(long novelId) {
        Integer value = jdbc.queryForObject("SELECT COALESCE(MAX(chapter_no),0)+1 FROM ai_novel_chapter WHERE novel_id=?", Integer.class, novelId);
        return value == null ? 1 : value;
    }

    /** 将字符串转换为简单字数统计，中文按字符、英文按词近似计算。 */
    private int wordCount(String value) { return value == null || value.isBlank() ? 0 : value.trim().split("\\s+").length + (int) value.chars().filter(Character::isIdeographic).count(); }

    /** 为非管理员用户追加归属条件。 */
    private String ownerClause(String column, AuthUser user) { return user.roles().contains("ADMIN") ? "" : " AND " + column + "=" + user.id(); }

    /** 将结果集转换为前端使用的 camelCase Map。 */
    private Map<String, Object> row(ResultSet rs) throws java.sql.SQLException {
        ResultSetMetaData metadata = rs.getMetaData();
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 1; i <= metadata.getColumnCount(); i++) result.put(camel(metadata.getColumnLabel(i)), rs.getObject(i));
        return result;
    }

    /** 将数据库列名转换为 camelCase。 */
    private String camel(String name) { StringBuilder result = new StringBuilder(); boolean upper = false; for (char c : name.toCharArray()) { if (c == '_') upper = true; else { result.append(upper ? Character.toUpperCase(c) : c); upper = false; } } return result.toString(); }
    /** 读取必填字段并限制长度。 */
    private String required(Map<String, Object> payload, String key, int max) { String value = value(payload, key, "").trim(); if (value.isBlank()) throw new BusinessException("aiNovel.fieldRequired", key); if (value.length() > max) throw new BusinessException("aiNovel.fieldTooLong", key, max); return value; }
    /** 读取可选文本并限制长度。 */
    private String text(Map<String, Object> payload, String key, int max) { return text(payload == null ? null : payload.get(key), max); }
    /** 读取任意对象的可选文本并限制长度。 */
    private String text(Object raw, int max) { if (raw == null) return null; String value = String.valueOf(raw).trim(); return value.isBlank() ? null : limit(value, max); }
    /** 读取字段或默认值。 */
    private String value(Map<String, Object> payload, String key, String fallback) { Object raw = payload == null ? null : payload.get(key); return raw == null ? fallback : String.valueOf(raw); }
    /** 校验枚举字段。 */
    private String enumValue(String raw, Set<String> allowed, String message) { String value = raw == null ? "" : raw.trim().toUpperCase(); if (!allowed.contains(value)) throw new BusinessException(message); return value; }
    /** 校验整数范围并拒绝小数或非数字输入。 */
    private int integer(Map<String, Object> payload, String key, int fallback, int min, int max) { Object raw = payload == null ? null : payload.get(key); int value = integral(raw, fallback, key); if (value < min || value > max) throw new BusinessException("aiNovel.numberInvalid", key); return value; }
    /** 读取可空长整数并拒绝小数或非法正数。 */
    private Long nullableLong(Map<String, Object> payload, String key) { Object raw = payload == null ? null : payload.get(key); if (raw == null) return null; long value = integral(raw, 0, key); return value > 0 ? value : null; }
    /** 向预处理语句写入严格校验后的可空长整数。 */
    private void setNullableLong(PreparedStatement statement, int index, Object raw) throws java.sql.SQLException { if (raw == null) { statement.setObject(index, null); return; } int value = integral(raw, 0, "parentId"); if (value > 0) statement.setLong(index, value); else statement.setObject(index, null); }
    /** 将请求中的数字严格转换为整数，避免 1.5 被静默截断。 */
    private int integral(Object raw, int fallback, String key) { if (raw == null) return fallback; if (!(raw instanceof Number number) || number.doubleValue() != number.longValue()) throw new BusinessException("aiNovel.numberInvalid", key); long value = number.longValue(); if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw new BusinessException("aiNovel.numberInvalid", key); return (int) value; }
    /** 从 JSON 数组兼容读取章节 ID。 */
    private List<Long> chapterIds(JsonNode node) { List<Long> result = new ArrayList<>(); if (node != null && node.isArray()) node.forEach(item -> { if (item.isIntegralNumber() && item.asLong() > 0) result.add(item.asLong()); }); return result; }
    /** 限制文本长度，避免提示词和数据库字段超限。 */
    private String limit(String value, int max) { return value == null ? "" : value.substring(0, Math.min(max, value.length())); }

    /** AI 写作操作请求。 */
    public record WriteRequest(String operation, Long chapterId, String instruction) {}
    /** 从原著提取影视改编剧本的请求。 */
    public record ExtractRequest(Long projectId, List<Long> chapterIds, Integer sceneCount, String title) {}
}
