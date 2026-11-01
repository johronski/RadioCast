# Probe 1 nov: får 5 slumpade kanaler låtinfo? Dagens mönster, Icecast-statussida (som webbappen), ICY i strömmen.
import urllib.request, json, re
UA = {"User-Agent": "RadioCast-probe", "Origin": "https://johronski.github.io"}
def req(u, h=None, t=10):
    return urllib.request.urlopen(urllib.request.Request(u, headers={**UA, **(h or {})}), timeout=t)
src = json.load(open("meta-sources.json"))["sources"]
def known(url):
    for p in src:
        m = re.search(p["match"], url or "")
        if not m: continue
        k = m.group(1) if m.groups() else ""
        if "map" in p and k not in p["map"]: continue
        return p["id"]
st = json.loads(req("https://de1.api.radio-browser.info/json/stations/search?order=random&limit=5&hidebroken=true&is_https=true").read())
for s in st:
    u = s["url_resolved"] or s["url"]
    print("\n==", s["name"].strip(), "|", s["countrycode"], "|", s["codec"], s["bitrate"], "|", u)
    print("  mönster:", known(s["url"]) or known(u) or "ingen")
    try:
        o = re.match(r"(https?://[^/]+)", u).group(1); path = urllib.parse.urlparse(u).path
        r = req(o + "/status-json.xsl", t=6); cors = r.headers.get("Access-Control-Allow-Origin")
        j = json.loads(r.read()); srcs = j.get("icestats", {}).get("source") or []
        if not isinstance(srcs, list): srcs = [srcs]
        m = next((x for x in srcs if (x.get("listenurl") or "").endswith(path)), srcs[0] if len(srcs) == 1 else None)
        print("  icecast-status: CORS", cors, "| titel:", (m or {}).get("artist", ""), "–", (m or {}).get("title") if m else "(ingen matchande ström)")
    except Exception as e: print("  icecast-status: nej (", str(e)[:80], ")")
    try:
        r = req(u, {"Icy-MetaData": "1"}, t=10); mi = r.headers.get("icy-metaint")
        print("  server:", r.headers.get("Server"), "| CORS ström:", r.headers.get("Access-Control-Allow-Origin"), "| icy-metaint:", mi)
        if mi:
            mi = int(mi); r.read(mi); n = r.read(1)[0] * 16; meta = r.read(n).decode("utf-8", "replace")
            print("  ICY:", re.search(r"StreamTitle='(.*?)';", meta).group(1) if "StreamTitle" in meta else "(tomt block, prova senare)")
        r.close()
    except Exception as e: print("  ström: fel", str(e)[:100])
