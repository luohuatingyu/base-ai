package com.baseai.platform.knowledge;

import com.baseai.platform.common.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeBaseServiceChunkTest {
    /** 切片必须受最大字符限制并保留相邻窗口重叠。 */
    @Test void chunksLongTextWithBoundedOverlap(){String content="a".repeat(120)+"\n"+"b".repeat(120);List<String> chunks=KnowledgeBaseService.chunks(content,100,20);assertTrue(chunks.size()>=3);assertTrue(chunks.stream().allMatch(item->item.length()<=100));assertTrue(chunks.get(0).endsWith(chunks.get(1).substring(0,Math.min(20,chunks.get(1).length()))));}
    /** 空白文档不能产生没有业务内容的向量。 */
    @Test void rejectsBlankDocument(){BusinessException exception=assertThrows(BusinessException.class,()->KnowledgeBaseService.chunks(" \n ",100,10));assertEquals("knowledge.documentEmpty",exception.getMessageKey());}
}
