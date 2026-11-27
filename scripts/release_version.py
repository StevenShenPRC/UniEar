"""Resolve stable/beta release metadata from a tag or the HEAD commit subject."""
import os
import re
import subprocess
from pathlib import Path

VERSION = r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)"


def resolve(subject, ref_type, ref_name, configured):
    if ref_type == "tag":
        match = re.fullmatch(rf"(v|beta-v)({VERSION})", ref_name)
        if not match:
            raise ValueError("Expected vX.Y.Z or beta-vX.Y.Z tag")
        beta, version = match[1] == "beta-v", match[2]
    else:
        match = re.match(rf"^(release|beta)-v({VERSION})(?:$|\s+|:\s*)", subject.splitlines()[0] if subject else "")
        if not match:
            if subject.startswith(("release-v", "beta-v")):
                raise ValueError("Malformed release version or missing prefix delimiter")
            return None
        beta, version = match[1] == "beta", match[2]
    if version != configured:
        raise ValueError(f"Requested {version} must match Android versionName {configured}")
    return {"tag": f"beta-v{version}" if beta else f"v{version}", "prerelease": str(beta).lower()}


def main():
    subject = subprocess.check_output(["git", "log", "-1", "--format=%s"], text=True, encoding="utf-8").strip()
    gradle = Path("android/build.gradle.kts").read_text(encoding="utf-8")
    match = re.search(r'^\s*versionName\s*=\s*"([^"]+)"\s*$', gradle, re.M)
    if match is None:
        raise ValueError("Missing Android versionName")
    result = resolve(subject, os.environ["REF_TYPE"], os.environ["REF_NAME"], match[1])
    outputs = {"enabled": str(result is not None).lower(),
               "publish": str(result is not None and os.environ["EVENT_NAME"] == "push").lower(),
               **(result or {"tag": "", "prerelease": "false"})}
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
        output.writelines(f"{key}={value}\n" for key, value in outputs.items())
    print(outputs)


if __name__ == "__main__":
    main()
