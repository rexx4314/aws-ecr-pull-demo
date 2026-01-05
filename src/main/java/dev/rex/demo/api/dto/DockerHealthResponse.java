package dev.rex.demo.api.dto;

/**
 * Docker 컨테이너의 헬스 체크 응답을 나타내는 DTO 레코드
 *
 * @param ok      상태 정상 여부 ( 정상)
 * @param message 상태 설명 또는 에러 메시지
 */
public record DockerHealthResponse(
        boolean ok,
        String message
) {
}
