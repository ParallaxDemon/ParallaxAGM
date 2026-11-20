#!/data/data/ai.antigem/files/usr/bin/bash
# ParallaxAI terminal client for AntiGem.
# Packaged by Gradle as assets/bin/parallax and unpacked into $PREFIX/bin by LocalServerManager.
# Uses only Python's standard library; no pip installs, no browser, no extra setup.

PY="${PANEL_PYTHON:-}"
if [ -z "$PY" ]; then
    for candidate in "${PREFIX:-}/bin/python3" \
                     "/data/data/ai.antigem/files/usr/bin/python3" \
                     "/data/data/ai.antidev/files/usr/bin/python3" \
                     "python3" "python"; do
        if [ -n "$candidate" ] && { [ -x "$candidate" ] || command -v "$candidate" >/dev/null 2>&1; }; then
            PY="$candidate"
            break
        fi
    done
fi
if [ -z "$PY" ]; then
    echo "error: python3 not found. Install it with: pkg install python" >&2
    exit 1
fi

exec "$PY" - "$@" <<'PYEOF'
#!/usr/bin/env python3
"""ParallaxAI terminal client — chat, batch chats, status, artifacts and agent skill.

Everything happens through the terminal: jobs are dispatched to the live panel and the
GitHub Actions worker does the heavy work (tools, APK analysis, patching), so the phone
only needs this one small script.

Config is optional: sane defaults are baked in. Override with `parallax setup`, or with
the PANEL_BASE_URL / PANEL_API_KEY environment variables (they win over saved config).
"""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

DEFAULT_BASE = "https://ai.parallaxserver.online"
DEFAULT_KEY = "b451dbad82be9edfd821db3b960f3c5d5b404af7290f329bbfcb87dd0133fc87"
CONFIG = Path.home() / ".config" / "parallax-ai" / "config.json"
TERMINAL = ("done", "failed", "dispatch_failed")
MARK = {"done": "[OK]", "failed": "[FAIL]", "dispatch_failed": "[DISPATCH FAIL]",
        "queued": "[queued]", "running": "[running]"}
SKILL = """\
---
name: ParallaxAI
description: Use the ParallaxAI panel from the AntiGem terminal for fast DeepSeek chats, parallel batches, APK/DEX/static analysis and code patching.
---

# ParallaxAI terminal tool

`parallax` is installed in the Linux terminal (`$PREFIX/bin/parallax`). It calls the
ParallaxAI API; the work runs on GitHub Actions with real tools (jadx, apktool, grep,
git), so do ANY of the following from the terminal — never invent results yourself.

## Commands

```sh
parallax setup --key YOUR_API_KEY [--base https://ai.parallaxserver.online]
parallax chat "Analyze this function"                 # one chat, waits for the answer
parallax chat "question" --no-wait                    # just get a job id
parallax batch "Q1" "Q2" "Q3"                         # up to 20 chats, one command
parallax batch --file prompts.txt                     # one prompt per line
parallax status <job_or_batch_id>                     # progress + answers
parallax jobs --limit 10                              # recent jobs
parallax artifact <job_id> report.zip                 # download the report ZIP
parallax tools                                        # list every available command
```

## When to use it

- APK / DEX / native library / obfuscated code analysis: `parallax chat "analyze <path> ..."`.
- Several independent questions at once: `parallax batch ...` (all run in parallel).
- Long-running work: use `--no-wait`, then `parallax status <id>`.

Rules: the panel is the source of truth. Always report the real job/batch id and the
actual result text returned by `parallax status`. Do not guess API keys — ask the user
to run `parallax setup --key ...` if authentication fails.
"""


def die(message, code=1):
    print("error: " + str(message), file=sys.stderr)
    raise SystemExit(code)


def load_config():
    saved = {}
    if CONFIG.is_file():
        try:
            saved = json.loads(CONFIG.read_text(encoding="utf-8"))
        except (OSError, ValueError) as exc:
            die("cannot read config: " + str(exc))
    base = os.environ.get("PANEL_BASE_URL") or saved.get("base_url") or DEFAULT_BASE
    key = os.environ.get("PANEL_API_KEY") or saved.get("api_key") or DEFAULT_KEY
    return base.rstrip("/"), key


def save_config(base, key, reset=False):
    old_base, old_key = load_config()
    if reset:
        try:
            CONFIG.unlink()
        except FileNotFoundError:
            pass
        print("config reset to built-in defaults: " + DEFAULT_BASE)
        return
    base = (base or old_base).rstrip("/")
    key = key or old_key
    if not base.startswith("https://"):
        die("base URL must start with https://")
    if not key:
        die("API key must not be empty")
    CONFIG.parent.mkdir(parents=True, exist_ok=True)
    CONFIG.write_text(json.dumps({"base_url": base, "api_key": key}, indent=2), encoding="utf-8")
    try:
        os.chmod(CONFIG, 0o600)
    except OSError:
        pass
    print("saved " + str(CONFIG) + " (mode 600)")


