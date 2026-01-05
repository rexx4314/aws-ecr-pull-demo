package dev.rex.demo.common.error;

import java.util.Map;

/**
 * API 예외를 표현하는 런타임 예외 클래스
 * <p>
 * ErrorCode와 추가적인 details(필요한 경우)를 포함하여
 * 서비스/컨트롤러 계층에서 클라이언트에 전달할 에러 정보를 캡슐화함
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final Map<String, Object> details;

    /**
     * 기본 생성자
     *
     * @param code    에러 코드
     * @param message 사용자에게 표시할 간단한 메시지
     */
    public ApiException(ErrorCode code, String message) {
        this(code, message, null, null);
    }

    /**
     * 상세 정보를 포함한 생성자
     *
     * @param code    에러 코드
     * @param message 사용자 메시지
     * @param details 추가적인 에러 상세 정보(필드별 오류 등), 필요없으면 null
     */
    public ApiException(ErrorCode code, String message, Map<String, Object> details) {
        this(code, message, details, null);
    }

    /**
     * 원인(Throwable)을 포함한 생성자
     *
     * @param code    에러 코드
     * @param message 사용자 메시지
     * @param details 추가적인 에러 상세 정보
     * @param cause   원인 예외 (있다면 기록)
     */
    public ApiException(ErrorCode code, String message, Map<String, Object> details, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.details = details;
    }

    /**
     * 예외에 연결된 ErrorCode를 반환
     *
     * @return ErrorCode
     */
    public ErrorCode code() {
        return code;
    }

    /**
     * 예외의 추가적인 상세 정보를 반환
     *
     * @return details 맵 (없으면 null)
     */
    public Map<String, Object> details() {
        return details;
    }
}
