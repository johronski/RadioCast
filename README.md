# RadioCast

Android-app som castar webbradio direkt till Chromecast Audio (och andra Chromecasts).

- Cast-knapp uppe till höger: välj högtalare, byt högtalare eller sluta casta. Ikonen visar när appen är ansluten.
- Hittar högtalare och högtalargrupper automatiskt på wifi, eller via IP-adress om sökningen inte fungerar.
- Tryck på en kanal medan du castar så byts den direkt. Appen återansluter till senaste högtalaren när den startar.
- Kanaler: Radio Nova, Nouvo Nova, Mouv', FIP och France Inter. Lägg till egna med en strömlänk.
- Radion fortsätter spela på högtalaren även när appen är stängd.

## Hämta appen

Varje gång koden ändras bygger GitHub en ny APK. Gå till **Releases** i det här repot,
öppna den senaste och ladda ner **RadioCast.apk** på telefonen.

Första gången frågar Android om du vill tillåta installation från webbläsaren. Svara ja.
Play Protect kan varna för en okänd app; välj att installera ändå.

## Om högtalaren inte hittas

Tryck på cast-knappen och välj **Ange IP-adress…** längst ner i listan. IP-adressen finns i Google Home-appen:
tryck på högtalaren, kugghjulet, Enhetsinformation.

## Teknik

Appen använder inga Google Play-tjänster. Den pratar Cast-protokollet direkt med högtalaren
(TLS på port 8009) och startar Googles standardmottagare för media, som spelar strömmen.
Sökningen sker med Androids inbyggda mDNS (`_googlecast._tcp`).

Byggs med `gradle assembleDebug` (Gradle 8.9, Java 17). Signeringsnyckeln i `app/` är
avsiktligt incheckad så att nya versioner kan installeras ovanpå gamla. Den är bara till
för privat bruk.
