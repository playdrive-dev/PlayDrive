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
    return y, {"lufs_in": l0, "gain_db": round(applied, 2), "peak_limited": bool(applied < gain_db)}


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


def seam_click(x: np.ndarray, seam: int, loop_len: int, bars: int = 4) -> dict:
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
    bar = loop_len // bars
    internal = [hf_energy(x, seam + k * bar) for k in range(1, bars)]
    vs_db = 10 * np.log10(hf_energy(x, seam) / np.median(internal))
    return {"jump_ratio": round(float(jump / ref), 2), "vs_downbeats_db": round(float(vs_db), 1)}


def check_loop(ogg: Path, name: str, orig: np.ndarray = None, natural_pos: int = None, gain_db: float = 0.0,
               frames: int = LOOP_FRAMES, bars: int = 4) -> dict:
    y = read_stereo(ogg)
    assert len(y) == frames + 2 * LOOP_PAD, f"{name}: decoded {len(y)}"
    y = y[LOOP_PAD:LOOP_PAD + frames]
    PREVIEW.mkdir(parents=True, exist_ok=True)
    x3 = np.concatenate([y, y, y])
    write_wav(PREVIEW / f"{name}_x3.wav", x3)
    seams = [seam_click(x3, len(y) * k, len(y), bars) for k in (1,)]  # 3회 반복 미리듣기의 1번 이음매
    seams.append(seam_click(np.concatenate([x3, y]), len(y) * 2, len(y), bars))
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
    return {"frames": len(y), "loop_pad": LOOP_PAD, "exact_bars": bool(len(y) == frames), "seams": seams, "vs_original_db": vs_orig,
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
# 원샷 사용 구간(초). tools/find_tapestop.py + 피크·f0 추적 결과(dev-notes 참고):
# Suno 'tape stop' 원샷에는 피치가 내려가는 구간이 없었다.
#  A: 52Hz 지속 → 4.94s 타격 → 5.03~5.06s에 갑자기 무음(정지) → 5.26s 재시작  ⇒ 4.00~5.06s를 써서 '멈춤'이 들리게
#  B: 55Hz 지속, 4.4~5.3s 동안 음량만 −16 → −31dB로 감소(페이드)            ⇒ 3.90~5.32s
SFX_RANGES = {"sfx_tapestop_A": (4.00, 5.06), "sfx_tapestop_B": (3.90, 5.32)}


def process_sfx(path: Path) -> dict:
    stem, var = path.stem.rsplit("_", 1)
    name = f"lofi_{stem}_{var.lower()}"
    x = read_stereo(path)
    mono = np.abs(x).max(axis=1)
    thr = 10 ** (-50 / 20)
    loud = np.where(mono > thr)[0]
    a = max(0, loud[0] - int(0.005 * SR)); b = loud[-1] + 1
    if path.stem in SFX_RANGES:
        a, b = (int(v * SR) for v in SFX_RANGES[path.stem])
    y = x[a:b].copy()
    fade = min(int(0.3 * SR), len(y) // 4) if path.stem not in SFX_RANGES else int(0.01 * SR)  # 구간 지정 시 끝은 10ms만(정지감 유지)
    y[-fade:] *= np.linspace(1, 0, fade)[:, None] ** 2
    fin = int(0.002 * SR) if path.stem not in SFX_RANGES else int(0.02 * SR)
    y[:fin] *= np.linspace(0, 1, fin)[:, None]
    y, norm = normalize(y, TARGET_LUFS_SFX)
    ogg = encode(y, name)
    return {"name": name, "src": path.name, "range_s": [round(a / SR, 3), round(b / SR, 3)],
            "trim_lead_s": round(a / SR, 3), "trim_tail_s": round((len(x) - b) / SR, 3),
            "length_s": round(len(y) / SR, 2), **norm, "ogg_kb": round(ogg.stat().st_size / 1024)}


# ------------------------------------------------------------------ 한 곡 스템(v2)
SONG_STEMS = {"drums": "1 Drums.wav", "bass": "2 Bass.wav", "guitar": "3 Guitar.wav", "keys": "4 Keyboard.wav",
              "synth": "6 Synth.wav", "perc": "5 Percussion.wav"}
SONG_BARS = 8
SONG_FRAMES = int(round(SONG_BARS * BAR_S * SR))  # 1,024,000
# 원본이 작은 스템은 −20 LUFS까지 끌어올리면 잡음이 커지므로 목표를 낮춘다(앱 레벨로 균형).
SONG_TARGET_LUFS = {"drums": -20.0, "bass": -20.0, "guitar": -20.0, "keys": -20.0, "synth": -22.0, "perc": -24.0}


def noise_floor_db(x: np.ndarray) -> float:
    """50ms RMS의 5퍼센타일(dBFS) — 쉬는 구간의 바닥 잡음."""
    w = int(0.05 * SR); n = len(x) // w
    rms = np.sqrt((x[:n * w].reshape(n, w, -1) ** 2).mean(axis=(1, 2)))
    return float(20 * np.log10(np.percentile(rms, 5) + 1e-12))


def process_song(src: Path, start_bar: int) -> list:
    g = json.loads((ROOT / "build/analysis/song.json").read_text())
    start_s = g["first_downbeat_s"] + start_bar * g["bar_s"]
    start = int(round(start_s * SR))
    out = []
    mix = None
    for key, fname in SONG_STEMS.items():
        x = read_stereo(src / fname)
        loop = x[start:start + SONG_FRAMES].copy()
        after = x[start + SONG_FRAMES:start + SONG_FRAMES + XFADE]
        ramp = np.linspace(0, 1, XFADE)[:, None]
        loop[:XFADE] = loop[:XFADE] * ramp + after * (1 - ramp)
        orig_lufs = lufs_of(loop)
        nf_before = noise_floor_db(loop)
        loop, norm = normalize(loop, SONG_TARGET_LUFS[key])
        nf_after = noise_floor_db(loop)
        name = f"song_{key}"
        ogg = encode(loop, name, loop=True)
        r = {"name": name, "src": fname, "start_s": round(start / SR, 4), "start_sample": start, "frames": SONG_FRAMES,
             "orig_lufs": orig_lufs, **norm, "noise_floor_before_db": round(nf_before, 1),
             "noise_floor_after_db": round(nf_after, 1), "ogg_kb": round(ogg.stat().st_size / 1024)}
        r.update(check_loop(ogg, name, x, start + SONG_FRAMES, norm["gain_db"], SONG_FRAMES, SONG_BARS))
        out.append(r)
        print(json.dumps(r, ensure_ascii=False))
    return out


def song_mixdown(levels: dict) -> dict:
    """앱 레벨로 모든 song_* 레이어를 섞은 미리듣기(x3)와 피크·라우드니스."""
    acc = None
    for key, lv in levels.items():
        y = read_stereo(OUT_RAW / f"song_{key}.ogg")[LOOP_PAD:LOOP_PAD + SONG_FRAMES] * lv
        acc = y if acc is None else acc + y
    acc *= 0.8  # LoopMixer masterGain
    write_wav(PREVIEW / "song_mix_x3.wav", np.clip(np.concatenate([acc, acc, acc]), -1, 1))
    return {"peak_dbfs": round(float(20 * np.log10(np.abs(acc).max())), 1), "lufs": lufs_of(np.clip(acc, -1, 1))}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", default=str(ROOT / "assets-src/suno_raw"))
    ap.add_argument("--only", default="")
    ap.add_argument("--song", default="", help="한 곡 스템 폴더(지정하면 그 곡만 처리)")
    ap.add_argument("--start-bar", type=int, default=44)
    args = ap.parse_args()
    for tool in ("ffmpeg", "oggenc"):
        if not shutil.which(tool):
            sys.exit(f"{tool} 필요")
    TMP.mkdir(parents=True, exist_ok=True)
    PREVIEW.mkdir(parents=True, exist_ok=True)
    if args.song:
        rep = process_song(Path(args.song), args.start_bar)
        # 앱 기본 레벨(StemConfig.SONG_LEVELS와 같게 유지)
        levels = {"drums": 0.80, "bass": 0.80, "keys": 0.70, "guitar": 0.60, "synth": 0.55, "perc": 0.50}
        mixr = song_mixdown(levels)
        print(json.dumps({"mixdown": mixr, "levels": levels}, ensure_ascii=False))
        (PREVIEW / "song_report.json").write_text(json.dumps({"stems": rep, "mixdown": mixr, "levels": levels}, ensure_ascii=False, indent=1))
        shutil.rmtree(TMP, ignore_errors=True)
        return
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
    rep_name = "report.json" if not only else f"report_{'_'.join(sorted(only))}.json"
    (PREVIEW / rep_name).write_text(json.dumps(report, ensure_ascii=False, indent=1))
    shutil.rmtree(TMP, ignore_errors=True)


if __name__ == "__main__":
    main()
