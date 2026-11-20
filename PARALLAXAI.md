# ParallaxAI inside AntiGem (ParallaxAGM)

## Two ways to use it

1. **As an agent** — Settings → Agents (or the agent picker on the empty chat screen) now lists
   **ParallaxAI** next to Antigravity, Claude Code, OpenCode and Codex. Pick it and chat normally:
   each turn is sent to your ParallaxAI server API with the recent conversation as context, so
   chats continue across turns. The job runs on GitHub Actions with the panel's tools (analysis,
   patching, etc.) and the reply appears in the chat when it finishes. API key / panel URL and
   context length can be changed in the ParallaxAI agent settings (`parallax_prefs`).
2. **From the terminal** — the `parallax` command described below.

## Terminal client

The ParallaxAI terminal client is bundled into the APK as `assets/bin/parallax`. After the
app unpacks its Linux environment, the command is available in the in-app terminal at
`$PREFIX/bin/parallax`. Everything is driven from the terminal: jobs go to the live
ParallaxAI panel and the GitHub Actions worker runs the tools (analysis, patching, etc.).

## First run (optional)

Sensible defaults are baked in, so it works immediately:

| Setting | Default |
| --- | --- |
| Panel | `https://ai.parallaxserver.online` |
| API key | bundled client key |

To use your own key/panel, or after rotating the key:

```sh
parallax setup --key YOUR_API_KEY --base https://ai.parallaxserver.online
parallax setup --reset      # back to the bundled defaults
```

Environment variables `PANEL_BASE_URL` and `PANEL_API_KEY` override the saved config.

## Commands

```sh
parallax tools                                   # list every tool
parallax chat "Explain this DEX file"            # one chat, waits for the answer
parallax chat "question" --no-wait               # just get a job id
parallax batch "Q1" "Q2" "Q3"                    # 1-20 chats in ONE command, parallel
parallax batch --file prompts.txt                # one prompt per line (# comments ok)
parallax status <job_or_batch_id>                # progress + answers
parallax jobs --limit 10                         # recent jobs
parallax artifact <job_id> report.zip            # download the report ZIP
parallax skill                                   # teach in-app agents to use the tool
```

`parallax skill` writes a `SKILL.md` into `~/.agents/skills/parallaxai/` and
`~/.claude/skills/parallaxai/` so agents discovered by Settings → Skills & Plugins know
they can call `parallax` from the terminal.

## Files changed from upstream

- `scripts/parallax.sh` — self-contained bash + Python stdlib client (source of truth).
- `app/build.gradle.kts` — `copyScriptsToAssets` bundles it as `bin/parallax`.
- `app/src/main/java/ai/antigem/data/local/LocalServerManager.kt` — unpacks it into the
  terminal `bin/` on first run and after updates.
- `version.json` — version bump and changelog entry.
- `.github/workflows/build-apk.yml` — builds/publishes the signed release APK.
