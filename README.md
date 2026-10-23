# Luma

Spotify 계정으로 로그인해서 노래를 검색하고, 내 플레이리스트를 보고, 가사와 함께 재생하는 웹 플레이어예요.

**주소:** https://jason2538.github.io/luma/

## 기능
- 첫 화면 문구가 5초마다 한국어와 영어로 바뀌어요
- 노래·가수 검색 후 바로 재생
- 내 플레이리스트와 좋아요 표시한 곡 보기
- 재생 화면: 왼쪽은 앨범 커버와 재생 조작, 오른쪽은 재생 시간에 맞춰 움직이는 가사 ([LRCLIB](https://lrclib.net) 제공)
- 오른쪽 위 기기 버튼으로 다른 기기(폰, 태블릿의 Spotify 앱) 조작

## 설정 (한 번만)
1. **GitHub Pages 켜기:** 이 저장소의 Settings → Pages에서 Source를 "Deploy from a branch", Branch를 `main` / `/ (root)`로 두고 Save
2. **Spotify 개발자 대시보드** (https://developer.spotify.com/dashboard) 의 앱 Settings에서:
   - Redirect URIs에 `https://jason2538.github.io/luma/` 추가 (끝의 `/` 포함)
   - "Web API"와 "Web Playback SDK" 체크
   - User Management에 사용할 Spotify 계정 이메일 추가 (개발 모드는 최대 5명)

## 참고
- 브라우저 안에서 재생하려면 Spotify Premium이 필요해요.
- 안드로이드 브라우저처럼 웹 재생이 안 되는 곳에서는 Spotify 앱을 켜고, 기기 버튼에서 그 앱을 골라 조작하면 돼요.
- 내가 만들지 않은 플레이리스트는 Spotify 정책상 곡 목록이 안 보여요. "전체 재생"으로는 들을 수 있어요.
- APK(안드로이드 앱)에서는 Spotify 로그인을 앱 안이 아니라 기기의 브라우저에서 진행하고, 끝나면 앱으로 자동으로 돌아와요. (구글은 앱 안에 들어있는 화면에서 하는 로그인을 막아서, 구글·페이스북 계정으로 가입한 경우에도 이렇게 해야 로그인돼요.)
- Client Secret은 쓰지 않아요 (PKCE 방식). 저장소에 Secret을 올리지 마세요.
- 내 컴퓨터에서 테스트할 때는 `python3 -m http.server 8000` 실행 후 `http://127.0.0.1:8000/`으로 열고, 그 주소도 Redirect URI에 추가하세요. (`localhost`는 Spotify가 받지 않아요)
