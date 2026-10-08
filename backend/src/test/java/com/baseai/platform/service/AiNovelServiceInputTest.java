package com.baseai.platform.service;

import com.baseai.platform.common.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import static org.mockito.Mockito.mock;

/** 验证原著服务对章节序号和参考资料排序的数字输入不会静默截断。 */
class AiNovelServiceInputTest {
    /** 小数输入必须拒绝，避免章节顺序或父章节归属产生歧义。 */
    @Test
    void rejectsFractionalIntegers() throws Exception {
        AiNovelService service = new AiNovelService(mock(JdbcTemplate.class), mock(AiChatClient.class), new ObjectMapper());
        Method integral = AiNovelService.class.getDeclaredMethod("integral", Object.class, int.class, String.class);
        integral.setAccessible(true);
        InvocationTargetException error = assertThrows(InvocationTargetException.class,
            () -> integral.invoke(service, 1.5D, 0, "chapterNo"));
        assertEquals(BusinessException.class, error.getCause().getClass());
    }
}
