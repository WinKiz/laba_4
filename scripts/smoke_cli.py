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
base = [java, "-jar", str(jar)]
summary = {}

with tempfile.TemporaryDirectory(prefix="cli-check-", dir=root / "target") as temporary:
    work = Path(temporary)
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
    run("unavailable-server", ["--host", "127.0.0.1", "--port", str(port), "--scenario", "1", "--warmups", "0", "--repeats", "1", "--timeout", "0.2", "--out", "failed"], 1)

(root / "target/cli-checks.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
