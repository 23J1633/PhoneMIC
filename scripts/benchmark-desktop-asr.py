"""Measure local ASR startup, decoding, memory, and window responsiveness."""

from __future__ import annotations

import argparse
import ctypes
import gc
import json
import platform
import statistics
import subprocess
import sys
import threading
import time
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / ".cache" / "desktop-asr-benchmark"
sys.path.insert(0, str(CACHE / "deps"))

import psutil


def emit(event: str, **values: object) -> None:
    print(json.dumps({"event": event, **values}, ensure_ascii=False), flush=True)


def percentile(values: list[float], fraction: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, int((len(ordered) - 1) * fraction))]


def processor_name() -> str:
    if sys.platform == "win32":
        import winreg
        with winreg.OpenKey(
            winreg.HKEY_LOCAL_MACHINE,
            r"HARDWARE\DESCRIPTION\System\CentralProcessor\0",
        ) as key:
            return winreg.QueryValueEx(key, "ProcessorNameString")[0].strip()
    return platform.processor()


class Monitor:
    def __init__(self) -> None:
        self.samples: list[dict] = []
        self.phase = "baseline"
        self.stopped = threading.Event()
        self.thread = threading.Thread(target=self.run, daemon=True)

    def run(self) -> None:
        process = psutil.Process()
        psutil.cpu_percent()
        user32 = ctypes.WinDLL("user32", use_last_error=True)
        user32.GetForegroundWindow.restype = ctypes.c_void_p
        user32.SendMessageTimeoutW.argtypes = [
            ctypes.c_void_p, ctypes.c_uint, ctypes.c_size_t, ctypes.c_ssize_t,
            ctypes.c_uint, ctypes.c_uint, ctypes.POINTER(ctypes.c_size_t),
        ]
        user32.SendMessageTimeoutW.restype = ctypes.c_size_t
        while not self.stopped.is_set():
            memory = psutil.virtual_memory()
            window = user32.GetForegroundWindow()
            response_ms = None
            timeout = False
            if window:
                result = ctypes.c_size_t()
                started = time.perf_counter()
                success = user32.SendMessageTimeoutW(
                    window, 0, 0, 0, 0x0002, 100, ctypes.byref(result)
                )
                response_ms = (time.perf_counter() - started) * 1000
                timeout = not bool(success)
            info = process.memory_info()
            self.samples.append({
                "phase": self.phase,
                "cpu_percent": psutil.cpu_percent(),
                "available_mib": memory.available / 2**20,
                "rss_mib": info.rss / 2**20,
                "private_mib": getattr(info, "private", info.vms) / 2**20,
                "window_response_ms": response_ms,
                "window_timeout": timeout,
            })
            self.stopped.wait(0.2)

    def start(self) -> None:
        self.thread.start()

    def finish(self) -> dict:
        self.stopped.set()
        self.thread.join(timeout=2)
        summaries = {}
        for phase in dict.fromkeys(sample["phase"] for sample in self.samples):
            samples = [sample for sample in self.samples if sample["phase"] == phase]
            responses = [sample["window_response_ms"] for sample in samples
                         if sample["window_response_ms"] is not None]
            summaries[phase] = {
                "samples": len(samples),
                "system_cpu_mean_percent": statistics.mean(s["cpu_percent"] for s in samples),
                "available_memory_min_mib": min(s["available_mib"] for s in samples),
                "process_rss_max_mib": max(s["rss_mib"] for s in samples),
                "process_private_max_mib": max(s["private_mib"] for s in samples),
                "window_response_p95_ms": percentile(responses, 0.95),
                "window_response_max_ms": max(responses, default=None),
                "window_timeouts_100ms": sum(s["window_timeout"] for s in samples),
            }
        return summaries


