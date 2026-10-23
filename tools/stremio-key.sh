#!/bin/bash
# Signs in to Stremio once and saves the sign-in key in config.properties as stremio_auth_key, so the home
# screen's Continue Watching can show your Stremio progress. The password is only sent to Stremio, never saved.
CONFIG="$(cd "$(dirname "$0")/.." && pwd)/config.properties"
[ -f "$CONFIG" ] || { echo "Can't find $CONFIG"; exit 1; }
read -r -p "Stremio email: " STREMIO_EMAIL
read -r -s -p "Stremio password: " STREMIO_PASSWORD; echo
export STREMIO_EMAIL STREMIO_PASSWORD CONFIG
python3 - <<'EOF'
import json, os, re, urllib.request
body = json.dumps({"type": "Login", "email": os.environ["STREMIO_EMAIL"], "password": os.environ["STREMIO_PASSWORD"],
                   "facebook": False}).encode()
req = urllib.request.Request("https://api.strem.io/api/login", data=body, headers={"Content-Type": "application/json"})
try:
    reply = json.load(urllib.request.urlopen(req, timeout=20))
except Exception as e:
    raise SystemExit(f"Couldn't reach Stremio: {e}")
key = (reply.get("result") or {}).get("authKey")
if not key:
    msg = (reply.get("error") or {}).get("message", "unknown error")
    raise SystemExit(f"Stremio said: {msg}. Check the email and password (accounts made with Google or Facebook "
                     "need a password set first on stremio.com).")
path = os.environ["CONFIG"]
text = open(path).read()
line = "stremio_auth_key=" + key
text = re.sub(r"(?m)^stremio_auth_key=.*$", line, text) if re.search(r"(?m)^stremio_auth_key=", text) \
    else text.rstrip("\n") + "\n" + line + "\n"
open(path, "w").write(text)
print("Done: the Stremio key is saved in config.properties. Tell Claude \"done\".")
EOF
