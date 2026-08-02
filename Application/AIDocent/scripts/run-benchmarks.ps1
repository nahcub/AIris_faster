# 여러 모델을 같은 조건에서 무인 측정하는 실험 러너.
#
# 사람이 손으로 하던 일(모델 고르기 → Suite 누르기 → 끝날 때까지 보기 → adb pull)을
# 순서대로 적어둔 것. 손잡이는 앱이 이미 제공한다:
#   입력  : am start 인텐트 엑스트라   (AutoRunRequest.kt)
#   완료  : benchmarks/last_run.txt    (BenchSignal.kt)
#   출력  : benchmarks/results.jsonl   (BenchmarkLogger.kt)
#
# 이 스크립트가 추가로 하는 일은 '조건 통제'다 — 온도 게이트, 기기 설정 고정,
# 순서 효과 분산. 통제한 조건은 benchmark_results/run_conditions.csv 에 남겨서
# 나중에 results.jsonl 과 timestamp 로 짝지을 수 있게 한다.
#
# ⚠️ 이 스크립트는 기기 설정(밝기·비행기모드·화면유지)을 바꾼다. finally 에서 원복하지만,
#    중간에 강제 종료(Ctrl+C 후 프로세스 kill)하면 원복이 안 될 수 있다.

$ErrorActionPreference = "Stop"

# ══════════════════════════════════════════════════════════════
#  실험 계획 — 평소엔 이 블록만 고친다
# ══════════════════════════════════════════════════════════════

$models = @(
    "gemma-4-E2B-it-int4.litertlm",              # 대조군
    "gemma-4-E2B-it-docent-lora-int4.litertlm"   # LoRA본
)

$repeats = 5      # 프롬프트당 기록 회차
$warmups = 1      # 프롬프트당 버리는 예열 회차

# 순서 효과 분산. $true 면 A,B,A,B,... 로 번갈아 돈다.
# 대가: 앱 재시작마다 예열이 다시 필요해서 총 실행 회차가 늘어난다
#       (블록 모드 3x(1+5)=18회 vs 인터리브 5x3x(1+1)=30회).
# 그래도 배터리 잔량 드리프트·시간대별 실온 변화 같은 '느린 변수'를 두 모델에 균등하게 퍼뜨린다.
$interleave = $true

# 냉각 게이트 — 이 조건을 만족해야 측정을 시작한다
# ⚠️ 임계는 '기기의 idle 바닥값 + 1~2℃'로 잡아야 한다. 바닥값보다 낮게 잡으면
#    게이트가 영원히 안 열려서 회차마다 $coolTimeout 을 통째로 태운다.
#    이 폰(R3CY90VGY3N) 실측 idle: AP 34.2 / SKIN 33.7 (2026-08-02).
#    기기나 실온이 바뀌면 `adb shell dumpsys thermalservice` 의
#    "Current temperatures from HAL:" 섹션을 다시 보고 조정할 것.
$maxApC      = 50.0     # SoC 다이 온도 상한 (℃)
$maxSkinC    = 50.0     # 기기 표면 온도 상한 (℃). SKIN 38℃가 스로틀 1단계 임계다
$coolTimeout = 3600     # 냉각 최대 대기 (초). 초과하면 '조건 미달' 낙인 찍고 진행
$coolPoll    = 30       # 냉각 재측정 간격 (초)

# 기기 자격 조건
$minBatteryPct = 50     # 잔량이 낮으면 성능 제한이 걸릴 수 있다

$brightnessFixed  = 100    # 0~255. 자동 밝기를 끄고 이 값으로 고정
$useAirplaneMode  = $true  # ⚠️ 무선 adb(adb connect)를 쓴다면 반드시 $false — 자기 연결을 끊는다

$suiteTimeout = 3600    # 앱이 SUITE_DONE 을 낼 때까지 기다릴 최대 시간 (초)

# BenchmarkRunner.DEFAULT_PROMPTS 의 개수. 저장 회차 수를 검산하는 데 쓴다.
# (프롬프트셋을 바꿨으면 이 숫자도 같이 고칠 것)
$promptCount = 3

# ══════════════════════════════════════════════════════════════
#  경로 / 도구
# ══════════════════════════════════════════════════════════════

$appId    = "com.example.airis"
$activity = "$appId/.MainActivity"
$filesDir = "/sdcard/Android/data/$appId/files"
$marker   = "$filesDir/benchmarks/last_run.txt"
$results  = "$filesDir/benchmarks/results.jsonl"

$projectRoot = Split-Path -Parent $PSScriptRoot
$outDir      = Join-Path $projectRoot "benchmark_results"   # .gitignore 처리된 회수 폴더
$condCsv     = Join-Path $outDir "run_conditions.csv"

