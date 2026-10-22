D=$(date -u +%Y-%m-%d)
for u in "https://api.sr.se/api/v2/playlists/getplaylistbychannelid?id=164&startdatetime=${D}T00:00:00&enddatetime=${D}T23:59:00&format=json&size=5" \
         "https://api.sr.se/api/v2/playlists/getplaylistbychannelid?id=164&format=json&size=5"; do
  echo "== $u"; curl -s --compressed "$u" | head -c 2500; echo; done
echo "== RF levels"; curl -s https://api.radiofrance.fr/livemeta/pull/7 | python3 -c "
import json,sys,time; d=json.load(sys.stdin); now=time.time()
for k,s in sorted(d['steps'].items(), key=lambda x:x[1]['start']):
  print(s['embedType'], s['depth'], round((s['start']-now)/60), round((s['end']-now)/60), s.get('authors'), '-', s['title'])"
