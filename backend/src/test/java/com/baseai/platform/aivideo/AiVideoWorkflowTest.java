package com.baseai.platform.aivideo;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.security.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 使用真实关系数据库验证回填、归属、审核和发布，不模拟核心 SQL。 */
class AiVideoWorkflowTest {
    private JdbcTemplate jdbc;
    private AiVideoService service;
    private static final String URL = "https://cdn.example/video.mp4";

    /** 从正式迁移建立测试表，仅将 JSON 替换为 H2 可直接读取的文本列。 */
    @BeforeEach void setup() throws Exception {
        var dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:video-" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE");
        jdbc = new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(dataSource.getConnection(), true));
        try (var source = getClass().getResourceAsStream("/db/migration/mysql/V44__add_ai_video_workspace.sql")) {
            String sql = new String(source.readAllBytes(), StandardCharsets.UTF_8).replace(" JSON ", " LONGTEXT ");
            for (String statement : sql.split(";")) if (!statement.isBlank()) jdbc.execute(statement);
        }
        jdbc.execute("CREATE TABLE ai_media_generation_task(id BIGINT PRIMARY KEY, owner_user_id BIGINT, generation_type VARCHAR(32), status VARCHAR(24), output_json LONGTEXT, prompt TEXT)");
        jdbc.update("INSERT INTO ai_video_project(id,code,name,owner_user_id) VALUES (1,'own','Own',7),(2,'other','Other',8)");
        jdbc.update("INSERT INTO ai_video_storyboard(id,project_id,shot_no,title,owner_user_id) VALUES (1,1,1,'Shot',7),(2,2,1,'Other shot',8)");
        jdbc.update("INSERT INTO ai_media_generation_task VALUES (11,7,'video_model','SUCCEEDED',?, 'Rain')", "{\"outputs\":[{\"mediaType\":\"video\",\"kind\":\"url\",\"value\":\"" + URL + "\"},{\"mediaType\":\"image\",\"value\":\"https://cdn.example/frame.png\"}]}");
        service = new AiVideoService(jdbc);
        login(7, Set.of("ai:video:video:create", "ai:video:review:create", "ai:video:review:update"));
    }

    /** 清理连接与线程登录态。 */
    @AfterEach void cleanup() throws Exception { AuthContext.clear(); ((AutoCloseable) jdbc.getDataSource()).close(); }

    /** 构造权限受限的普通用户。 */
    private void login(long id, Set<String> permissions) { AuthContext.set(new AuthUser(id, "user", Set.of("USER"), permissions, AuthenticationType.TOKEN, null, null)); }

    /** 构造合法回填请求。 */
    private Map<String, Object> request() { return new LinkedHashMap<>(Map.of("generationTaskId",11L,"projectId",1L,"storyboardId",1L,"mediaUrl",URL,"title","Clip")); }

    /** 提取持久化成片编号。 */
    private long promote() { return ((Number) service.promoteGeneration(request()).get("id")).longValue(); }

    /** 检查业务错误及数据库无新增副作用。 */
    private void rejected(Map<String, Object> payload) { assertThrows(BusinessException.class, () -> service.promoteGeneration(payload)); assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_video_video", Integer.class)); }

    @Test void persistsOutputAndSourceAndRejectsDuplicate() {
        long id = promote();
        assertEquals(URL, jdbc.queryForObject("SELECT media_url FROM ai_video_video WHERE id=?", String.class, id));
        assertTrue(jdbc.queryForObject("SELECT description FROM ai_video_video WHERE id=?", String.class, id).contains("storyboard #1"));
        assertThrows(BusinessException.class, () -> service.promoteGeneration(request()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_video_video", Integer.class));
    }

    @ParameterizedTest @ValueSource(strings={"RUNNING","QUEUED","FAILED","CANCELLED"})
    void refusesUnfinishedTasks(String status) { jdbc.update("UPDATE ai_media_generation_task SET status=?",status); rejected(request()); }

    @ParameterizedTest @ValueSource(strings={"javascript:alert(1)","//cdn.example/video.mp4","https://u:p@cdn.example/a","https://cdn.example/frame.png","https://cdn.example/forged.mp4"})
    void refusesUnsafeOrNonVideoOutputs(String url) { var p=request(); p.put("mediaUrl",url); rejected(p); }

    @Test void refusesUnauthorizedAndCrossProjectReferences() {
        AuthContext.clear(); rejected(request());
        login(8, Set.of("ai:video:video:create")); rejected(request());
        login(7, Set.of()); rejected(request());
        login(7, Set.of("ai:video:video:create")); var p=request(); p.put("storyboardId",2L); rejected(p);
    }

    @Test void rejectsFractionalIdentifiersAndMissingTitle() {
        for (String key : List.of("generationTaskId","projectId","storyboardId")) { var p=request(); p.put(key,1.5); rejected(p); }
        var p=request(); p.put("title"," "); rejected(p);
    }

    @Test void rejectsCallerPublishedStatus() { var p=request(); p.put("status","PUBLISHED"); rejected(p); }

    @Test void latestReviewControlsPublicationAndUpdatesVideo() {
        long id=promote();
        var publish=service.create("publishes",Map.of("videoId",id,"channel","manual"),null);
        long publishId=((Number)publish.get("id")).longValue();
        assertThrows(BusinessException.class,()->service.markPublished(publishId));
        service.reviewVideo(id,Map.of("status","APPROVED","comment","Good"));
        assertEquals("PUBLISHED",service.markPublished(publishId).get("status"));
        assertEquals("PUBLISHED",jdbc.queryForObject("SELECT status FROM ai_video_video WHERE id=?",String.class,id));
        assertThrows(BusinessException.class,()->service.markPublished(publishId));
    }

    @Test void directReviewAndPublishCannotBypassMediaOrLatestDecision() {
        long id=promote();
        service.create("reviews",Map.of("videoId",id,"status","APPROVED"),null);
        service.create("reviews",Map.of("videoId",id,"status","REJECTED"),null);
        assertThrows(BusinessException.class,()->service.create("publishes",Map.of("videoId",id,"channel","manual","status","PUBLISHED"),null));
        jdbc.update("UPDATE ai_video_video SET media_url=NULL WHERE id=?",id);
        assertThrows(BusinessException.class,()->service.create("reviews",Map.of("videoId",id,"status","APPROVED"),null));
    }

    @Test void replacingMediaInvalidatesApprovalAndCannotClearReviewedMedia() {
        long id=promote(); service.reviewVideo(id,Map.of("status","APPROVED"));
        assertThrows(BusinessException.class,()->service.update("videos",id,Map.of("title","Clip","status","REVIEW","mediaUrl","")));
        service.update("videos",id,Map.of("title","New","status","DRAFT","mediaUrl","https://cdn.example/new.mp4"));
        assertThrows(BusinessException.class,()->service.create("publishes",Map.of("videoId",id,"channel","manual","status","PUBLISHED"),null));
    }
}
