# 기여 안내

버그 수정과 문서 개선을 환영합니다. Pull Request를 만들기 전에 다음 원칙을 지켜 주세요.

## 저장소에 올리면 안 되는 항목

- 실제 `journey_choices.json` 또는 원자료 JSON 사본
- `.jks`, `.keystore`, `.p12` 운영·개발 키
- `keystore.properties`, 비밀번호, 토큰, 개인 경로
- 게임 스크린샷, 캐릭터 이미지, 음원 또는 추출 자산
- 계정 정보나 개인정보가 포함된 로그
- 드라이브명이나 사용자 홈에서 시작하는 로컬 절대경로와 비공개 공유 링크. 저장소 안의 파일은 저장소 루트 기준 상대경로로 표기해 주세요.

## 검사

```bash
python3 tools/check_public_source.py
./gradlew testDebugUnitTest lintRelease assembleDebug
```

실제 게임 데이터 대신 `journey_choices.example.json`과 합성 테스트 데이터를 사용해 주세요. 화면 처리나 네트워크 동작을 변경했다면 `PRIVACY.md`와 앱 내 안내도 함께 갱신해야 합니다.

## 내장 DB 테스트 APK

배포 전 후보 DB를 Pages에 먼저 게시하지 말고, 저장소 밖의 compact DB를 `internal` 빌드에만 주입합니다.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\build_bundled_db_test.ps1 -DatabasePath <외부-DB-경로> -VersionCode <설치가능한-코드>
```

이 빌드는 기존 다운로드 DB보다 내장 테스트 DB를 우선하며 원격 DB 확인·교체를 비활성화합니다. DB는 `build/` 아래의 생성 자산으로만 복사됩니다. 정식 `release` 빌드는 `main` 또는 `release` 소스 세트에 실제 DB가 있으면 실패하며, 공개 소스 검사도 모든 Android 소스 세트의 실제 `journey_choices.json`을 거부합니다.

커밋하기 전 `git status`, `git diff --cached`로 포함 파일을 직접 확인하세요.

## 데이터 기반 인식과 배포 순서

- 일반·단축 문구는 DB의 `text`와 `aliases`에서 비교하며, 화면 OCR에 가장 잘 맞는 등록 문구를 결과 제목으로 사용합니다. 별칭 개수나 특정 이벤트명에 분기하지 않습니다.
- 아르카나 후보는 outcome의 `arcanaIds` 전체에서 수집합니다. 후보가 3개 이상으로 늘어도 비교하고, 특징이 누락되거나 판정이 불충분하면 전체 결과로 돌아갑니다.
- `sameProgress`는 생성기가 확인한 단일 응답 화면과 0개/1개 공통 보상 구조만 포함합니다. 새 컷신 구조는 검증 후 생성 규칙을 확장합니다.
- 정식 APK는 실제 DB 없이 빌드하고 APK 내부도 확인합니다. 새 앱을 공개한 다음 호환 최소 버전이 올라간 Pages DB와 메타를 함께 게시해, 아직 받을 수 없는 앱 업데이트를 요구하지 않도록 합니다.
