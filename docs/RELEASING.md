# 운영 APK 빌드와 배포

이 문서는 Google Play가 아닌 소규모 직접 배포용 절차입니다. 운영 키는 프로젝트, GitHub, 채팅 또는 공유 드라이브에 올리지 않습니다.

## 1. 최초 운영 키 생성

신뢰할 수 있는 PC에서 한 번만 실행합니다. 비밀번호는 명령줄 인수로 넣지 말고 `keytool`이 묻는 프롬프트에서 입력하세요.

```bash
keytool -genkeypair -v \
  -keystore /secure/path/starsavior-helper-release.jks \
  -alias starsavior-helper \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10950 \
  -storetype JKS
```

- 키와 비밀번호를 서로 분리된 암호화 위치에 두 벌 이상 백업합니다.
- 이 키를 잃으면 기존 설치본을 같은 앱으로 업데이트할 수 없습니다.
- 이 키가 유출되면 다른 사람이 악성 업데이트처럼 보이는 APK를 서명할 수 있습니다.
- 운영 키는 다른 앱과 공유하지 않습니다.

공개 가능한 인증서를 별도로 내보낼 수 있습니다.

```bash
keytool -exportcert -rfc \
  -keystore /secure/path/starsavior-helper-release.jks \
  -alias starsavior-helper \
  -file starsavior-helper-release-cert.pem
```

`release-cert.pem`에는 개인키가 없으므로 공개할 수 있지만 `.jks`는 절대 공개하면 안 됩니다.

## 2. 로컬 서명 설정

```bash
cp keystore.properties.example keystore.properties
```

복사한 파일을 다음과 같이 채웁니다.

```properties
storeFile=/secure/path/starsavior-helper-release.jks
storePassword=비밀번호
keyAlias=starsavior-helper
keyPassword=비밀번호
```

`keystore.properties`는 Git에서 제외됩니다. 환경변수 `STAR_JOURNEY_KEYSTORE`, `STAR_JOURNEY_STORE_PASSWORD`, `STAR_JOURNEY_KEY_ALIAS`, `STAR_JOURNEY_KEY_PASSWORD`를 대신 사용할 수도 있습니다.

## 3. 내장 DB 준비

원자료 운영자의 허가 범위 안에서 다음 명령으로 로컬 전용 DB를 만듭니다.

```bash
node tools/generate_journey_data.mjs
```

결과인 `app/src/main/assets/journey_choices.json`은 Git에서 제외됩니다. 이미 검증한 DB가 있다면 같은 경로에 직접 복사할 수 있습니다.

## 4. 검사와 빌드

```bash
./gradlew testDebugUnitTest lintRelease
./gradlew assembleRelease
```

서명 설정이 없거나 키 경로가 잘못되면 `assembleRelease`는 실패해야 정상입니다. 결과 APK는 일반적으로 다음 위치에 생성됩니다.

```text
app/build/outputs/apk/release/app-release.apk
```

## 5. 서명과 해시 확인

```bash
$ANDROID_HOME/build-tools/35.0.0/apksigner verify \
  --verbose --print-certs \
  app/build/outputs/apk/release/app-release.apk

sha256sum app/build/outputs/apk/release/app-release.apk
```

Windows PowerShell에서는 APK 해시를 다음처럼 확인할 수 있습니다.

```powershell
Get-FileHash .\app\build\outputs\apk\release\app-release.apk -Algorithm SHA256
```

출력된 APK SHA-256과 서명 인증서 SHA-256을 배포 안내에 함께 적습니다.

## 6. 공개 소스 준비

실제 DB를 공개 소스 패키지에서 제거한 뒤 검사합니다.

```bash
python3 tools/check_public_source.py
git status
git diff --cached
```

다음 파일은 commit하면 안 됩니다.

- `app/src/main/assets/journey_choices.json`
- `keystore.properties`
- 모든 `.jks`, `.keystore`, `.p12`
- APK와 AAB

GitHub Actions는 예제 DB로 테스트·린트·debug 빌드만 수행하며 운영 서명키를 사용하지 않습니다.

## 7. 배포

공개 저장소에는 source tag, 변경 기록, APK 해시와 인증서 지문을 게시합니다. APK 공유 권한이 게임 모임 범위라면 APK 자체는 공개 GitHub Release에 첨부하지 말고 허가된 채널에서만 공유합니다.

1.1.0 이하 시험판은 앱 ID와 서명이 다릅니다. 사용자는 이전 앱을 종료·삭제한 뒤 `helper.journey.starsavior` 공개판을 새로 설치해야 합니다.
