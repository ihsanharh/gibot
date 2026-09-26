# GiBot

A headless Minecraft Bedrock bot designed specifically for fetching store item token costs and automating gifting on The Hive.

## Commands

GiBot supports 3 core actions:

```bash
# 1. Fetch available tokens & all store items (with token costs & image URLs)
./gibot fetch

# 2. Fetch token cost and image URL for a specific item
./gibot fetch [item]

# 3. Gift an item to a player
./gibot gift [username] [item]
```

### Options

| Flag | Description |
| :--- | :--- |
| `-p`, `--proxy <url>` | Route connections through HTTP or SOCKS5 proxy (supports UDP relay on SOCKS5) |
| `-j`, `--json` | Output results in machine-readable JSON format (ideal for external processes) |
| `-v`, `--verbose` | Output full connection and debug logs |

---

## Output Formats

### 1. Fetch Item (`fetch [item]`)

**Plaintext:**
```text
Item: Axe Head
Cost: 1 Tokens
Image: https://cdn.playhive.com/avatars/hat-axe.png
```

**JSON (`-j` / `--json`):**
```json
{
  "name": "Axe Head",
  "category": "Hats",
  "cost": 1,
  "image": "https://cdn.playhive.com/avatars/hat-axe.png"
}
```

---

### 2. Fetch Catalog (`fetch`)

**Plaintext:**
```text
Available Tokens: 12

[Hats]
- Axe Head: 1 Tokens (https://cdn.playhive.com/avatars/hat-axe.png)
- Cardboard Box: 2 Tokens (https://cdn.playhive.com/avatars/hat-cardboard-box.png)
...
```

**JSON (`-j` / `--json`):**
```json
{
  "tokens": 12,
  "items": [
    {
      "name": "Axe Head",
      "category": "Hats",
      "cost": 1,
      "image": "https://cdn.playhive.com/avatars/hat-axe.png"
    },
    ...
  ]
}
```

---

### 3. Gifting (`gift [username] [item]`)

- **Success:**
  - Plaintext: `Success: You've gifted Cardboard Box to zwush!`
  - JSON: `{"status":"success","recipient":"zwush","item":"Cardboard Box","message":"..."}`
  - Exit Code: `0`
- **Failure:**
  - Plaintext: `Failed: Sorry, we can't find a player named xyz.`
  - JSON: `{"status":"error","message":"Sorry, we can't find a player named xyz."}`
  - Exit Code: `1`

---

## Authentication

Authentication is handled independently using Microsoft's OAuth Device Code flow:
- On first run without credentials, GiBot prints an authorization link and code:
  ```text
  Microsoft Account Login Required!
  1. Visit URL: https://www.microsoft.com/link?otc=XXXX-XXXX
  2. Or go to https://www.microsoft.com/link and enter code: XXXX-XXXX
  ```
- Once approved, credentials are saved to `auth.json`.
- Subsequent runs refresh credentials automatically without user intervention.

---

## Building

GiBot requires Java 25. Clone the repository with its submodules:
```bash
git clone --recurse-submodules https://github.com/ihsanharh/gibot.git
cd gibot
```

If you already cloned without submodules, initialize them with:
```bash
git submodule update --init --recursive
```

Build the standalone executable and fat JAR:
```bash
./gradlew executable
```
This automatically produces:
- `./gibot`: Self-contained executable CLI binary (runs directly with all JVM warnings suppressed).
- `build/libs/gibot-all.jar`: Standalone fat JAR (can be run with `java -jar ...`).

---