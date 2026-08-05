# 기여 안내

버그 수정과 문서 개선을 환영합니다. Pull Request를 만들기 전에 다음 원칙을 지켜 주세요.

## 저장소에 올리면 안 되는 항목

- 실제 `journey_choices.json` 또는 원자료 JSON 사본
- `.jks`, `.keystore`, `.p12` 운영·개발 키
- `keystore.properties`, 비밀번호, 토큰, 개인 경로
- 게임 스크린샷, 캐릭터 이미지, 음원 또는 추출 자산
- 계정 정보나 개인정보가 포함된 로그

## 검사

```bash
python3 tools/check_public_source.py
./gradlew testDebugUnitTest lintRelease assembleDebug
```

실제 게임 데이터 대신 `journey_choices.example.json`과 합성 테스트 데이터를 사용해 주세요. 화면 처리나 네트워크 동작을 변경했다면 `PRIVACY.md`와 앱 내 안내도 함께 갱신해야 합니다.

커밋하기 전 `git status`, `git diff --cached`로 포함 파일을 직접 확인하세요.
