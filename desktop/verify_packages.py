"""Exercise every installer payload on the native build host.

The smoke test needs no phone, account, pairing, GUI, or network. It starts the
same packaged executable registered in AI hosts and verifies JSON-RPC framing
and the MCP initialization/ping contract. This is not a signing or GUI test.
"""

import json
import os
from pathlib import Path
import platform
import subprocess
import sys
import tempfile


def run(*args, **kwargs):
    """Run a native extraction utility, propagating failures and timeouts."""
    return subprocess.run(args, check=True, timeout=180, **kwargs)


def one_file(root, suffix, version):
    """Reject missing, duplicate or empty packages for the current version."""
    matches = [
        path for path in (root / suffix).glob("*." + suffix)
        if path.is_file() and path.stat().st_size and (
            "-" + version + "." in path.name
            or "-" + version + "-" in path.name
            or "_" + version + "_" in path.name
        )
    ]
    if len(matches) != 1:
        raise RuntimeError(f"Expected one {suffix} installer for {version}, got {matches}")
    return matches[0].resolve()


def handshake(root, name):
    """Check the installed launcher, using literal independently expected RPC results."""
    launchers = [
        path for path in root.rglob("*")
        if path.name.casefold() == name.casefold() and path.is_file()
        and (name.endswith(".exe") or os.access(path, os.X_OK))
    ]
    if len(launchers) != 1:
        raise RuntimeError(f"Expected one {name} launcher in {root}, got {launchers}")

    # ADB mode is deliberately selected: initialize and ping do not call ADB.
    # Strip inherited MCP configuration so no production device can be contacted.
    env = {key: value for key, value in os.environ.items()
           if not key.upper().startswith("MCPSERVED_")}
    env["MCPSERVED_MODE"] = "adb"
    requests = (
        '{"jsonrpc":"2.0","id":17,"method":"initialize","params":'
        '{"protocolVersion":"2025-06-18","capabilities":{},'
        '"clientInfo":{"name":"installer-smoke","version":"1"}}}\n'
        '{"jsonrpc":"2.0","method":"notifications/initialized"}\n'
        '{"jsonrpc":"2.0","id":23,"method":"ping"}\n'
    )
    result = subprocess.run(
        [str(launchers[0].resolve()), "stdio"],
        input=requests, capture_output=True, text=True, encoding="utf-8",
        errors="replace", env=env, cwd=root, timeout=60,
    )
    if result.returncode != 0:
        raise RuntimeError(f"Packaged launcher exited {result.returncode}: {result.stderr}")
    responses = [json.loads(line) for line in result.stdout.splitlines()]
    if len(responses) != 2:
        raise RuntimeError(f"Expected exactly two JSON-RPC responses: {result.stdout!r}")
    initial, ping = responses
    if (initial.get("jsonrpc") != "2.0" or initial.get("id") != 17
            or initial.get("result", {}).get("protocolVersion") != "2025-06-18"
            or initial.get("result", {}).get("serverInfo", {}).get("name") != "mcpserved"
            or "tools" not in initial.get("result", {}).get("capabilities", {})):
        raise RuntimeError(f"Invalid MCP initialize response: {initial}")
    if ping != {"jsonrpc": "2.0", "id": 23, "result": {}}:
        raise RuntimeError(f"Invalid MCP ping response: {ping}")
    print(f"MCP stdio smoke passed: {launchers[0]}", flush=True)


def verify(root, version):
    """Extract or mount the generated packages without installing them system-wide."""
    system = platform.system()
    with tempfile.TemporaryDirectory(prefix="mcpserved-installer-") as temp:
        work = Path(temp)
        if system == "Linux":
            deb = one_file(root, "deb", version)
            deb_root = work / "deb"
            deb_root.mkdir()
            run("dpkg-deb", "-x", str(deb), str(deb_root))
            handshake(deb_root, "MCPserved")

            rpm = one_file(root, "rpm", version)
            rpm_root = work / "rpm"
            rpm_root.mkdir()
            # Use a file rather than a shell pipeline, so both return codes are checked.
            archive = work / "payload.cpio"
            with archive.open("wb") as output:
                run("rpm2cpio", str(rpm), stdout=output)
            with archive.open("rb") as payload:
                run("cpio", "-idm", "--no-absolute-filenames", cwd=rpm_root, stdin=payload)
            handshake(rpm_root, "MCPserved")
        elif system == "Darwin":
            dmg = one_file(root, "dmg", version)
            mount = work / "mount"
            mount.mkdir()
            run("hdiutil", "attach", str(dmg), "-readonly", "-nobrowse", "-mountpoint", str(mount))
            try:
                handshake(mount, "MCPserved")
            finally:
                run("hdiutil", "detach", str(mount))
        elif system == "Windows":
            msi = one_file(root, "msi", version)
            target = work / "admin"
            target.mkdir()
            log = work / "msi.log"
            try:
                run("msiexec.exe", "/a", str(msi), "/qn", "/norestart",
                    f"TARGETDIR={target}", "/L*v", str(log))
            except subprocess.CalledProcessError:
                if log.exists():
                    print(log.read_text(encoding="utf-16", errors="replace")[-12000:], file=sys.stderr)
                raise
            handshake(target, "MCPserved.exe")
        else:
            raise RuntimeError(f"Unsupported packaging host: {system}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("Usage: verify_packages.py PACKAGE_ROOT VERSION")
    verify(Path(sys.argv[1]).resolve(), sys.argv[2])
