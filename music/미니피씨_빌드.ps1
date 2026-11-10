# 미니피씨(윈도우)에서 사신뮤직 설치 파일 만들기
#
# 쓰는 법 (파워셸에서):
#   .\미니피씨_빌드.ps1                          → 이 폴더에 사신뮤직.apk 생성
#   .\미니피씨_빌드.ps1 \\나스이름\공유폴더\앱    → 만든 뒤 나스 폴더에도 복사
#
# 처음 한 번만 자바 17이 필요합니다:  winget install EclipseAdoptium.Temurin.17.JDK
# 실행이 막히면 먼저:  Set-ExecutionPolicy -Scope CurrentUser RemoteSigned
param([string]$나스폴더 = "")
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

# 개발 도구 위치: 안드로이드 스튜디오 기본 위치와 같음 (경로에 한글이 있으면 도구가 고장 날 수 있어 영문 경로를 씀)
$도구 = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA "Android\Sdk" }
if ($PSScriptRoot -match '[^\x00-\x7F]') { Write-Host "※ 주의: 이 폴더 경로에 한글이 있으면 만들기가 실패할 수 있습니다. 실패하면 C:\sasin 같은 영문 폴더로 옮겨서 다시 하세요." }

# 1) 자바 확인
if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    Write-Host "자바가 없습니다. 먼저 설치하세요:  winget install EclipseAdoptium.Temurin.17.JDK"; exit 1
}

# 2) 안드로이드 개발 도구 (없을 때만 받음)
if (-not (Test-Path "$도구\platforms\android-34") -or -not (Test-Path "$도구\build-tools\34.0.0")) {
    Write-Host "▶ 안드로이드 개발 도구를 받습니다…"
    New-Item -ItemType Directory -Force "$도구\cmdline-tools" | Out-Null
    $임시 = Join-Path $env:TEMP ("사신뮤직" + [guid]::NewGuid())
    New-Item -ItemType Directory $임시 | Out-Null
    Invoke-WebRequest "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip" -OutFile "$임시\도구.zip"
    Expand-Archive "$임시\도구.zip" -DestinationPath $임시
    if (Test-Path "$도구\cmdline-tools\latest") { Remove-Item -Recurse -Force "$도구\cmdline-tools\latest" }
    Move-Item "$임시\cmdline-tools" "$도구\cmdline-tools\latest"
    Remove-Item -Recurse -Force $임시
    $관리자 = "$도구\cmdline-tools\latest\bin\sdkmanager.bat"
    ("y`n" * 20) | & $관리자 "--sdk_root=$도구" --licenses | Out-Null
    & $관리자 "--sdk_root=$도구" "platforms;android-34" "build-tools;34.0.0"
}
"sdk.dir=" + ($도구 -replace '\\', '/') | Set-Content -Encoding ASCII local.properties

# 3) 서명 키: 미니피씨 전용 키를 한 번 만들어 계속 씀 (같은 키여야 덮어 설치가 됨)
if (-not (Test-Path release.keystore) -and -not (Test-Path ci-temp.keystore)) {
    Write-Host "▶ 서명 키를 새로 만듭니다 (ci-temp.keystore — 잃어버리지 않게 보관하세요)"
    keytool -genkeypair -keystore ci-temp.keystore -alias temp -keyalg RSA -keysize 2048 -validity 10000 `
        -storepass android -keypass android -dname "CN=Sasin Music, C=KR"
}

# 4) 만들기
Write-Host "▶ 설치 파일을 만듭니다…"
.\gradlew.bat assembleRelease --no-daemon
if ($LASTEXITCODE -ne 0) { Write-Host "✘ 만들기 실패"; exit 1 }
Copy-Item app\build\outputs\apk\release\app-release.apk 사신뮤직.apk -Force
Write-Host "✔ 완성: $PSScriptRoot\사신뮤직.apk"

# 5) 나스로 복사
if ($나스폴더) {
    if (Test-Path $나스폴더) {
        Copy-Item 사신뮤직.apk (Join-Path $나스폴더 "사신뮤직.apk") -Force
        Write-Host "✔ 나스에 복사: $나스폴더\사신뮤직.apk"
    } else {
        Write-Host "✘ 나스 폴더에 접근할 수 없습니다: $나스폴더 (파일 탐색기에서 먼저 열어 로그인해 보세요)"; exit 1
    }
}