# SDK 해석: 환경변수 우선, 없으면 표준 설치 경로 (run-on-device.ps1 과 동일한 폴백)
$sdkRoot = $env:ANDROID_HOME
if (-not $sdkRoot) { $sdkRoot = $env:ANDROID_SDK_ROOT }
if (-not $sdkRoot) { $sdkRoot = Join-Path $env:LOCALAPPDATA "Android\Sdk" }

$adb = Join-Path $sdkRoot "platform-tools\adb.exe"
if (-not (Test-Path $adb)) {
    throw "adb not found at $adb. Set ANDROID_HOME to your Android SDK directory."
}

if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir | Out-Null }

# ══════════════════════════════════════════════════════════════
#  기기 상태 읽기 / 쓰기
# ══════════════════════════════════════════════════════════════

# dumpsys thermalservice 에서 현재 온도 + 스로틀 단계를 뽑는다.
# ⚠️ 같은 dump 안의 "Cached temperatures:" 는 스테일이다(관측 시 15℃까지 벌어졌다).
#    반드시 "Current temperatures from HAL:" 섹션만 파싱할 것.
function Get-ThermalState {
    $raw = (& $adb shell "dumpsys thermalservice") -join "`n"

    $cur = [regex]::Match($raw, '(?s)Current temperatures from HAL:(.*?)Current cooling')
    if (-not $cur.Success) { throw "온도 섹션을 찾지 못했습니다. dumpsys thermalservice 출력을 확인하세요." }

    # 이름-값 쌍을 통째로 긁어 해시테이블로. 센서명을 패턴에 끼워 찾으면
    # 'BAT' 이 'SUBBAT' 에도 걸리는 함정이 있다.
    $temps = @{}
    foreach ($m in [regex]::Matches($cur.Groups[1].Value, 'mValue=([-\d.]+), mType=\d+, mName=(\w+)')) {
        $temps[$m.Groups[2].Value] = [double]$m.Groups[1].Value
    }
    if ($temps.Count -eq 0) { throw "온도 값을 파싱하지 못했습니다." }

    [pscustomobject]@{
        AP     = $temps.AP
        SKIN   = $temps.SKIN
        BAT    = $temps.BAT
        Status = [int][regex]::Match($raw, 'Thermal Status: (\d+)').Groups[1].Value
    }
}

function Get-BatteryLevel {
    $bat = (& $adb shell "dumpsys battery") -join "`n"
    [int][regex]::Match($bat, '(?m)^\s*level: (\d+)').Groups[1].Value
}

function Get-DeviceSetting([string]$ns, [string]$key) {
    ((& $adb shell settings get $ns $key) -join "").Trim()
}

# 'null'(미설정)을 되돌릴 땐 put 이 아니라 delete 여야 한다.
# put 하면 문자열 "null" 이 실제 값으로 박힌다.
function Restore-DeviceSetting([string]$ns, [string]$key, [string]$value) {
    if ([string]::IsNullOrWhiteSpace($value) -or $value -eq "null") {
        & $adb shell settings delete $ns $key | Out-Null
    } else {
        & $adb shell settings put $ns $key $value | Out-Null
    }
}

# ══════════════════════════════════════════════════════════════
#  가드 / 환경 세팅
# ══════════════════════════════════════════════════════════════

# 측정할 자격이 있는 상태인지 검사. 없으면 아예 시작하지 않는다
# (반쯤 진행하고 실패하는 것보다 시작 안 하는 게 낫다).
function Assert-DeviceReady {
    $device = & $adb devices | Select-String "\tdevice$"
    if (-not $device) {
        throw "인증된 기기가 없습니다. USB 연결 / USB 디버깅 / RSA 수락 후 'adb devices' 확인."
    }

    $level = Get-BatteryLevel
    if ($level -lt $minBatteryPct) {
        throw "배터리 $level% — $minBatteryPct% 이상에서 측정하세요 (저잔량 시 성능 제한 가능)."
    }

    if ((Get-DeviceSetting "global" "low_power") -eq "1") {
        throw "절전 모드가 켜져 있습니다. 끄고 다시 실행하세요."
    }

    # 잠긴 기기에선 am start 가 막히고, 화면이 꺼지면 CPU 거버너가 내려앉는다
    # (실측: 같은 모델·프롬프트가 2.4 tok/s vs 16.5 tok/s).
    & $adb shell input keyevent KEYCODE_WAKEUP | Out-Null
    $win = (& $adb shell "dumpsys window") -join "`n"
    if ($win -match 'mDreamingLockscreen=true') {
        throw "기기가 잠겨 있습니다. 손으로 잠금을 해제한 뒤 다시 실행하세요."
    }

    Write-Host "기기 준비됨 (배터리 $level%)" -ForegroundColor Green
}