def worker(threads: int, output: Path) -> None:
    monitor = Monitor()
    monitor.start()
    time.sleep(2)
    monitor.phase = "imports"
    imported_at = time.perf_counter()
    import numpy as np
    import sherpa_onnx
    import_seconds = time.perf_counter() - imported_at

    model = ROOT / "android/app/src/main/assets/model-qwen"
    monitor.phase = "model_load"
    started = time.perf_counter()
    recognizer = sherpa_onnx.OfflineRecognizer.from_qwen3_asr(
        conv_frontend=str(model / "conv_frontend.onnx"),
        encoder=str(model / "encoder.int8.onnx"),
        decoder=str(model / "decoder.int8.onnx"),
        tokenizer=str(model / "tokenizer"),
        num_threads=threads,
        provider="cpu",
        max_total_len=1024,
        max_new_tokens=256,
    )
    load_seconds = time.perf_counter() - started
    emit("model_ready", threads=threads, load_seconds=round(load_seconds, 3))

    fixture_dir = ROOT / "android/app/src/androidTest/assets"
    def pcm(name: str):
        return np.fromfile(fixture_dir / f"{name}.pcm", dtype="<i2").astype(np.float32) / 32768.0

    zh = pcm("zh")
    en = pcm("en")
    silence = np.zeros(4800, dtype=np.float32)
    bilingual = np.concatenate([zh, silence, en])
    long_audio = np.concatenate([zh, silence, en, silence, zh, silence, en])
    cases = [
        ("zh_first", zh), ("zh_warm_1", zh), ("zh_warm_2", zh),
        ("en", en), ("zh_en", bilingual),
        ("natural_mixed", pcm("mixed-natural")),
        ("zh_with_english_terms", pcm("mixed-zh-words")),
        ("long_zh_en", long_audio),
    ]
    results = []
    monitor.phase = "decode"
    for name, samples in cases:
        stream = recognizer.create_stream()
        started = time.perf_counter()
        stream.accept_waveform(16000, samples)
        recognizer.decode_stream(stream)
        text = stream.result.text
        seconds = time.perf_counter() - started
        duration = len(samples) / 16000
        row = {
            "case": name, "audio_seconds": duration,
            "decode_seconds": seconds, "rtf": seconds / duration,
            "text": text,
        }
        results.append(row)
        emit("decode", threads=threads, **row)
        del stream

    info = psutil.Process().memory_info()
    report = {
        "threads": threads,
        "backend": "CPU",
        "runtime": "sherpa-onnx",
        "runtime_version": sherpa_onnx.__version__,
        "numpy_version": np.__version__,
        "python_version": platform.python_version(),
        "imports_seconds": import_seconds,
        "model_load_seconds": load_seconds,
        "runtime_import_and_model_load_seconds": import_seconds + load_seconds,
        "model_to_first_result_seconds": load_seconds + results[0]["decode_seconds"],
        "process_peak_working_set_mib": getattr(info, "peak_wset", info.rss) / 2**20,
        "cases": results,
        "monitor": monitor.finish(),
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    del recognizer
    gc.collect()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--worker", action="store_true")
    parser.add_argument("--threads", type=int, default=3)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.worker:
        worker(args.threads, args.output)
        return
    report = {
        "timestamp_utc": datetime.now(timezone.utc).isoformat(),
        "machine": {
            "platform": platform.platform(),
            "processor": processor_name(),
            "physical_cores": psutil.cpu_count(logical=False),
            "logical_cores": psutil.cpu_count(),
            "memory_total_gib": psutil.virtual_memory().total / 2**30,
        },
        "notes": [
            "Uses the existing PhoneMIC Qwen3-ASR-0.6B INT8 model and test PCM fixtures.",
            "Each thread configuration runs in a fresh process, sequentially.",
            "OS file cache is not cleared; model initialization is not a cold-boot disk benchmark.",
            "Foreground WM_NULL response measures message-loop availability, not animation frame rate.",
            "No Android emulator is used; this is native Windows CPU inference.",
        ],
        "runs": [],
    }
    for threads in (3, 6):
        output = CACHE / f"result-{threads}-threads.json"
        started = time.perf_counter()
        subprocess.run(
            [sys.executable, "-X", "utf8", str(Path(__file__).resolve()),
             "--worker", "--threads", str(threads), "--output", str(output)],
            check=True, timeout=180,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )
        result = json.loads(output.read_text(encoding="utf-8"))
        result["whole_worker_seconds_including_baseline_and_all_cases"] = time.perf_counter() - started
        report["runs"].append(result)
    destination = ROOT / "artifacts/desktop-asr-benchmark.json"
    destination.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    emit("complete", report=str(destination))


if __name__ == "__main__":
    main()