def request(method, path, payload=None, raw=False, timeout=60, allow_404=False):
    base, key = load_config()
    if not base or not key:
        die("not configured. Run: parallax setup --key YOUR_API_KEY")
    url = base + "/api/v1/" + path.lstrip("/")
    data = None
    headers = {"Authorization": "Bearer " + key, "Accept": "application/json",
               "User-Agent": "ParallaxAI-AntiGem/1.0"}
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            body = response.read()
            return body if raw else (json.loads(body) if body.strip() else {})
    except urllib.error.HTTPError as exc:
        if exc.code == 404 and allow_404:
            return None
        body = exc.read().decode("utf-8", "replace")
        try:
            detail = json.loads(body)
        except ValueError:
            detail = body[:300]
        die("API returned HTTP {}: {}".format(exc.code, detail))
    except urllib.error.URLError as exc:
        die("cannot reach panel: " + str(exc.reason))


def short(text, limit=70):
    text = " ".join(str(text or "").split())
    return text if len(text) <= limit else text[:limit - 1] + "…"


def print_job(job, index=None):
    prefix = "{:>2}. ".format(index) if index else ""
    print("{}{} {}  {}".format(prefix, MARK.get(job.get("status"), "[?]"),
                               str(job.get("id", ""))[:12], short(job.get("prompt"), 50)))
    if job.get("status") == "done" and job.get("result"):
        print("      " + short(job["result"], 160))
    elif job.get("error"):
        print("      error: " + short(job["error"], 160))


def wait_job(job_id, timeout, interval=3):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        job = request("GET", "jobs/" + job_id)
        if job.get("status") != last:
            print(MARK.get(job.get("status"), "[?]") + " " + str(job.get("status")))
            last = job.get("status")
        if job.get("status") in TERMINAL:
            return job
        time.sleep(interval)
    die("timed out waiting for job " + job_id)


def wait_batch(batch_id, timeout, interval=3):
    deadline = time.monotonic() + timeout
    last = -1
    while time.monotonic() < deadline:
        batch = request("GET", "batch/" + batch_id)
        jobs = batch.get("jobs", [])
        if batch.get("done") != last:
            print("progress: {}/{} done".format(batch.get("done"), batch.get("count")))
            last = batch.get("done")
        if jobs and all(j.get("status") in TERMINAL for j in jobs):
            return batch
        time.sleep(interval)
    die("timed out waiting for batch " + batch_id)


def cmd_setup(args):
    save_config(args.base, args.key, args.reset)


def cmd_chat(args):
    result = request("POST", "jobs", {"kind": "chat", "prompt": args.prompt})
    if args.json and args.no_wait:
        print(json.dumps(result, indent=2, ensure_ascii=False))
        return
    print("job: " + result["id"])
    if args.no_wait:
        return
    job = wait_job(result["id"], args.timeout)
    if args.json:
        print(json.dumps(job, indent=2, ensure_ascii=False))
        return
    print("result:")
    print(job.get("result") or "")
    if job.get("status") != "done":
        print("error: " + str(job.get("error") or ""), file=sys.stderr)
        raise SystemExit(2)


def cmd_batch(args):
    prompts = list(args.prompts)
    if args.file:
        lines = Path(args.file).read_text(encoding="utf-8").splitlines()
        prompts.extend(line.strip() for line in lines if line.strip() and not line.lstrip().startswith("#"))
    if not prompts:
        die("provide at least one prompt, or use --file prompts.txt")
    if len(prompts) > 20:
        die("maximum 20 prompts per batch")
    result = request("POST", "batch", {"kind": "chat", "prompts": prompts})
    if args.json and args.no_wait:
        print(json.dumps(result, indent=2, ensure_ascii=False))
        return
    print("batch: {} ({} chats)".format(result["batch_id"], result["count"]))
    if args.no_wait:
        return
    batch = wait_batch(result["batch_id"], args.timeout)
    if args.json:
        print(json.dumps(batch, indent=2, ensure_ascii=False))
        return
    for i, job in enumerate(batch.get("jobs", []), 1):
        print("")
        print("--- chat {} | {} ---".format(i, job.get("id")))
        print("Q: " + str(job.get("prompt")))
        print("A: " + str(job.get("result") or "").strip())
        if job.get("error"):
            print("ERROR: " + str(job["error"]))
    if any(j.get("status") != "done" for j in batch.get("jobs", [])):
        raise SystemExit(2)


