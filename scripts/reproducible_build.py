"""
PlatformIO pre-build script: make release images independent of the machine
that builds them, so a local build matches the CI release asset byte for byte.

Two things leaked into firmware.bin before:
- absolute paths through __FILE__ (asserts, logs): /Users/<name>/.platformio/...
  on a Mac, /home/runner/.platformio/... on CI. -ffile-prefix-map rewrites the
  project and the PlatformIO package directories to fixed names.
- the build time through __DATE__/__TIME__. GCC and clang take both from
  SOURCE_DATE_EPOCH when it is set; release environments set it to the HEAD
  commit time unless the caller already did. The same value also goes in as a
  define: SCons and the build cache key objects by their command line, not by
  the environment, so without it an incremental build would keep the previous
  commit's time.
"""

import os
import subprocess

Import("env")  # noqa: F821  (SCons global)


def commit_epoch(project_dir):
    try:
        return subprocess.check_output(
            ["git", "log", "-1", "--format=%ct"],
            text=True, stderr=subprocess.DEVNULL, cwd=project_dir,
        ).strip()
    except (OSError, subprocess.CalledProcessError):
        return ""


project_dir = env.subst("$PROJECT_DIR")  # noqa: F821
packages_dir = env.subst("$PROJECT_PACKAGES_DIR")  # noqa: F821
core_dir = env.subst("$PROJECT_CORE_DIR")  # noqa: F821

# Longest prefix first: the packages directory usually sits inside the core one.
maps = [(packages_dir, "/pio/packages"), (core_dir, "/pio"), (project_dir, ".")]
env.Append(CCFLAGS=[f"-ffile-prefix-map={src}={dst}" for src, dst in maps if src])  # noqa: F821

# Development builds keep the real build time and their incremental speed.
pioenv = env["PIOENV"]  # noqa: F821
if "gh_release" in pioenv or pioenv.endswith("-nobt"):
    epoch = os.environ.get("SOURCE_DATE_EPOCH") or commit_epoch(project_dir)
    if epoch:
        env["ENV"]["SOURCE_DATE_EPOCH"] = epoch  # noqa: F821
        env.Append(CPPDEFINES=[("FOLIO_SOURCE_DATE_EPOCH", epoch)])  # noqa: F821
        print(f"reproducible_build: SOURCE_DATE_EPOCH={epoch}")
