"""Check out or update the pinned HyperEars protocol source."""

import argparse
import os
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
REPO = ROOT / "upstream" / "HyperEars"
PIN = REPO.parent / "hyperears.rev"
ORIGIN = "https://github.com/silverpoetry/HyperEars.git"
PATHS = (
    "/protocol/", "/integration/", "/LICENSE", "/THIRD_PARTY_NOTICES.md",
    "/CONTRIBUTING.md", "/docs/project-scope.md",
)


def git(*args, capture=False, no_lazy_fetch=False):
    result = subprocess.run(
        ["git", "-C", str(REPO), *args], check=True, text=True,
        stdout=subprocess.PIPE if capture else None,
        env=os.environ | {"GIT_NO_LAZY_FETCH": "1"} if no_lazy_fetch else None,
    )
    return result.stdout.strip() if capture else None


def ensure_repo():
    if not (REPO / ".git").exists():
        subprocess.run(
            ["git", "clone", "--filter=blob:none", "--depth=1", "--single-branch",
             "--branch", "main", "--sparse", ORIGIN, str(REPO)],
            cwd=ROOT, check=True,
        )
        git("sparse-checkout", "set", "--no-cone", *PATHS)
    if git("remote", "get-url", "origin", capture=True) != ORIGIN:
        raise ValueError("HyperEars 的 origin 与项目配置不一致")
    if git("status", "--porcelain", "--no-ahead-behind", capture=True, no_lazy_fetch=True):
        raise ValueError("HyperEars 检出中有本地修改，请先保存这些修改")


def checkout(revision):
    if subprocess.run(["git", "-C", str(REPO), "cat-file", "-e", revision + "^{commit}"],
                      capture_output=True, env=os.environ | {"GIT_NO_LAZY_FETCH": "1"}).returncode:
        git("fetch", "--depth=1", "origin", revision)
    head = git("rev-parse", "HEAD", capture=True)
    if head != revision or subprocess.run(
        ["git", "-C", str(REPO), "symbolic-ref", "-q", "HEAD"], stdout=subprocess.DEVNULL,
    ).returncode == 0:
        git("checkout", "--detach", revision)


def verify(revision, android):
    wrapper = ["cmd.exe", "/d", "/c", "gradlew.bat"] if os.name == "nt" else ["sh", "./gradlew"]
    tasks = [":core:test", ":desktop:compileKotlin"]
    if android:
        tasks.append(":android:assembleDebug")
    else:
        tasks.append("-PdesktopOnly")
    subprocess.run(wrapper + [f"-PhyperearsRevision={revision}", *tasks], cwd=ROOT, check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("checkout", "update"))
    parser.add_argument("--android", action="store_true", help="更新时同时构建 Android APK")
    args = parser.parse_args()
    pinned = PIN.read_text(encoding="ascii").strip()
    if not re.fullmatch("[a-f0-9]{40}", pinned):
        raise ValueError("上游版本文件必须包含完整 Git 提交 SHA")
    ensure_repo()
    if args.action == "checkout":
        checkout(pinned)
        return
    git("fetch", "--depth=1", "origin", "main")
    candidate = git("rev-parse", "FETCH_HEAD", capture=True)
    if candidate == pinned:
        checkout(pinned)
        print("HyperEars 已是当前 main 版本。")
        return
    previous = git("rev-parse", "HEAD", capture=True)
    try:
        checkout(candidate)
        verify(candidate, args.android)
        temporary = PIN.with_suffix(".rev.tmp")
        temporary.write_text(candidate + "\n", encoding="ascii")
        temporary.replace(PIN)
    except (Exception, KeyboardInterrupt):
        checkout(previous)
        raise
    print(f"HyperEars：{pinned[:12]} → {candidate[:12]}")


if __name__ == "__main__":
    try:
        main()
    except (OSError, subprocess.CalledProcessError, ValueError) as error:
        sys.exit(str(error))
