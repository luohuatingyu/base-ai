package com.baseai.platform.chat;

import com.baseai.platform.common.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 使用真实 H2/JPA/事务验证持久化、隔离、防重及租约恢复。 */
@SpringJUnitConfig(ChatConversationServiceTest.Config.class)
class ChatConversationServiceTest {
    @Configuration @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = ChatConversationRepository.class)
    static class Config {
        /** 提供隔离的内存数据库。 */
        @Bean DataSource dataSource() { return new DriverManagerDataSource("jdbc:h2:mem:chat;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""); }
        /** 仅加载会话实体，避免无关外部服务。 */
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(source); factory.setPackagesToScan("com.baseai.platform.chat");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop",
                "hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy"));
            return factory;
        }
        /** 提供实际数据库事务。 */
        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory factory) { return new JpaTransactionManager(factory); }
        /** 复用业务 JSON 编解码。 */
        @Bean ObjectMapper mapper() { return new ObjectMapper(); }
        /** 装配未经 Mock 的业务服务。 */
        @Bean ChatConversationService service(ChatConversationRepository conversations, ChatMessageRepository messages,
                                              ObjectMapper mapper, PlatformTransactionManager manager) {
            return new ChatConversationService(conversations, messages, mapper, manager);
        }
    }
    @org.springframework.beans.factory.annotation.Autowired ChatConversationService service;
    @org.springframework.beans.factory.annotation.Autowired ChatConversationRepository conversations;
    @org.springframework.beans.factory.annotation.Autowired ChatMessageRepository messages;
    @org.springframework.beans.factory.annotation.Autowired PlatformTransactionManager manager;
    final ObjectMapper mapper = new ObjectMapper();

    /** 每项测试独立清理数据库。 */
    @BeforeEach void clean() {
        new TransactionTemplate(manager).executeWithoutResult(status -> { messages.deleteAll(); conversations.deleteAll(); });
    }

    /** 构建合法请求。 */
    ChatConversationService.SendRequest request(String id, String content) {
        return new ChatConversationService.SendRequest(id, mapper.valueToTree(content),
            new ChatConversationService.Settings("text_model", "chat", null, false, "MEDIUM", "你是助手"));
    }

    /** 完成消息在重新读取及续聊时保持顺序，删除清理关联数据。 */
    @Test void persistsRestoresContinuesAndDeletes() {
        Long id = service.create(1L).id();
        var turn = service.begin(1L, id, request("first", "你好"));
        service.save(turn, "回答", "COMPLETED", mapper.valueToTree(Map.of("model", "test-model", "totalTokens", 3)));
        var detail = service.detail(1L, id);
        assertEquals("你好", detail.conversation().title());
        assertEquals("回答", detail.messages().get(1).content().asText());
        assertEquals(3, detail.messages().get(1).metadata().path("totalTokens").asInt());
        var next = service.begin(1L, id, request("second", "继续"));
        assertEquals(List.of("system", "user", "assistant", "user"), next.context().stream().map(com.baseai.platform.service.AiChatClient.Message::role).toList());
        assertEquals("回答", ((com.fasterxml.jackson.databind.JsonNode) next.context().get(2).content()).asText());
        service.save(next, "下一段", "COMPLETED", null);
        service.delete(1L, id);
        assertEquals(0, messages.count());
        assertEquals(0, conversations.count());
    }

    /** 他人会话与不存在的编号均返回 404，不能读取、删除或写入。 */
    @Test void isolatesOwners() {
        Long id = service.create(1L).id();
        assertEquals(0L, service.list(2L, 0).get("total"));
        assertEquals(404, assertThrows(BusinessException.class, () -> service.detail(2L, id)).getStatus());
        assertEquals(404, assertThrows(BusinessException.class, () -> service.delete(2L, id)).getStatus());
        assertEquals(404, assertThrows(BusinessException.class, () -> service.begin(2L, id, request("bad", "test"))).getStatus());
        assertEquals(404, assertThrows(BusinessException.class, () -> service.detail(1L, Long.MAX_VALUE)).getStatus());
        assertEquals(0, messages.count());
    }

    /** 活动租约和重复请求不会重复写入，生成期间禁止删除。 */
    @Test void rejectsConflictsAndDuplicates() {
        Long id = service.create(1L).id();
        var turn = service.begin(1L, id, request("same", "question"));
        assertEquals(409, assertThrows(BusinessException.class, () -> service.begin(1L, id, request("next", "next"))).getStatus());
        assertEquals(409, assertThrows(BusinessException.class, () -> service.delete(1L, id)).getStatus());
        service.save(turn, "answer", "COMPLETED", null);
        assertEquals(409, assertThrows(BusinessException.class, () -> service.begin(1L, id, request("same", "question"))).getStatus());
        assertEquals(2, messages.count());
    }

    /** 进程异常后的过期租约恢复且未完成轮次不会污染下一次上下文。 */
    @Test void recoversExpiredLeaseAndExcludesPartialTurns() {
        Long id = service.create(1L).id();
        var turn = service.begin(1L, id, request("old", "question"));
        service.save(turn, "partial", "GENERATING", null);
        new TransactionTemplate(manager).executeWithoutResult(status -> conversations.findById(id).orElseThrow().leaseUntil = Instant.now().minusSeconds(1));
        var detail = service.detail(1L, id);
        assertFalse(detail.conversation().generating());
        assertEquals("INTERRUPTED", detail.messages().get(1).status());
        assertEquals("partial", detail.messages().get(1).content().asText());
        var next = service.begin(1L, id, request("new", "next"));
        assertEquals(2, next.context().size());
        service.save(turn, "stale overwrite", "COMPLETED", null);
        assertTrue(service.detail(1L, id).conversation().generating());
    }

    /** 空白和超长消息必须在写数据库之前拒绝。 */
    @ParameterizedTest @ValueSource(strings = {"", " ", "\n", "oversize"})
    void rejectsInvalidText(String text) {
        Long id = service.create(1L).id();
        String input = "oversize".equals(text) ? "a".repeat(100001) : text;
        assertThrows(BusinessException.class, () -> service.begin(1L, id, request("id", input)));
        assertEquals(0, messages.count());
    }

    /** 分页限制、空结果和跨页顺序正确。 */
    @Test void paginates() {
        assertEquals(0L, service.list(1L, 0).get("total"));
        for (int index = 0; index < 21; index++) service.create(1L);
        assertEquals(20, ((List<?>) service.list(1L, 0).get("items")).size());
        assertEquals(1, ((List<?>) service.list(1L, 1).get("items")).size());
        assertThrows(BusinessException.class, () -> service.list(1L, -1));
    }

    /** 多模态内容和模型设置可完整恢复。 */
    @Test void restoresVisionContentAndSettings() throws Exception {
        Long id = service.create(1L).id();
        var content = mapper.readTree("[{\"type\":\"text\",\"text\":\"图片\"},{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,YQ==\"}}]");
        var settings = new ChatConversationService.Settings("vision_model", null, 7L, true, "HIGH", "视觉提示词");
        var turn = service.begin(1L, id, new ChatConversationService.SendRequest("vision", content, settings));
        service.save(turn, "图片回答", "COMPLETED", null);
        var detail = service.detail(1L, id);
        assertEquals(content, detail.messages().get(0).content());
        assertEquals("视觉提示词", detail.settings().path("systemPrompt").asText());
        assertEquals(7, detail.settings().path("modelId").asLong());
    }

    /** 无效结构、远程图片和超限参数不能产生持久化副作用。 */
    @ParameterizedTest @ValueSource(strings = {"null", "{}", "[]", "[{\"type\":\"tool\"}]",
        "[{\"type\":\"image_url\",\"image_url\":{\"url\":\"https://outside.example/image.png\"}}]",
        "[{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,\"}}]",
        "[{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,!!!\"}}]"})
    void rejectsInvalidContent(String json) throws Exception {
        Long id = service.create(1L).id();
        var settings = new ChatConversationService.Settings("vision_model", "chat", null, false, "LOW", "");
        var request = new ChatConversationService.SendRequest("invalid", mapper.readTree(json), settings);
        assertThrows(BusinessException.class, () -> service.begin(1L, id, request));
        assertEquals(0, messages.count());
    }

    /** 输入最大值可保存，超过 Worker 历史上限时明确拒绝而不写入失败轮次。 */
    @Test void enforcesContextBoundary() {
        Long id = service.create(1L).id();
        for (int index = 0; index < 50; index++) {
            var turn = service.begin(1L, id, request("turn-" + index, index == 0 ? "x".repeat(100000) : "hello"));
            service.save(turn, "answer", "COMPLETED", null);
        }
        assertEquals(413, assertThrows(BusinessException.class, () -> service.begin(1L, id, request("overflow", "next"))).getStatus());
        assertEquals(100, messages.count());
    }

    /** 两个独立事务并发发问只能创建一个轮次。 */
    @Test void concurrentRequestsCreateOneTurn() throws Exception {
        Long id = service.create(1L).id();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Callable<Boolean> send = () -> {
            gate.await();
            try { service.begin(1L, id, request(UUID.randomUUID().toString(), "question")); return true; }
            catch (BusinessException exception) { assertEquals(409, exception.getStatus()); return false; }
        };
        try {
            var first = executor.submit(send); var second = executor.submit(send); gate.countDown();
            assertNotEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertEquals(2, messages.count());
        } finally { executor.shutdownNow(); }
    }

    /** 真实 Controller、SSE 序列化和数据库共同验证成功与部分失败终态。 */
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void controllerPersistsStreamOutcome(boolean fails) throws Exception {
        var client = org.mockito.Mockito.mock(ChatStreamClient.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            ChatStreamClient.EventConsumer consumer = invocation.getArgument(1);
            consumer.accept(mapper.readTree("{\"type\":\"delta\",\"content\":\"部分回答\"}"));
            if (fails) throw new IllegalStateException("upstream-secret-must-not-leak");
            consumer.accept(mapper.readTree("{\"type\":\"done\",\"model\":\"test-model\",\"totalTokens\":3}"));
            return null;
        }).when(client).stream(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        var controller = new ChatConversationController(service, client, mapper);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        com.baseai.platform.security.AuthContext.set(new com.baseai.platform.security.AuthUser(1L, "user", Set.of(), Set.of("ai:chat:invoke"),
            com.baseai.platform.security.AuthenticationType.TOKEN, null, null));
        try {
            Long id = service.create(1L).id();
            var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/ai/conversations/" + id + "/messages/stream").contentType("application/json")
                .content(mapper.writeValueAsString(request("stream", "question")))).andReturn().getResponse();
            assertEquals(200, response.getStatus());
            assertTrue(response.getContentType().startsWith("text/event-stream"));
            String body = response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(body.contains(fails ? "\"type\":\"error\"" : "\"type\":\"done\""));
            assertFalse(body.contains("upstream-secret"));
            var detail = service.detail(1L, id);
            assertEquals("部分回答", detail.messages().get(1).content().asText());
            assertEquals(fails ? "INTERRUPTED" : "COMPLETED", detail.messages().get(1).status());
            assertFalse(detail.conversation().generating());
        } finally { com.baseai.platform.security.AuthContext.clear(); }
    }
}
