import base64, hashlib, json, os, secrets, sys, urllib.parse, urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer

client_id, out = sys.argv[1], sys.argv[2]
redirect = "http://127.0.0.1:8888/callback"
verifier = secrets.token_urlsafe(64)
challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
state = secrets.token_urlsafe(16)
url = "https://accounts.spotify.com/authorize?" + urllib.parse.urlencode({
    "client_id": client_id, "response_type": "code", "redirect_uri": redirect, "state": state,
    "code_challenge_method": "S256", "code_challenge": challenge,
    "scope": "user-read-recently-played playlist-read-private user-library-read"})
result = {}

class H(BaseHTTPRequestHandler):
    def do_GET(self):
        q = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
        if urllib.parse.urlparse(self.path).path != "/callback":
            self.send_response(404); self.end_headers(); return
        ok = q.get("state", [""])[0] == state and "code" in q
        result["code" if ok else "error"] = q.get("code", [q.get("error", ["bad state"])[0]])[0]
        self.send_response(200); self.send_header("Content-Type", "text/plain; charset=utf-8"); self.end_headers()
        self.wfile.write(b"Spotify linked. You can close this tab." if ok else b"Something went wrong; see the terminal.")
    def log_message(self, *a): pass

srv = HTTPServer(("127.0.0.1", 8888), H)
srv.timeout = 600
print("OPEN THIS URL:", url, flush=True)
srv.handle_request()
if "code" not in result:
    print("FAILED:", result.get("error", "timed out"), flush=True); sys.exit(1)
data = urllib.parse.urlencode({"grant_type": "authorization_code", "code": result["code"], "redirect_uri": redirect,
                               "client_id": client_id, "code_verifier": verifier}).encode()
tok = json.load(urllib.request.urlopen(urllib.request.Request("https://accounts.spotify.com/api/token", data)))
fd = os.open(out, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
with os.fdopen(fd, "w") as f:
    json.dump({"client_id": client_id, "refresh_token": tok["refresh_token"]}, f)
print("DONE: refresh token saved", flush=True)
