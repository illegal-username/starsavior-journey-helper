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

변경 범위에 맞는 검증을 선택합니다. `python3` 대신 Windows에서는 `python`을 사용할 수 있습니다.

| 변경 범위 | 최종 검증 |
| --- | --- |
| 문서·검증 도구 | `python3 tools/verify.py` |
| 앱 코드·리소스·DB 파서·Gradle·CI | `python3 tools/verify.py --scope android` |
| 스태미나 판독·경계·안정화 | Windows에서 `python tools/verify.py --scope stamina --corpus-root <외부-코퍼스-경로>` |
| 후보 DB를 담은 기기 시험 | 아래 internal 빌드 명령; 별도 debug APK는 필요할 때만 생성 |

모든 범위는 공개 소스 검사, Python 도구 테스트, staged/unstaged 공백 검사를 수행합니다. Android 범위는 `testDebugUnitTest lintRelease assembleDebug`를 한 번 실행합니다. Windows에서는 설치된 프로젝트 Gradle 래퍼를 우선 사용합니다. CI도 같은 Android 명령을 사용하며 서명 키·실제 DB는 필요하지 않습니다.

수정 중에는 `./gradlew testDebugUnitTest --tests 'helper.journey.starsavior.변경대상Test'`처럼 관련 테스트부터 실행하세요. 최종 변경 묶음이 통과하면 새 변경·실패·미해결 의문이 없는 한 같은 검사를 반복하지 않습니다. `clean`, `--rerun-tasks`, 모든 variant의 `test lint`는 기본 절차가 아닙니다. internal variant는 별도 입력과 서명 구성이 필요합니다.

`--plan`은 실행 예정 명령만 표시합니다. 실행 결과는 Git 제외 경로 `.gradle/verification/latest.json`에 범위, HEAD, 추적/새 소스 내용 지문, 명령, 종료 코드, 시간으로 남습니다. 이는 검증 근거이며 자동 생략 캐시는 아닙니다. 소스나 데이터가 바뀌었다면 과거 성공을 재사용하지 않습니다. 스태미나 보고서는 지정한 외부 코퍼스의 `reports/`에 생성됩니다.

기존 검증 보고서를 보존하려면 `python tools/verify.py --scope android --report <새-보고서-경로>`를 사용합니다.
보고서는 저장소 밖이나 Git 제외 경로에 두세요. 이 옵션은 기존 파일 덮어쓰기를 거부하고 기본 `latest.json`을 변경하지 않습니다. 실패 결과도 지정한 새 보고서에 기록합니다.
`--plan`은 `--report`를 함께 지정해도 파일을 만들지 않습니다. 실제 검사에서는 빌드·캐시 산출물이 생기지만 소스 동기화, 후보 포인터 변경, 커밋, 배포를 수행하지 않습니다.

스태미나 합격 기준은 사용자가 직접 주석한 `annotated/**/annotations.tsv` 전체이며 값·변화량·쌍 비교 허용오차는 1입니다. `expectations.tsv`는 추정값 진단용입니다. 다른 HUD 크기·화면비·위치의 합성 사례도 검증하고, 실제 게임 화면은 공개 저장소에 넣지 않습니다.

실제 게임 데이터 대신 `journey_choices.example.json`과 합성 데이터를 사용해 주세요. 화면/네트워크의 개인정보 처리 방식이 바뀌면 `PRIVACY.md`와 앱 내 안내도 함께 갱신합니다.

DB·메타 생성은 별도 데이터 생성 프로젝트에서 수행하고 Android는 완성된 compact JSON만 소비합니다. 사용하지 않는 앱 내 6문서 변환기와 소스 assets에 실제 DB를 쓰던 개발 스크립트는 제거했습니다. 후보 DB는 아래 internal 빌드에 외부 파일로 전달하세요.

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
- `sameProgress`는 생성기가 검증한 분기 없는 응답 화면입니다. 한 이벤트 안의 일반 대화와 보상 선택도 서로 다른 레코드로 매칭하며, 대화에 뒤쪽 선택의 보상을 붙이지 않습니다. 새 컷신 구조는 검증 후 생성 규칙을 확장합니다.
- 이벤트 확정에는 최소 두 선택지의 문구 근거가 필요합니다. 공통 선택지 하나만 일치한 다른 이벤트로 대체하지 않습니다.
- 정식 APK는 실제 DB 없이 빌드하고 APK 내부도 확인합니다. 새 앱을 공개한 다음 호환 최소 버전이 올라간 Pages DB와 메타를 함께 게시해, 아직 받을 수 없는 앱 업데이트를 요구하지 않도록 합니다.

## 다국어 데이터 계약

앱은 선택한 언어 하나에 해당하는 OCR과 DB를 사용합니다. 기본값은 시스템 언어이며 미지원 언어는 영어로 연결합니다. `GameLanguage`가 언어 정규화와 배포 URL을 정의합니다.
새 DB는 schema 5의 `language`, 메타는 schema 2의 같은 `language`를 필수로 가집니다.
배포 경로는 `v5/<language>/journey_choices.json`과 같은 폴더의 메타입니다.
한국어 구형 DB(schema 4)는 로컬 호환 목적으로 읽으며 다른 언어로 재사용하지 않습니다.
언어를 바꾸거나 번역을 추가할 때는 선택 문구·aliases·원본 화면 누락 검사·숫자 조건·메타/캐시 격리를 함께 검증하세요.

`values`는 영어 기본 리소스입니다. `values-ko`, `values-ja`, `values-b+zh+Hant`, `values-b+zh+Hans`, `values-fr/de/es/pt/in/vi`에 같은 사용자 메시지 키를 둡니다.
문장 전체와 위치 인자를 사용하고, 게임 관련 명사는 추출본의 동일 문자열 키를 대조합니다.
번역 키·형식 인자 검사는 `tools/tests/test_localization_resources.py`에 포함됩니다.
내부 예외의 기술 진단은 영어이며, 사용자 복구 안내는 선택 언어로 표시합니다.

internal의 `-DatabasePath`는 단일 schema 5 DB 또는 언어별 DB들이 있는 외부 폴더를 받을 수 있습니다.
언어가 중복된 입력은 거부하고 입력에 없는 언어로 임의 폴백하지 않습니다.
모든 빌드의 **게임·도우미 언어**에서 시스템 언어 또는 지원 언어를 선택합니다. 설정은 저장되며 앱 표시·OCR·DB를 함께 바꿉니다. 적용 언어 변경 시 화면 공유를 다시 허용해야 합니다. 이전 내부 시험판의 언어 설정은 첫 실행 시 호환합니다.
internal 빌드는 화면 공유 허용 후 이전 화면으로 돌아가므로 갤러리에서 이미지 테스트를 할 수 있습니다.
실제 다국어 DB를 공개 assets에 넣지 말고 앱과 데이터를 검증한 후 별도 승인된 배포 순서를 따르세요.
