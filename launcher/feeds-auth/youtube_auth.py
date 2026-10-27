import json, os, sys, time, urllib.parse, urllib.request, urllib.error

client_id, client_secret, out = sys.argv[1], sys.argv[2], sys.argv[3]
def post(url, form):
    try:
        return json.load(urllib.request.urlopen(urllib.request.Request(url, urllib.parse.urlencode(form).encode())))
    except urllib.error.HTTPError as e:
        return json.load(e)
dev = post("https://oauth2.googleapis.com/device/code",
           {"client_id": client_id, "scope": "https://www.googleapis.com/auth/youtube.readonly"})
if "device_code" not in dev:
    print("FAILED:", dev, flush=True); sys.exit(1)
print("GO TO:", dev["verification_url"], "AND ENTER CODE:", dev["user_code"], flush=True)
deadline = time.time() + dev.get("expires_in", 1800)
interval = dev.get("interval", 5)
while time.time() < deadline:
    time.sleep(interval)
    tok = post("https://oauth2.googleapis.com/token", {"client_id": client_id, "client_secret": client_secret,
               "device_code": dev["device_code"], "grant_type": "urn:ietf:params:oauth:grant-type:device_code"})
    if "refresh_token" in tok:
        fd = os.open(out, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w") as f:
            json.dump({"client_id": client_id, "client_secret": client_secret, "refresh_token": tok["refresh_token"]}, f)
        print("DONE: refresh token saved", flush=True); sys.exit(0)
    err = tok.get("error")
    if err == "slow_down": interval += 5
    elif err != "authorization_pending":
        print("FAILED:", tok, flush=True); sys.exit(1)
print("FAILED: timed out", flush=True); sys.exit(1)
