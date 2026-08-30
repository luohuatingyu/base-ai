package com.baseai.platform.document;

import java.util.Map;

/** 不可信文档解析的隔离边界。 */
public interface DocumentParser {
    /** 在独立解析进程中提取正文和有限元数据。 */
    Result parse(byte[] content, String fileName, int maximumCharacters);

    /** 解析成功后返回不可变正文与元数据。 */
    record Result(String text, Map<String, String> metadata) {
        /** 复制元数据，避免调用方修改跨进程校验后的结果。 */
        public Result {
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }

    /** 对外只暴露稳定失败类型，不泄漏解析器或文件细节。 */
    final class ParseException extends RuntimeException {
        private final Reason reason;

        /** 保存稳定失败原因。 */
        public ParseException(Reason reason) {
            super(reason.name());
            this.reason = reason;
        }

        /** 保存稳定失败原因并关联内部异常。 */
        public ParseException(Reason reason, Throwable cause) {
            super(reason.name(), cause);
            this.reason = reason;
        }

        /** 返回调用方可安全映射的失败原因。 */
        public Reason reason() { return reason; }
    }

    /** 跨业务模块共享的有限失败分类。 */
    enum Reason { EMPTY, TOO_LARGE, INVALID, TIMEOUT, UNAVAILABLE }
}
