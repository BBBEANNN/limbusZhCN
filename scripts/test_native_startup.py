#!/usr/bin/env python3
"""编译并运行真实 ARM64 重定位源码的宿主回归；不需要 Android 设备。"""

from pathlib import Path
import os
import shutil
import subprocess
import sys


def main() -> int:
    """选择宿主 C++ 编译器，在诊断目录生成并运行回归程序。"""
    root = Path(__file__).resolve().parents[1]
    build = root / ".diagnostics" / "native-startup-tests"
    build.mkdir(parents=True, exist_ok=True)
    source = root / "scripts" / "native_startup_test.cpp"
    environment = os.environ.copy()
    if os.name == "nt":
        # 独立设置 MSVC 与 Windows SDK 路径，不要求用户预先打开开发者命令行。
        installations = sorted(Path("C:/Program Files/Microsoft Visual Studio").glob(
            "*/*/VC/Tools/MSVC/*/bin/Hostx64/x64/cl.exe"))
        if not installations:
            raise RuntimeError("需要 MSVC x64 编译器运行宿主 native 回归")
        compiler = installations[-1]
        msvc = compiler.parents[3]
        sdk = Path("C:/Program Files (x86)/Windows Kits/10")
        versions = sorted(path for path in (sdk / "Include").glob("*") if (path / "ucrt").is_dir())
        if not versions:
            raise RuntimeError("需要 Windows 10/11 SDK 运行宿主 native 回归")
        version = versions[-1].name
        environment["PATH"] = str(compiler.parent) + os.pathsep + environment.get("PATH", "")
        environment["INCLUDE"] = ";".join(str(path) for path in [
            msvc / "include", sdk / "Include" / version / "ucrt",
            sdk / "Include" / version / "shared", sdk / "Include" / version / "um"])
        environment["LIB"] = ";".join(str(path) for path in [
            msvc / "lib" / "x64", sdk / "Lib" / version / "ucrt" / "x64",
            sdk / "Lib" / version / "um" / "x64"])
        executable = build / "native-startup-test.exe"
        command = [str(compiler), "/nologo", "/std:c++17", "/EHsc", "/utf-8",
                   str(source), "/Fe:" + str(executable), "/Fo:" + str(build / "native-startup-test.obj")]
    else:
        compiler = environment.get("CXX") or shutil.which("clang++") or shutil.which("g++")
        if not compiler:
            raise RuntimeError("需要 clang++ 或 g++ 运行宿主 native 回归")
        executable = build / "native-startup-test"
        command = [compiler, "-std=c++17", "-O1", "-g", str(source), "-o", str(executable)]
    subprocess.run(command, cwd=root, env=environment, check=True)
    return subprocess.run([str(executable)], cwd=root, env=environment, check=False).returncode


if __name__ == "__main__":
    sys.exit(main())