function Set-BenchEnvironment {
    # 자동 밝기를 먼저 꺼야 한다 — 안 그러면 밝기 값을 넣어도 자동 조절이 즉시 덮어쓴다
    & $adb shell settings put system screen_brightness_mode 0 | Out-Null
    & $adb shell settings put system screen_brightness $brightnessFixed | Out-Null

    # 3 = AC|USB. 냉각 대기 중(앱이 죽어 있어 FLAG_KEEP_SCREEN_ON 이 없는 구간)에도 화면 유지
    & $adb shell settings put global stay_on_while_plugged_in 3 | Out-Null

    if ($useAirplaneMode) {
        try { & $adb shell cmd connectivity airplane-mode enable | Out-Null }
        catch { Write-Host "  비행기 모드 전환 실패(무시하고 진행): $_" -ForegroundColor Yellow }
    }

    & $adb shell am kill-all | Out-Null   # 캐시된 백그라운드 프로세스 정리
    Write-Host "측정 환경 적용됨" -ForegroundColor Green
}

function Restore-Environment([hashtable]$saved) {
    Write-Host "`n기기 설정 원복 중..." -ForegroundColor Cyan
    Restore-DeviceSetting "system" "screen_brightness_mode"    $saved.brightnessMode
    Restore-DeviceSetting "system" "screen_brightness"         $saved.brightness
    Restore-DeviceSetting "global" "stay_on_while_plugged_in"  $saved.stayOn
    if ($useAirplaneMode) {
        try { & $adb shell cmd connectivity airplane-mode disable | Out-Null } catch { }
    }
    & $adb shell am force-stop $appId | Out-Null
}

# ══════════════════════════════════════════════════════════════
#  냉각 게이트 / 완료 대기
# ══════════════════════════════════════════════════════════════

# 온도와 스로틀 단계가 조건을 만족할 때까지 대기하며 재측정한다.
# 타임아웃은 throw 가 아니라 '조건 미달로 진행' — 한 모델이 안 식는다고
# 밤샘 실행 전체를 날리지 않되, MetCriteria=False 로 낙인찍어 분석 때 걸러낼 수 있게 한다.
function Wait-ForCooldown {
    $start    = Get-Date
    $deadline = $start.AddSeconds($coolTimeout)

    while ($true) {
        $t = Get-ThermalState
        $coolEnough   = ($t.AP -le $maxApC) -and ($t.SKIN -le $maxSkinC)
        $notThrottled = ($t.Status -eq 0)

        if ($coolEnough -and $notThrottled) {
            $waited = [int]((Get-Date) - $start).TotalSeconds
            Write-Host ("  냉각 완료 ({0}s 대기): AP={1} SKIN={2} BAT={3}" -f `
                $waited, $t.AP, $t.SKIN, $t.BAT) -ForegroundColor Green
            return [pscustomobject]@{ Temp = $t; WaitedSec = $waited; MetCriteria = $true }
        }

        if ((Get-Date) -ge $deadline) {
            Write-Host ("  [!] 냉각 타임아웃: AP={0} SKIN={1} status={2} — 조건 미달로 진행" -f `
                $t.AP, $t.SKIN, $t.Status) -ForegroundColor Yellow
            return [pscustomobject]@{ Temp = $t; WaitedSec = $coolTimeout; MetCriteria = $false }
        }

        $reason = if (-not $notThrottled) { "스로틀 $($t.Status)단계" } else { "온도" }
        Write-Host ("  냉각 대기... AP={0} SKIN={1} ({2})" -f $t.AP, $t.SKIN, $reason) -ForegroundColor DarkGray
        Start-Sleep -Seconds $coolPoll
    }
}

# 마커 파일이 생길 때까지 폴링. am start 는 앱을 띄우고 즉시 리턴하므로
# '끝났다'는 앱이 남기는 신호로만 알 수 있다.
# (logcat 대기는 '앱 시작 전에 걸어놨어야 한다'는 레이스가 있어 파일 폴링이 더 견고하다)
function Wait-BenchSignal {
    $deadline = (Get-Date).AddSeconds($suiteTimeout)
    while ((Get-Date) -lt $deadline) {
        # 2>/dev/null 은 기기 셸 안에서 도는 것 — 파일이 아직 없을 때 조용히 넘어간다
        $line = (& $adb shell "cat '$marker' 2>/dev/null") -join ""
        if ($line) { return $line.Trim() }
        Start-Sleep -Seconds 5
    }
    throw "SUITE 타임아웃 ($suiteTimeout s). 'adb logcat -s AirisBench:I' 로 확인하세요."
}

# ══════════════════════════════════════════════════════════════
#  실행 계획
# ══════════════════════════════════════════════════════════════

