# ECR Layer Stream Downloader (Spring Boot)

## 1. 프로젝트 개요

* AWS ECR Repository 전체 조회
* Repository별 최신 tag 계산
* ECR 이미지 구성 요소 다운로드
    * manifest.json
    * layer blobs
    * config blob (옵션)
* 다운로드 결과를 기반으로 docker save tar(export) 생성
* 모든 기능을 REST API로 제어

---

## 2. 아키텍처 개요

```
Client (IntelliJ HTTP Client)
        ↓
Spring Boot API
        ↓
AWS SDK (ECR)
 - DescribeRepositories
 - ListImages / DescribeImages        (scan: 최신 tag 계산)
 - BatchGetImage                     (manifest 조회)
 - GetAuthorizationToken             (ECR Basic token)
 - GetDownloadUrlForLayer            (layer/config 다운로드 URL)
        ↓
HTTP Stream Download (Pre-signed URL)
 - manifest.json 저장
 - blobs(레이어) 저장
 - config.json 저장(옵션)
        ↓
Local File System (./out)
        ↓
Export (Docker Save Tar)
 - 로컬(out) 기반 tar 생성
 - tar 스트리밍 다운로드
```

* 다운로드 산출물 저장 위치: 프로젝트 디렉터리 `./out`
* 애플리케이션 내부 DB 저장 없음
* Docker CLI/Engine은 **레이어 다운로드에는 필요 없음**
* docker save tar(export)는 다운로드 결과를 기반으로 생성됨

---

## 3. 실행 환경

### 3.1 시스템 요구 사항

* Java 21
* AWS ECR 접근 가능한 IAM User
* Access Key / Secret Key
* ECR 네트워크 접근 가능

### 3.2 로컬 테스트 환경

> * Windows 11 Pro
> * Java 21.0.9 (IntelliJ 내장 Amazon Corretto)
> * Spring Boot 3.5.3
> * AWS SDK ECR 2.32.7

### 3.3 추가 사항

* IntelliJ HTTP Client 사용
* Scan Cache TTL: 2 minutes (`EcrController.SCAN_CACHE_TTL`)
* 기본 outputDir: `./out` (요청에서 변경 가능)

---

## 4. 로컬 저장 구조

기본 저장 경로: `./out`

※ digest-{digest}/ 디렉터리는 digest 기준으로 다운로드 요청한 경우에만 생성됩니다.

```
out/
 └─ {accountId}/
    └─ {region}/
       └─ {repositoryName}/
          ├─ tag-{tag}/
          │  │  manifest.json
          │  │  config.json                (includeConfig=true)
          │  │
          │  ├─ blobs/
          │  │  └─ sha256/
          │  │        <digest1>
          │  │        <digest2>
          │  │        ...
          │  │
          │  └─ export/
          │     └─ docker-save/
          │           docker-save_<tag>.tar
          │
          └─ digest-{digest}/
             │  manifest.json
             │  config.json                (includeConfig=true)
             │
             ├─ blobs/
             │  └─ sha256/
             │        <digest1>
             │        <digest2>
             │        ...
             │
             └─ export/
                └─ docker-save/
                      docker-save_<hint>.tar
```

저장되는 파일:

* manifest.json : ECR에서 조회한 원본 manifest
* blobs/sha256-* : layer blob (스트리밍 다운로드 결과)
* config.json : image config blob (옵션)

---

## 5. API 목록

### 5.1 ECR Repository + 최신 tag 조회 (Scan)

* Repository 전체 조회
* Repository별 최신 tag 계산
* download 가능 여부 판단

```
POST /api/ecr/scan
```

요청 예시

```json
{
  "region": "ap-northeast-2",
  "accountId": "194356581254",
  "accessKeyId": "AKIA...",
  "secretAccessKey": "xxxx"
}
```

응답 예시(요약)

```json
{
  "items": [
    {
      "repositoryName": "demo/rex-repo",
      "latestTag": "v1",
      "pullable": true,
      "reason": null,
      "lastPushedAt": "2025-12-31T12:34:56Z"
    }
  ]
}
```

pullable=false 사유 예시

* NO_IMAGES_IN_REPOSITORY
* TAGGED_EXISTS_BUT_PUSHED_AT_UNKNOWN
* SCAN_TIMED_OUT_OR_LIMITED
* LATEST_IMAGE_HAS_NO_TAGS
* REPOSITORY_NOT_FOUND
* 기타 → REPO_NOT_PULLABLE

---

### 5.2 레이어 다운로드 (Stream Download)

* ECR에서 manifest / layer / config를 조회
* HTTP Stream 방식으로 로컬 파일 저장

※ Manifest list(멀티 아키텍처)는 platform 선택 옵션이 없으며,
현재는 단일 manifest 기준으로만 처리됩니다.

```
POST /api/ecr/download
```

요청 예시

```json
{
  "region": "ap-northeast-2",
  "accountId": "194356581254",
  "accessKeyId": "AKIA...",
  "secretAccessKey": "xxxx",
  "repositoryName": "demo/rex-repo",
  "tag": "v1",
  "resolveLatest": false,
  "includeConfig": true,
  "verifySha256": true,
  "concurrency": 4,
  "maxRetries": 4,
  "httpTimeoutSeconds": 180,
  "outputDir": "./out",
  "maxPages": 50,
  "maxImages": 2000
}
```

응답 예시

```json
{
  "resolvedTag": "v1",
  "resolvedDigest": "sha256:....",
  "layerCount": 5,
  "downloadedCount": 5,
  "outputPath": ".../out/.../tag-v1",
  "manifestPath": ".../manifest.json",
  "configPath": ".../config.json",
  "message": "OK"
}
```

특징

* scan 결과를 2분 TTL 캐시에 저장하여 재사용
* pullable=false Repository는 다운로드 차단
* resolveLatest=false + tag 직접 지정 시

    * 최신 tag가 아니면 TAG_NOT_LATEST 오류 반환

---

### 5.3 docker save tar(export)

* 로컬(out)에 저장된 다운로드 결과를 기반으로
* docker save tar 생성 후 스트리밍 다운로드

※ export로 생성되는 tar는 docker load 가능한 docker save 포맷입니다.

```
POST /api/ecr/export/docker-save
```

요청 예시

```json
{
  "region": "ap-northeast-2",
  "accountId": "194356581254",
  "accessKeyId": "AKIA...",
  "secretAccessKey": "xxxx",
  "repositoryName": "demo/rex-repo",
  "tag": "v1",
  "resolveLatest": false,
  "includeConfig": true,
  "verifySha256": true,
  "outputDir": "./out",
  "repoTag": "demo/rex-repo:v1",
  "fileNameHint": "docker-save_v1.tar",
  "httpTimeoutSeconds": 180,
  "maxRetries": 4,
  "maxPages": 50,
  "maxImages": 2000
}
```

응답 헤더 예시

* Content-Type: application/x-tar
* Content-Disposition: attachment; filename="docker-save_v1.tar"
* X-Server-Tar-Path: 서버에 생성된 tar 절대 경로

---

## 6. IntelliJ HTTP Client 테스트 순서

1. /api/ecr/scan
2. pullable=true Repository 선택
3. /api/ecr/download
4. /api/ecr/export/docker-save

* IntelliJ의 **Save Response** 기능으로 tar 저장
* X-Server-Tar-Path 헤더로 서버 파일 위치 확인

테스트 스크립트

```
resources/script/ecr-layer-stream-test.http
```

---
