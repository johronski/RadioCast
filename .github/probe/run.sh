set -e
mkdir -p /tmp/p/out && cd /tmp/p
curl -sSL -o json.jar https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar
cd $GITHUB_WORKSPACE
S=app/src/main/java/se/radiocast
javac -encoding UTF-8 -cp /tmp/p/json.jar -d /tmp/p/out $(find .github/probe/stubs -name '*.java') $S/SongWatcher.java $S/SongText.java $S/Station.java $S/StationGroups.java $S/SearchQuery.java
java -cp /tmp/p/out:/tmp/p/json.jar se.radiocast.SongProbe
