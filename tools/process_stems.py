#!/usr/bin/env python3
"""Suno 원본 WAV → 앱용 OGG 스템(res/raw) 가공.

루프(drums, bass, chords, melody, hat, pad_rain × A/B)
  1. 분석(tools/analyze_stems.py)으로 템포·시작점(다운비트) 결정
     - 템포 추정이 믿을 만하고(박 잔차 < 15ms, 사용 박 ≥ 8) 90에서 0.05 BPM 넘게 벗어나면
       ffmpeg atempo로 피치는 유지한 채 템포만 90으로 맞춤
     - 시작점: 드럼·하이햇 = 첫 다운비트(킥/onset), 음높이 스템 = 코드 템플릿 정렬로 찾은 마디 시작
  2. 시작점부터 정확히 4마디(512,000 샘플 @48kHz, 90 BPM) 자르기
  3. 루프 이음매 8ms 크로스페이드(뒤에 이어지는 원본이 있으면 그걸 앞부분에 섞음 → 자연스러운 연결)
  4. 음량: 통합 라우드니스 -20 LUFS 목표, 샘플 피크 -1 dBFS 이하(넘으면 게인을 낮춤)
  5. oggenc -q 5, 48kHz 스테레오 → app/src/main/res/raw/lofi_<stem>_<a|b>.ogg
원샷(sfx_tapestop, sfx_scratch × A/B)
  앞 무음 제거(-50 dBFS, 5ms 남김) + 끝 무음 제거 + 꼬리 300ms 페이드아웃, -18 LUFS / 피크 -1 dBFS

검사: 출하될 OGG를 다시 디코딩해 3번 이어 붙인 미리듣기 WAV(build/preview/, git 제외)를 만들고
      이음매의 클릭(급격한 진폭 변화)을 자동 검사 → build/preview/report.json

사용: python3 tools/process_stems.py [--src assets-src/suno_raw] [--only drums_A,...]
의존성: numpy, ffmpeg, oggenc(vorbis-tools)
"""
import argparse
import json
import shutil
import subprocess
import sys
import wave
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
import analyze_stems as A  # noqa: E402

SR = 48_000
BPM = 90.0
BAR_S = 4 * 60 / BPM
LOOP_FRAMES = int(round(4 * BAR_S * SR))  # 512000
XFADE = int(0.008 * SR)
# 루프 OGG는 앞뒤에 순환 패딩을 붙여 인코딩한다(앞 = 루프 끝 PAD샘플, 뒤 = 루프 처음 PAD샘플).
# Vorbis가 파일 경계에서 만드는 왜곡이 이음매(다운비트)에 걸리지 않게 하기 위함.
# 앱(OggLayerSource)은 디코딩 후 앞 PAD샘플을 버리고 LOOP_FRAMES만 쓴다.
LOOP_PAD = 4096
TARGET_LUFS_LOOP = -20.0
TARGET_LUFS_SFX = -18.0
PEAK_DBFS = -1.0

ROOT = Path(__file__).resolve().parent.parent
OUT_RAW = ROOT / "app/src/main/res/raw"
PREVIEW = ROOT / "build/preview"
TMP = ROOT / "build/stems_tmp"


def run(cmd):
    return subprocess.run(cmd, check=True, capture_output=True)


def read_stereo(path: Path) -> np.ndarray:
    return A.decode(path, mono=False).astype(np.float64)


def write_wav(path: Path, x: np.ndarray):
    pcm = np.clip(np.round(x * 32767), -32768, 32767).astype("<i2")
    with wave.open(str(path), "wb") as w:
        w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes(pcm.tobytes())


def stretch(src: Path, factor: float, dst: Path):
    run(["ffmpeg", "-v", "error", "-y", "-i", str(src), "-af", f"atempo={factor:.6f}", "-ar", str(SR), "-ac", "2", "-c:a", "pcm_f32le", str(dst)])


def lufs_of(x: np.ndarray) -> float:
    tmp = TMP / "_lufs.wav"
    write_wav(tmp, x)
    return A.loudness(tmp)["lufs"]


def normalize(x: np.ndarray, target_lufs: float):
    l0 = lufs_of(x)
    gain_db = target_lufs - l0
    peak = np.abs(x).max()
    max_gain_db = PEAK_DBFS - 20 * np.log10(peak + 1e-12)
    applied = min(gain_db, max_gain_db)
    y = x * 10 ** (applied / 20)
    return y, {"lufs_in": l0, "gain_db": round(applied, 2), "peak_limited": applied < gain_db}


