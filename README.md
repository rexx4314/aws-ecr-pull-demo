# ECR Docker CLI Pull Demo (Spring Boot)

## 1. 프로젝트 개요

* AWS ECR Repository 전체 조회
* Repository별 최신 tag 계산
* AWS 인증 정보 기반 실제 `docker pull` 실행
* 로컬 Docker 이미지 조회
* 로컬 Docker 이미지 삭제
* 모든 동작을 REST API로 제어

※ 본 프로젝트는 Docker CLI(ProcessBuilder) 기반으로 실제 docker pull을 실행하는 데모입니다.

---

## 2. 아키텍처 개요

```
Client (IntelliJ HTTP Client)
        ↓
Spring Boot API
        ↓
AWS SDK (ECR)
 - DescribeRepositories
 - DescribeImages
        ↓
Docker CLI
 - docker login
 - docker pull
 - docker images
 - docker rmi
        ↓
Local Docker Engine
```

* 이미지 저장 위치: 로컬 Docker 엔진
* 애플리케이션 내부 저장 없음
* 프로젝트 디렉터리 파일 생성 없음

※ Docker CLI 호출은 Java ProcessBuilder를 통해 실행됩니다.

---

## 3. 실행 환경

### 3.1 시스템 요구 사항

* Java 21
* Docker Desktop 또는 Docker Engine
* AWS ECR 접근 가능한 IAM User
* Access Key / Secret Key
* ECR 네트워크 접근 가능

#### 로컬 테스트 환경

> * Windows 11 Pro
> * Java 21.0.9 (IntelliJ 내장 Amazon Corretto)
> * Spring Boot 3.5.3
> * AWS SDK ECR 2.32.7
> * Docker Desktop 29.1.3

※ Docker Desktop 또는 Docker Engine 필수 (docker pull 기반 데모)

### 3.2 Docker 사전 조건

```bash
docker version
docker ps
```

* 위 명령 정상 동작 필수

---

## 4. API 목록

### 4.1 Docker Health Check

* 목적: Docker 데몬 동작 여부 확인

```
GET /api/health/docker
```

응답 예시:

```json
{
  "ok": true,
  "message": "Docker OK (server=29.1.3)"
}
```

---

### 4.2 ECR Repository + 최신 tag 조회

* 입력값:

    * region
    * accountId
    * accessKeyId
    * secretAccessKey
* 처리:

    * 모든 Repository 조회
    * Repository별 최신 tag 계산
    * 이미지 없는 Repository는 pull 불가 처리

```
POST /api/ecr/repositories
```

요청 예시:

```json
{
  "region": "ap-northeast-2",
  "accountId": "194356581254",
  "accessKeyId": "AKIA...",
  "secretAccessKey": "xxxx"
}
```

응답 요약:

```json
{
  "repositoryName": "demo/rex-repo",
  "latestTag": "v1",
  "pullable": true
}
```

* pullable=false 조건

    * 이미지 없음
    * 최신 tag 판단 불가

---

### 4.3 ECR 이미지 Pull

* 입력값:

    * region
    * accountId
    * accessKeyId
    * secretAccessKey
    * repositoryName
    * tag
* 처리:

    * ECR 로그인
    * docker login
    * docker pull
    * digest 확인

```
POST /api/ecr/pull
```

응답 예시:

```json
{
  "imageRef": ".../rex-repo:v1",
  "resolvedTag": "v1",
  "digest": "...@sha256:...",
  "message": "SUCCESS"
}
```

* 이미지 저장 위치: 로컬 Docker 엔진
* 애플리케이션 파일 생성 없음

---

### 4.4 로컬 Docker 이미지 조회

* 전체 조회

```
GET /api/docker/images
```

* Repository 필터 조회

```
GET /api/docker/images?containsRepo=demo/rex-repo
```

응답 예시:

```json
{
  "repository": ".../rex-repo",
  "tag": "v1",
  "imageId": "547c8c6863a8",
  "size": "123MB"
}
```

---

### 4.5 로컬 Docker 이미지 삭제

```
DELETE /api/docker/images
```

요청 예시:

```json
{
  "imageRef": ".../rex-repo:v1",
  "force": false
}
```

* 삭제 기준

    * repo:tag
    * 또는 repo@sha256:digest

---

## 5. IntelliJ HTTP Client 테스트 순서

1. `/api/health/docker`
2. `/api/ecr/repositories`
3. pullable=true repo 선택
4. `/api/ecr/pull`
5. `/api/docker/images`
6. `/api/docker/images` (DELETE, 선택)

> resources/script/ecr-pull-test.http

---
