"""Check the real command-line process exit codes and separate-server mode."""
from pathlib import Path
import json
import socket
import subprocess
import tempfile
import sys

root = Path(__file__).resolve().parents[1]
jar = root / "target/lab4-broker-client-1.0.jar"
assert jar.exists(), "Build the JAR first"
java = sys.argv[1] if len(sys.argv) > 1 else "java"
if len(sys.argv) == 1:
    # Windows javapath/java.exe may be a launcher stub with a separate child PID.
    settings = subprocess.run([java, "-XshowSettings:properties", "-version"], capture_output=True).stderr
    for line in settings.decode(errors="replace").splitlines():
        if line.strip().startswith("java.home ="):
            candidate = Path(line.split("=", 1)[1].strip()) / "bin" / ("java.exe" if sys.platform == "win32" else "java")
            if candidate.is_file():
                java = str(candidate)
base = [java, "-jar", str(jar)]
summary = {}

with tempfile.TemporaryDirectory(prefix="cli-check-", dir=root / "target") as temporary:
    work = Path(temporary)
    assert work.resolve().is_relative_to((root / "target").resolve())
    def run(name, args, expected, timeout=60):
        result = subprocess.run(base + args, cwd=work, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout)
        (root / "target" / (name + "-check.log")).write_bytes(result.stdout)
        assert result.returncode == expected, (name, result.returncode, result.stdout[-1500:])
        summary[name] = {"expected": expected, "actual": result.returncode}
        print("PASS", name, "exit", result.returncode)

    run("noargs", [], 0)
    run("invalid-option", ["--unknown"], 1)
    run("invalid-count", ["--messages", "999"], 1)
    run("help", ["--help"], 0)
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]
    server = subprocess.Popen(base + ["--server", "--bind", "127.0.0.1", "--port", str(port)], cwd=work,
                              stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    try:
        startup = server.stdout.readline()
        assert b"Auxiliary broker" in startup, startup
        run("standalone-server", ["--host", "127.0.0.1", "--port", str(port), "--warmups", "0", "--repeats", "1", "--out", "external"], 0)
        run("existing-output", ["--out", "external"], 1)
    finally:
        server.terminate()
        server.wait(timeout=5)
    # A bound, non-listening socket provides a port on which no broker can accept.
    with socket.socket() as unavailable:
        unavailable.bind(("127.0.0.1", 0))
        unused_port = unavailable.getsockname()[1]
        run("unavailable-server", ["--host", "127.0.0.1", "--port", str(unused_port), "--scenario", "1", "--warmups", "0", "--repeats", "1", "--timeout", "0.2", "--out", "failed"], 1)

(root / "target/cli-checks.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