def encode(x: np.ndarray, name: str, loop: bool = False) -> Path:
    if loop:
        x = np.concatenate([x[-LOOP_PAD:], x, x[:LOOP_PAD]])
    wav = TMP / f"{name}.wav"
    write_wav(wav, x)
    OUT_RAW.mkdir(parents=True, exist_ok=True)
    out = OUT_RAW / f"{name}.ogg"
    run(["oggenc", "-Q", "-q", "5", "--resample", str(SR), "-o", str(out), str(wav)])
    return out


# ------------------------------------------------------------------ 클릭 검사
def hf_energy(x: np.ndarray, center: int, w: int = int(0.002 * SR)) -> float:
    hp = np.diff(x[center - w: center + w + 2], n=2, axis=0)
    return float((hp ** 2).sum()) + 1e-12


def seam_click(x: np.ndarray, seam: int, loop_len: int) -> dict:
    """이음매 클릭 지표.
    jump_ratio: 이음매 ±2샘플의 최대 1차 차분 / 주변 ±100ms의 99.9퍼센타일 (>1.5면 불연속)
    vs_downbeats_db: 이음매 2ms 고역 에너지 − 같은 루프 안 다른 마디 시작점(2~4마디)의 중앙값
                     (다운비트 어택은 원래 있는 소리이므로 그보다 6dB 넘게 크면 클릭으로 본다)
    """
    d = np.abs(np.diff(x, axis=0)).max(axis=1)
    lo, hi = max(1, seam - 4800), min(len(d) - 1, seam + 4800)
    local = np.concatenate([d[lo:seam - 3], d[seam + 3:hi]])
    jump = d[seam - 2:seam + 2].max()
    ref = np.percentile(local, 99.9) + 1e-9
    bar = loop_len // 4
    internal = [hf_energy(x, seam + k * bar) for k in (1, 2, 3)]
    vs_db = 10 * np.log10(hf_energy(x, seam) / np.median(internal))
    return {"jump_ratio": round(float(jump / ref), 2), "vs_downbeats_db": round(float(vs_db), 1)}


def check_loop(ogg: Path, name: str, orig: np.ndarray = None, natural_pos: int = None, gain_db: float = 0.0) -> dict:
    y = read_stereo(ogg)
    assert len(y) == LOOP_FRAMES + 2 * LOOP_PAD, f"{name}: decoded {len(y)}"
    y = y[LOOP_PAD:LOOP_PAD + LOOP_FRAMES]
    PREVIEW.mkdir(parents=True, exist_ok=True)
    x3 = np.concatenate([y, y, y])
    write_wav(PREVIEW / f"{name}_x3.wav", x3)
    seams = [seam_click(x3, len(y) * k, len(y)) for k in (1,)]  # 3회 반복 미리듣기의 1번 이음매(뒤에 3마디 여유 필요)
    seams.append(seam_click(np.concatenate([x3, y]), len(y) * 2, len(y)))
    worst_jump = max(s["jump_ratio"] for s in seams)
    worst_burst = max(s["vs_downbeats_db"] for s in seams)
    vs_orig = None
    if orig is not None and natural_pos is not None and natural_pos + 2000 < len(orig):
        nat = hf_energy(orig * 10 ** (gain_db / 20), natural_pos)
        vs_orig = round(10 * np.log10(max(hf_energy(x3, len(y) * k) for k in (1, 2)) / nat), 1)
    # 판정: 샘플 불연속(jump) 없음 + 이음매 고역 에너지가 원본의 같은 지점보다 6dB 넘게 크지 않음
    ok = worst_jump <= 1.5 and (vs_orig is None and worst_burst <= 6.0 or vs_orig is not None and vs_orig <= 6.0)
    # 가공 후 템포 재확인
    mono = x3.mean(axis=1).astype(np.float32)
    env = A.flux(A.stft_mag(mono))
    bpm, _ = A.best_tempo(env, 85, 95, 0.01)
    return {"frames": len(y), "loop_pad": LOOP_PAD, "exact_4bars": bool(len(y) == LOOP_FRAMES), "seams": seams, "vs_original_db": vs_orig,
            "click_ok": bool(ok), "bpm_after": round(bpm, 2)}


