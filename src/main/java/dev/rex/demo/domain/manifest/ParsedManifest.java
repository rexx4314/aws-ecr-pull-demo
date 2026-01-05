package dev.rex.demo.domain.manifest;

import java.util.List;

/**
 * 파싱된 Docker/OCI 이미지 매니페스트 정보를 담는 불변 DTO
 *
 * <p>
 * 설계 의도:
 * - ManifestParser의 파싱 결과를 명확하게 표현하기 위한 값 객체
 * - record를 사용하여 불변성 + 단순 구조 유지
 * - "manifest list 여부"를 명시적으로 포함하여
 *   상위 레이어(Service/Application)에서 정책 판단이 가능하도록 함
 *
 * @param manifestList
 *        true  -> manifest list (멀티 아키텍처, index)
 *        false -> 단일 이미지 manifest
 *
 * @param layerDigests
 *        단일 manifest일 경우:
 *        - layers[].digest 값 목록
 *        - docker pull/save/export 시 실제로 필요한 핵심 데이터
 *
 *        manifest list일 경우:
 *        - 정책상 단일 manifest가 아니므로 빈 리스트
 *
 * @param configDigest
 *        이미지 config.digest
 *        - 단일 manifest일 때만 의미 있음
 *        - 스키마/형식에 따라 null일 수 있음
 */
public record ParsedManifest(
        boolean manifestList,
        List<String> layerDigests,
        String configDigest
) {

    /**
     * 이 매니페스트가 manifest list(멀티 아키텍처 인덱스)인지 여부
     *
     * <p>
     * - true 인 경우:
     *   - docker save / export 등에서 바로 사용할 수 없음
     *   - platform 선택 로직이 필요
     *
     * @return manifest list이면 true
     */
    public boolean isManifestList() {
        return manifestList;
    }
}
