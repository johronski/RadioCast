# RadioCast

Android-app som castar webbradio direkt till Chromecast Audio (och andra Chromecasts).

- Hittar högtalare automatiskt på wifi, eller via IP-adress om sökningen inte fungerar.
- Kanaler: Radio Nova, Nouvo Nova, Mouv', FIP och France Inter. Lägg till egna med en strömlänk.
- Radion fortsätter spela på högtalaren även när appen är stängd.

## Hämta appen

Varje gång koden ändras bygger GitHub en ny APK. Gå till **Releases** i det här repot,
öppna den senaste och ladda ner **RadioCast.apk** på telefonen.

Första gången frågar Android om du vill tillåta installation från webbläsaren. Svara ja.
Play Protect kan varna för en okänd app; välj att installera ändå.

## Om högtalaren inte hittas

Tryck **Hittas inte? Ange IP-adress**. IP-adressen finns i Google Home-appen:
tryck på högtalaren, kugghjulet, Enhetsinformation.

## Teknik

Appen använder inga Google Play-tjänster. Den pratar Cast-protokollet direkt med högtalaren
(TLS på port 8009) och startar Googles standardmottagare för media, som spelar strömmen.
Sökningen sker med Androids inbyggda mDNS (`_googlecast._tcp`).

Byggs med `gradle assembleDebug` (Gradle 8.9, Java 17). Signeringsnyckeln i `app/` är
avsiktligt incheckad så att nya versioner kan installeras ovanpå gamla. Den är bara till
för privat bruk.
