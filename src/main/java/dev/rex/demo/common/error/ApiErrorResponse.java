package dev.rex.demo.common.error;

import java.time.Instant;
import java.util.Map;

/**
 * API 오류 응답을 표현하는 DTO 레코드
 * <p>
 * 클라이언트에 반환되는 에러 정보(코드, 메시지, 상세, 타임스탬프, 요청 경로)를 포함
 *
 * @param code      에러 코드 문자열 (예: "INVALID_REQUEST")
 * @param message   사용자에게 표시할 간단한 에러 메시지
 * @param details   추가적인 에러 상세 정보(필드별 오류 등), 필요시 null 허용
 * @param timestamp 오류 발생 시각
 * @param path      요청된 리소스의 경로(예: /api/ecr/download)
 */
public record ApiErrorResponse(
        String code,
        String message,
        Map<String, Object> details,
        Instant timestamp,
        String path
) {
}
