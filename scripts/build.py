#!/usr/bin/env python3
"""Build the companion APK and a KernelSU module ZIP without Gradle.

Dependencies: Python >=3.10, JDK 17, Android Platform 35, Build Tools 35.0.0.
No third-party Python packages are required. No device is contacted.
"""
from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
API_URL = "https://api.xposed.info/de/robv/android/xposed/api/82/api-82.jar"
API_SHA256 = "f48c635f1c7469fdec0e00ad2ea0b7a6b2f5b55065784a35b7ca3a84615e8e25"
WINDOWS = os.name == "nt"


def run(arguments: list[str | Path], *, env: dict | None = None) -> None:
    subprocess.run([str(a) for a in arguments], check=True, env=env)


def java_tool(name: str) -> Path:
    executable = name + (".exe" if WINDOWS else "")
    home = os.environ.get("JAVA_HOME")
    candidate = Path(home) / "bin" / executable if home else None
    if candidate and candidate.is_file():
        return candidate
    found = shutil.which(executable)
    if not found:
        raise RuntimeError(f"Cannot find {name}; install JDK 17 and set JAVA_HOME.")
    return Path(found)


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def zip_file(archive: zipfile.ZipFile, name: str, data: bytes, executable=False) -> None:
    info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
    info.create_system = 3
    info.external_attr = (0o100755 if executable else 0o100644) << 16
    info.compress_type = zipfile.ZIP_DEFLATED
    archive.writestr(info, data)


