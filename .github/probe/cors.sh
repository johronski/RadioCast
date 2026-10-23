for u in "https://de1.api.radio-browser.info/json/stations/search?name=nova&limit=1" \
 "https://api.sr.se/api/v2/playlists/rightnow?channelid=164&format=json" \
 "https://api.sr.se/api/v2/playlists/getplaylistbychannelid?id=164&format=json&size=2" \
 "https://api.radiofrance.fr/livemeta/pull/7" \
 "https://www.nova.fr/radios-data/www.nova.fr/all.json" \
 "https://itunes.apple.com/search?term=abba&entity=song&limit=1" \
 "https://novazz.ice.infomaniak.ch/novazz-128.mp3" \
 "https://icecast.radiofrance.fr/fip-midfi.mp3" \
 "https://live1.sr.se/p3-aac-320"; do
  h=$(curl -s -L -m 8 -o /dev/null -D - -H "Origin: https://johronski.github.io" -r 0-100 "$u" | grep -i "access-control-allow-origin" | tail -1 | tr -d '\r')
  echo "$u  =>  ${h:-INGEN CORS}"
done