# ------------------------------------------------------------------ 루프
def process_loop(path: Path, info: dict) -> dict:
    stem, var = path.stem.rsplit("_", 1)
    name = f"lofi_{stem}_{var.lower()}"
    bpm = info.get("bpm") or BPM
    reliable = info.get("beat_resid_ms") is not None and info["beat_resid_ms"] < 15 and info.get("beats_used", 0) >= 8
    src = path
    factor = 1.0
    if reliable and abs(bpm - BPM) > 0.05:
        factor = BPM / bpm
        src = TMP / f"{path.stem}_stretched.wav"
        stretch(path, factor, src)
    x = read_stereo(src)

    if stem in ("drums", "hat"):
        start_s = info["first_downbeat_s"]
        start_rule = "first_downbeat"
    else:
        start_s = info.get("am7_bar_start_s") or info["first_downbeat_s"]
        start_rule = "chord_aligned_bar"
    start_s /= factor  # 템포 보정 후 시간축
    start = int(round(start_s * SR))
    # 4마디가 안 들어가면 한 마디씩 앞으로
    while start + LOOP_FRAMES > len(x) and start - int(BAR_S * SR) >= 0:
        start -= int(round(BAR_S * SR))
    if start + LOOP_FRAMES > len(x):
        start = max(0, len(x) - LOOP_FRAMES)
    loop = x[start:start + LOOP_FRAMES].copy()
    if len(loop) < LOOP_FRAMES:
        loop = np.pad(loop, ((0, LOOP_FRAMES - len(loop)), (0, 0)))

    ramp = np.linspace(0, 1, XFADE)[:, None]
    after = x[start + LOOP_FRAMES:start + LOOP_FRAMES + XFADE]
    before = x[start - XFADE:start] if start >= XFADE else None
    if len(after) == XFADE:
        loop[:XFADE] = loop[:XFADE] * ramp + after * (1 - ramp)       # 꼬리 연속분을 머리에 섞음
        xf_rule = "tail_into_head"
    elif before is not None:
        loop[-XFADE:] = loop[-XFADE:] * (1 - ramp) + before * ramp    # 머리 앞부분을 꼬리에 섞음
        xf_rule = "preroll_into_tail"
    else:
        loop[:XFADE] *= ramp; loop[-XFADE:] *= 1 - ramp
        xf_rule = "fade_out_in"

    loop, norm = normalize(loop, TARGET_LUFS_LOOP)
    ogg = encode(loop, name, loop=True)
    res = {"name": name, "src": path.name, "bpm_in": bpm, "tempo_reliable": reliable,
           "atempo": round(factor, 5), "start_rule": start_rule, "start_s": round(start / SR, 3),
           "xfade": xf_rule, **norm, "ogg_kb": round(ogg.stat().st_size / 1024)}
    natural = start + LOOP_FRAMES if xf_rule == "tail_into_head" else (start if xf_rule == "preroll_into_tail" else None)
    res.update(check_loop(ogg, name, x, natural, norm["gain_db"]))
    return res


# ------------------------------------------------------------------ 원샷
def process_sfx(path: Path) -> dict:
    stem, var = path.stem.rsplit("_", 1)
    name = f"lofi_{stem}_{var.lower()}"
    x = read_stereo(path)
    mono = np.abs(x).max(axis=1)
    thr = 10 ** (-50 / 20)
    loud = np.where(mono > thr)[0]
    a = max(0, loud[0] - int(0.005 * SR)); b = loud[-1] + 1
    y = x[a:b].copy()
    fade = min(int(0.3 * SR), len(y) // 4)
    y[-fade:] *= np.linspace(1, 0, fade)[:, None] ** 2
    y[:int(0.002 * SR)] *= np.linspace(0, 1, int(0.002 * SR))[:, None]
    y, norm = normalize(y, TARGET_LUFS_SFX)
    ogg = encode(y, name)
    return {"name": name, "src": path.name, "trim_lead_s": round(a / SR, 3), "trim_tail_s": round((len(x) - b) / SR, 3),
            "length_s": round(len(y) / SR, 2), **norm, "ogg_kb": round(ogg.stat().st_size / 1024)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", default=str(ROOT / "assets-src/suno_raw"))
    ap.add_argument("--only", default="")
    args = ap.parse_args()
    for tool in ("ffmpeg", "oggenc"):
        if not shutil.which(tool):
            sys.exit(f"{tool} 필요")
    TMP.mkdir(parents=True, exist_ok=True)
    src = Path(args.src)
    only = set(filter(None, args.only.split(",")))
    files = [p for p in sorted(src.glob("*.wav")) if not only or p.stem in only]

    analysis_path = ROOT / "build/analysis/stems.json"
    if analysis_path.exists():
        info = {r["file"]: r for r in json.loads(analysis_path.read_text())}
    else:
        info = {}
    report = []
    for p in files:
        if p.name not in info and not p.name.startswith("sfx_"):
            info[p.name] = A.analyze(p)
        r = process_sfx(p) if p.name.startswith("sfx_") else process_loop(p, info[p.name])
        print(json.dumps(r, ensure_ascii=False))
        report.append(r)
    PREVIEW.mkdir(parents=True, exist_ok=True)
    (PREVIEW / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=1))
    shutil.rmtree(TMP, ignore_errors=True)


if __name__ == "__main__":
    main()