def run_checks(build: Path) -> None:
    classes = build / "checks"
    classes.mkdir(parents=True, exist_ok=True)
    run([java_tool("javac"), "-encoding", "UTF-8", "-d", classes,
         ROOT / "app/src/main/java/cn/local/mijiabattery/Logic.java",
         ROOT / "tests/LogicCheck.java"])
    run([java_tool("java"), "-cp", classes, "LogicCheck"])
    shell = shutil.which("sh")
    for script in sorted((ROOT / "module").glob("*.sh")):
        data = script.read_bytes()
        if data.startswith(b"\xef\xbb\xbf") or b"\r" in data:
            raise RuntimeError(f"{script.name} must be UTF-8 without BOM and use LF.")
        if shell:
            run([shell, "-n", script])
    if not shell:
        print("Note: shell syntax check skipped (sh unavailable).")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, default=os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT"))
    parser.add_argument("--android-jar", type=Path, help="Override Platform 35 android.jar")
    parser.add_argument("--build-tools", type=Path, help="Override Build Tools 35.0.0 directory")
    parser.add_argument("--xposed-api", type=Path, help="Use an existing, hash-checked Xposed API 82 jar")
    parser.add_argument("--signing", choices=["development", "release"], default="development")
    parser.add_argument("--check-only", action="store_true")
    args = parser.parse_args()
    version = (ROOT / "version.txt").read_text(encoding="ascii").strip()
    # Fresh build directories avoid stale anonymous Java classes after source edits.
    build = ROOT / "build" / ("run-" + __import__("uuid").uuid4().hex[:12])
    build.mkdir(parents=True)
    run_checks(build)
    if args.check_only:
        return

    sdk = Path(args.sdk) if args.sdk else None
    android_jar = args.android_jar or (sdk / "platforms/android-35/android.jar" if sdk else None)
    tools = args.build_tools or (sdk / "build-tools/35.0.0" if sdk else None)
    if not android_jar or not android_jar.is_file() or not tools or not tools.is_dir():
        raise RuntimeError("Platform 35 / Build Tools 35.0.0 missing. See docs/BUILD.md.")
    suffix = ".exe" if WINDOWS else ""
    aapt2, zipalign = tools / ("aapt2" + suffix), tools / ("zipalign" + suffix)
    d8, signer = tools / "lib/d8.jar", tools / "lib/apksigner.jar"
    for path in [aapt2, zipalign, d8, signer]:
        if not path.is_file():
            raise RuntimeError(f"Required Android build tool missing: {path}")

    api = args.xposed_api or ROOT / ".cache/xposed-api-82.jar"
    if not api.exists():
        if args.xposed_api:
            raise RuntimeError("Specified Xposed API jar does not exist.")
        api.parent.mkdir(parents=True, exist_ok=True)
        with urllib.request.urlopen(API_URL, timeout=60) as response:
            data = response.read()
        if hashlib.sha256(data).hexdigest() != API_SHA256:
            raise RuntimeError("Xposed API download checksum mismatch.")
        api.write_bytes(data)
    if sha256(api) != API_SHA256:
        raise RuntimeError("Xposed API 82 checksum mismatch.")

    classes, dex = build / "classes", build / "dex"
    classes.mkdir(); dex.mkdir()
    sources = sorted((ROOT / "app/src/main/java").rglob("*.java"))
    run([java_tool("javac"), "-encoding", "UTF-8", "-source", "8", "-target", "8",
         "-cp", os.pathsep.join(map(str, [android_jar, api])), "-d", classes, *sources])
    compiled = build / "compiled.jar"
    run([java_tool("jar"), "cf", compiled, "-C", classes, "."])
    run([java_tool("java"), "-cp", d8, "com.android.tools.r8.D8", "--min-api", "28",
         "--lib", android_jar, "--classpath", api, "--output", dex, compiled])
    resources, unsigned, aligned = build / "resources.zip", build / "unsigned.apk", build / "aligned.apk"
    run([aapt2, "compile", "--dir", ROOT / "app/src/main/res", "-o", resources])
    run([aapt2, "link", "-o", unsigned, "--manifest", ROOT / "app/src/main/AndroidManifest.xml",
         "-I", android_jar, "-A", ROOT / "app/src/main/assets", resources])
    with zipfile.ZipFile(unsigned, "a", zipfile.ZIP_DEFLATED) as z:
        z.write(dex / "classes.dex", "classes.dex")
    run([zipalign, "-f", "4", unsigned, aligned])

    sign_env = dict(os.environ)
    if args.signing == "release":
        required = ["MIJIA_KEYSTORE", "MIJIA_KEY_ALIAS", "MIJIA_STORE_PASSWORD"]
        if any(not sign_env.get(k) for k in required):
            raise RuntimeError("Release signing requires " + ", ".join(required))
        key = Path(sign_env["MIJIA_KEYSTORE"]).resolve()
        alias = sign_env["MIJIA_KEY_ALIAS"]
        store_env = "MIJIA_STORE_PASSWORD"
        key_env = "MIJIA_KEY_PASSWORD" if sign_env.get("MIJIA_KEY_PASSWORD") else store_env
    else:
        key = ROOT / ".cache/development-signing.p12"
        key.parent.mkdir(parents=True, exist_ok=True)
        alias, store_env, key_env = "development", "MIJIA_DEV_PASSWORD", "MIJIA_DEV_PASSWORD"
        sign_env[store_env] = "development-only"
        if not key.exists():
            run([java_tool("keytool"), "-genkeypair", "-keystore", key, "-storetype", "PKCS12",
                 "-storepass:env", store_env, "-keypass:env", key_env, "-alias", alias,
                 "-keyalg", "RSA", "-keysize", "3072", "-validity", "3650",
                 "-dname", "CN=Mijia Battery Temperature Development", "-noprompt"], env=sign_env)
        print("Development signing: this key is local to this checkout; do not use for distributed updates.")

    output = ROOT / "dist"
    output.mkdir(exist_ok=True)
    apk = output / f"MijiaBatteryTemperature-{version}.apk"
    run([java_tool("java"), "-jar", signer, "sign", "--ks", key, "--ks-key-alias", alias,
         "--ks-pass", "env:" + store_env, "--key-pass", "env:" + key_env,
         "--out", apk, aligned], env=sign_env)
    run([java_tool("java"), "-jar", signer, "verify", "--verbose", apk])
    module = output / f"MijiaBatteryTemperature-KSU-{version}.zip"
    with zipfile.ZipFile(module, "w", zipfile.ZIP_DEFLATED) as z:
        for path in sorted((ROOT / "module").iterdir()):
            if path.is_file():
                zip_file(z, path.name, path.read_bytes(), executable=path.suffix == ".sh")
        zip_file(z, "README.md", (ROOT / "README.md").read_bytes())
    for archive in [apk, module]:
        with zipfile.ZipFile(archive) as z:
            if z.testzip():
                raise RuntimeError(f"ZIP CRC check failed: {archive.name}")
    checksum = output / "SHA256SUMS.txt"
    checksum.write_text("\n".join(f"{sha256(p)}  {p.name}" for p in [module, apk]) + "\n", encoding="utf-8")
    print("Built APK, KernelSU ZIP, checksums in:", output)


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, subprocess.CalledProcessError) as error:
        print("Build failed:", error, file=sys.stderr)
        raise SystemExit(1)
