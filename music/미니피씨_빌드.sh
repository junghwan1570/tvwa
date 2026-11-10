#!/usr/bin/env bash
# 미니피씨(리눅스)에서 사신뮤직 설치 파일 만들기
#
# 쓰는 법:
#   ./미니피씨_빌드.sh                    → 이 폴더에 사신뮤직.apk 생성
#   ./미니피씨_빌드.sh /mnt/나스/앱        → 만든 뒤 나스 폴더에도 복사
#
# 처음 한 번만 자바 17이 필요합니다:  sudo apt install -y openjdk-17-jdk unzip
# 안드로이드 개발 도구는 이 스크립트가 알아서 받습니다 (처음 한 번, 약 300메가바이트).
set -euo pipefail
cd "$(dirname "$0")"

NAS="${1:-}"
# 개발 도구 위치: 안드로이드 스튜디오 기본 위치와 같음 (경로에 한글이 있으면 도구가 고장 날 수 있어 영문 경로를 씀)
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
# 한글 파일 이름을 자바가 제대로 읽도록
export LC_ALL=C.UTF-8 LANG=C.UTF-8

# 1) 자바 확인
if ! command -v java >/dev/null; then
  echo "자바가 없습니다. 먼저 설치하세요:  sudo apt install -y openjdk-17-jdk unzip"; exit 1
fi
JV=$(java -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+).*/\1/')
if [ "$JV" -lt 17 ]; then echo "자바 17 이상이 필요합니다 (지금 $JV)"; exit 1; fi

# 2) 안드로이드 개발 도구 (없을 때만 받음)
if [ ! -d "$SDK/platforms/android-34" ] || [ ! -d "$SDK/build-tools/34.0.0" ]; then
  echo "▶ 안드로이드 개발 도구를 받습니다…"
  mkdir -p "$SDK/cmdline-tools"
  TMPD=$(mktemp -d)
  curl -fL -o "$TMPD/도구.zip" https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  unzip -q "$TMPD/도구.zip" -d "$TMPD"
  rm -rf "$SDK/cmdline-tools/latest"
  mv "$TMPD/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm -rf "$TMPD"
  yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" --licenses >/dev/null || true
  "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" "platforms;android-34" "build-tools;34.0.0"
fi
echo "sdk.dir=$SDK" > local.properties

# 3) 서명 키: 미니피씨 전용 키를 한 번 만들어 계속 씀 (같은 키여야 덮어 설치가 됨)
if [ ! -f release.keystore ] && [ ! -f ci-temp.keystore ]; then
  echo "▶ 서명 키를 새로 만듭니다 (ci-temp.keystore — 잃어버리지 않게 보관하세요)"
  keytool -genkeypair -keystore ci-temp.keystore -alias temp -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass android -keypass android -dname "CN=Sasin Music, C=KR"
fi

# 4) 만들기
echo "▶ 설치 파일을 만듭니다…"
chmod +x gradlew
./gradlew assembleRelease --no-daemon
cp app/build/outputs/apk/release/app-release.apk 사신뮤직.apk
echo "✔ 완성: $(pwd)/사신뮤직.apk"

# 5) 나스로 복사
if [ -n "$NAS" ]; then
  if [ -d "$NAS" ]; then
    cp 사신뮤직.apk "$NAS/사신뮤직.apk"
    echo "✔ 나스에 복사: $NAS/사신뮤직.apk"
  else
    echo "✘ 나스 폴더가 없습니다: $NAS (나스 공유 폴더가 연결(마운트)돼 있는지 확인하세요)"; exit 1
  fi
fi