def cmd_status(args):
    target = args.target
    obj = None
    if len(target) == 32 and not args.batch:
        obj = request("GET", "jobs/" + target, allow_404=True)
    if obj is None:
        obj = request("GET", "batch/" + target)
        if args.json:
            print(json.dumps(obj, indent=2, ensure_ascii=False))
        else:
            print("batch {} — {}/{} done".format(obj["batch_id"], obj["done"], obj["count"]))
            for i, job in enumerate(obj.get("jobs", []), 1):
                print_job(job, i)
        return
    if args.json:
        print(json.dumps(obj, indent=2, ensure_ascii=False))
    else:
        print_job(obj)
        if obj.get("result"):
            print("")
            print(obj["result"])


def cmd_jobs(args):
    data = request("GET", "jobs?limit=" + str(args.limit))
    jobs = data.get("jobs", [])
    if args.json:
        print(json.dumps(jobs, indent=2, ensure_ascii=False))
    else:
        for i, job in enumerate(jobs, 1):
            print_job(job, i)


def cmd_artifact(args):
    body = request("GET", "jobs/" + args.job_id + "/artifact", raw=True)
    if body[:2] != b"PK":
        die("response is not a ZIP file")
    output = args.output or ("report-" + args.job_id[:12] + ".zip")
    Path(output).write_bytes(body)
    print("saved " + output + " (" + str(len(body)) + " bytes)")


def cmd_skill(args):
    home = Path.home()
    roots = [home / ".agents" / "skills" / "parallaxai", home / ".claude" / "skills" / "parallaxai"]
    dirs = []
    for root in roots:
        if root.exists() and not args.all:
            continue
        try:
            root.mkdir(parents=True, exist_ok=True)
            target = root / "SKILL.md"
            target.write_text(SKILL, encoding="utf-8")
            print("installed " + str(target))
            dirs.append(root)
        except OSError as exc:
            print("skip " + str(root) + ": " + str(exc), file=sys.stderr)
    if not dirs:
        print("skill already installed; use --all to rewrite")
    print("agents can now call `parallax` from the terminal.")


def cmd_tools(args):
    print("ParallaxAI terminal tools (all run from this terminal):")
    for name, description in [
        ("setup", "save API key / panel URL (or reset to defaults)"),
        ("chat", "one chat; waits for the answer"),
        ("batch", "1-20 chats in one command, run in parallel"),
        ("status", "show a job or a whole batch"),
        ("jobs", "list recent jobs"),
        ("artifact", "download a job report ZIP"),
        ("skill", "install the agent skill that documents all of the above"),
        ("tools", "show this list"),
    ]:
        print("  {:<10} {}".format(name, description))
    print("")
    print("Run `parallax <tool> --help` for details.")


def build_parser():
    p = argparse.ArgumentParser(prog="parallax", description="ParallaxAI terminal client for AntiGem")
    sub = p.add_subparsers(dest="command", required=True)

    c = sub.add_parser("setup", help="save panel URL and API key")
    c.add_argument("--base", help="https://ai.parallaxserver.online")
    c.add_argument("--key", help="client API key")
    c.add_argument("--reset", action="store_true", help="remove saved config")
    c.set_defaults(func=cmd_setup)

    c = sub.add_parser("chat", help="send one chat and wait for the reply")
    c.add_argument("prompt")
    c.add_argument("--no-wait", action="store_true")
    c.add_argument("--timeout", type=int, default=900)
    c.add_argument("--json", action="store_true")
    c.set_defaults(func=cmd_chat)

    c = sub.add_parser("batch", help="send many chats in one batch")
    c.add_argument("prompts", nargs="*")
    c.add_argument("--file", help="text file with one prompt per line")
    c.add_argument("--no-wait", action="store_true")
    c.add_argument("--timeout", type=int, default=1800)
    c.add_argument("--json", action="store_true")
    c.set_defaults(func=cmd_batch)

    c = sub.add_parser("status", help="show a job or batch")
    c.add_argument("target")
    c.add_argument("--batch", action="store_true", help="treat target as a batch id")
    c.add_argument("--json", action="store_true")
    c.set_defaults(func=cmd_status)

    c = sub.add_parser("jobs", help="list recent jobs")
    c.add_argument("--limit", type=int, default=15)
    c.add_argument("--json", action="store_true")
    c.set_defaults(func=cmd_jobs)

    c = sub.add_parser("artifact", help="download a job report ZIP")
    c.add_argument("job_id")
    c.add_argument("output", nargs="?")
    c.set_defaults(func=cmd_artifact)

    c = sub.add_parser("skill", help="install agent skill for the terminal tools")
    c.add_argument("--all", action="store_true", help="rewrite even if already installed")
    c.set_defaults(func=cmd_skill)

    c = sub.add_parser("tools", help="list available terminal tools")
    c.set_defaults(func=cmd_tools)
    return p


def main():
    args = build_parser().parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
PYEOF