$plan = @()
if ($interleave) {
    for ($r = 1; $r -le $repeats; $r++) {
        foreach ($m in $models) { $plan += [pscustomobject]@{ Model = $m; Repeats = 1 } }
    }
} else {
    foreach ($m in $models) { $plan += [pscustomobject]@{ Model = $m; Repeats = $repeats } }
}

Write-Host ""
Write-Host "모델 $($models.Count)개 / repeats=$repeats / 실행 $($plan.Count)회 " -NoNewline -ForegroundColor Cyan
Write-Host $(if ($interleave) { "(인터리브)" } else { "(블록)" }) -ForegroundColor Cyan

# ══════════════════════════════════════════════════════════════
#  본 실행
# ══════════════════════════════════════════════════════════════

Assert-DeviceReady

# 원복할 값을 미리 읽어둔다
$saved = @{
    brightnessMode = Get-DeviceSetting "system" "screen_brightness_mode"
    brightness     = Get-DeviceSetting "system" "screen_brightness"
    stayOn         = Get-DeviceSetting "global" "stay_on_while_plugged_in"
}

# 기존 results.jsonl 을 타임스탬프 붙여 옮긴다(지우는 게 아니라 이름만 바꿈).
# results.jsonl 은 append-only 라, 안 치우면 pull 한 파일에 옛 레코드가 섞여 온다.
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
& $adb shell "test -f '$results' && mv '$results' '$results.$stamp'" | Out-Null

if (-not (Test-Path $condCsv)) {
    "timestamp,model,repeats,ap_start,skin_start,bat_start,ap_end,skin_end,thermal_end,cool_waited_s,cool_met,saved,expected,signal" `
        | Set-Content -Path $condCsv -Encoding utf8
}

try {
    Set-BenchEnvironment

    $i = 0
    foreach ($entry in $plan) {
        $i++
        $expected = $promptCount * $entry.Repeats
        Write-Host ""
        Write-Host "=== [$i/$($plan.Count)] $($entry.Model)  repeats=$($entry.Repeats) ===" -ForegroundColor Cyan

        # 식히는 동안 부하가 없어야 한다
        & $adb shell am force-stop $appId | Out-Null
        $cool = Wait-ForCooldown

        & $adb shell rm -f $marker | Out-Null   # 이전 신호 제거 (폴링의 전제)

        # -S = 프로세스 강제 재시작. 모델마다 완전히 새 프로세스라 조건이 같아진다.
        $modelName   = $entry.Model
        $entryRepeat = $entry.Repeats
        & $adb shell am start -S -n $activity `
            -e model $modelName -e autorun suite `
            --ei repeats $entryRepeat --ei warmups $warmups | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "am start 실패 (기기가 잠기지 않았는지 확인)" }

        $signal  = Wait-BenchSignal
        $endTemp = Get-ThermalState

        # 저장 회차 검산. 실패한 회차는 BenchmarkRunner 가 조용히 건너뛰므로
        # saved 숫자를 안 보면 누락을 알아챌 수 없다(07-31 편향 사건의 자리).
        $savedCount = -1
        if ($signal -match 'saved=(\d+)') { $savedCount = [int]$Matches[1] }

        if ($signal -like "SUITE_DONE*") {
            if ($savedCount -lt $expected) {
                Write-Host "  [!] $savedCount/$expected 저장됨 — 회차 누락" -ForegroundColor Yellow
            } else {
                Write-Host "  $signal" -ForegroundColor Green
            }
        } else {
            Write-Host "  [X] $signal" -ForegroundColor Red   # SUITE_FAILED / MODEL_NOT_FOUND
        }

        # 통제한 조건을 남긴다. results.jsonl 과는 timestamp 로 짝짓는다.
        ('"{0}","{1}",{2},{3},{4},{5},{6},{7},{8},{9},{10},{11},{12},"{13}"' -f `
            (Get-Date -Format o), $entry.Model, $entry.Repeats,
            $cool.Temp.AP, $cool.Temp.SKIN, $cool.Temp.BAT,
            $endTemp.AP, $endTemp.SKIN, $endTemp.Status,
            $cool.WaitedSec, $cool.MetCriteria,
            $savedCount, $expected, $signal) `
            | Add-Content -Path $condCsv -Encoding utf8
    }
}
finally {
    # 중간에 죽어도 기기를 원래대로 돌려놓는다
    Restore-Environment $saved
}

# ══════════════════════════════════════════════════════════════
#  회수
# ══════════════════════════════════════════════════════════════

Write-Host "`n결과 회수 중..." -ForegroundColor Cyan
& $adb pull $results $outDir

Write-Host ""
Write-Host "완료." -ForegroundColor Green
Write-Host "  측정값   : $(Join-Path $outDir 'results.jsonl')"
Write-Host "  실행조건 : $condCsv"
