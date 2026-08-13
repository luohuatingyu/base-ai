package com.baseai.platform.workflow;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkflowMessageTriggerManagerTest {
    /** 同一分区首个失败之后不得继续投递或推进 Offset。 */
    @Test
    void stopsPartitionAtFirstFailedDelivery() {
        List<Long> attempted = new ArrayList<>();

        long nextOffset = WorkflowMessageTriggerManager.deliverContiguous(List.of(10L, 11L, 12L), offset -> {
            attempted.add(offset);
            return offset != 11L;
        }, offset -> offset + 1L);

        assertEquals(List.of(10L, 11L), attempted);
        assertEquals(11L, nextOffset);
    }
}
