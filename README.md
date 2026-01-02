# AWS ECR Demo Projects (Spring Boot)

- 이 저장소는 AWS ECR 연동 데모를 **2가지 방식**으로 제공합니다.
- 각 방식은 별도 브랜치에서 독립적으로 관리됩니다.

## Branch Policy

- `main` 브랜치는 문서(README) 전용 브랜치입니다.
- 각 `feat/*` 브랜치는 서로 독립적인 데모 프로젝트이며 `main` 브랜치로 병합하지 않습니다.
- 본 저장소는 Pull Request 병합을 전제로 하지 않습니다.

---

## 브랜치 구성

| 브랜치 | 프로젝트명 | 핵심 방식 | Docker 필요 | 주요 산출물 |
|---|---|---|---|---|
| `feat/ecr-docker-cli-pull-demo` | ECR Docker CLI Pull Demo | Docker CLI(ProcessBuilder)로 `docker pull` 실행 | 필요 | 로컬 Docker Engine 이미지 |
| `feat/ecr-layer-stream-downloader` | ECR Layer Stream Downloader | ECR API + HTTP Stream으로 레이어/manifest 저장 | 레이어 다운로드는 불필요 (export는 로컬 tar 생성) | `./out`에 manifest/blobs/config + tar(export) |

---

## 브랜치 상세 설명

### 1) “운영 환경과 동일한 docker pull”
- 브랜치: `feat/ecr-docker-cli-pull-demo`
- 특징:
  - `docker login / docker pull`을 실제로 실행
  - Docker Desktop/Engine 필수
  - 로컬 이미지 조회/삭제까지 포함

### 2) “Docker 없이 레이어를 받아서 파일로 보관/가공”
- 브랜치: `feat/ecr-layer-stream-downloader`
- 특징:
  - manifest / layer blobs / config(옵션) 저장
  - SHA256 검증(옵션)
  - 저장된 결과를 기반으로 docker save 포맷 tar(export) 생성

---

## 공통 실행 환경

- Java 21
- AWS ECR 접근 가능한 IAM User (Access Key / Secret Key)
- ECR 네트워크 접근 가능

로컬 테스트 환경(공통)
- Windows 11 Pro
- Java 21.0.9 (IntelliJ 내장 Amazon Corretto)
- Spring Boot 3.5.3
- AWS SDK ECR 2.32.7

※ Docker CLI Pull Demo 브랜치는 추가로 Docker Desktop 29.1.3을 사용합니다.

---

## 시작 방법

각 브랜치로 이동한 뒤, 해당 브랜치의 README를 참고하세요.

- `feat/ecr-docker-cli-pull-demo` → Docker CLI 기반 pull 데모
- `feat/ecr-layer-stream-downloader` → 레이어 스트리밍 다운로드 + tar export

---
