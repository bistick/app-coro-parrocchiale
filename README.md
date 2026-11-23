# Coro — App Android per il Coro Parrocchiale

Applicazione Android touch (Kotlin + Jetpack Compose + Material 3) progettata per programmare ed eseguire le basi musicali (**MIDI**, **KAR** e **MP3/WAV**) durante la Santa Messa, sostituendo la postazione PC con vanBasco.

## Funzionalità principali
- **Programmazione Messa (una alla volta):** catalogo completo delle 18 fasi del Rito Romano (Messale Italiano) attivabili/disattivabili con un tocco + fasi personalizzate; ogni fase supporta più brani avviati manualmente (es. Comunione).
- **Libreria da cartella sul dispositivo:** selezione tramite Android Storage Access Framework (cartella copiata da PC via USB/Drive, incluse sottocartelle), ricerca rapida e filtri. Include 9 brani di prova pronti all'uso.
- **Impostazioni per singolo brano salvate come default in libreria:**
  - Punto di attacco (`mm:ss`) con anteprima audio e cattura al volo (*"Imposta attacco qui"*)
  - Durata sfumatura (`0,5–15 s`)
  - Cambio tonalità (`-12..+12` semitoni; sui MIDI preserva intatto il canale 10 delle percussioni)
  - Volume relativo brano (`20%–150%`)
- **Console Esecuzione Live (Smartphone e Tablet):**
  - Tasti grandi ad alto contrasto su tema scuro, schermo sempre acceso, indicatore volume multimediale di sistema.
  - **Protezione tocchi accidentali:** pressione prolungata (default `450 ms`, regolabile `300–800 ms` con area test) per Avvio, Sfuma, Stop immediato e Uscita; blocco mutuo degli altri brani durante la riproduzione.
- **Motore Audio integrato 100% offline:**
  - MIDI (`.mid`, `.midi`, `.kar`): sintetizzatore nativo **TinySoundFont** (`tsf.h` + `tml.h`) con banco suoni General MIDI `TimGM6mb.sf2` già integrato + possibilità di caricare un `.sf2` personalizzato dalle Impostazioni.
  - MP3 / Audio (`.mp3`, `.wav`, `.m4a`, `.ogg`, `.flac`): **AndroidX Media3 ExoPlayer**.
  - Micro-rampa anti-click sullo stop immediato e sfumatura fluida per l'impianto audio della chiesa.
- **Predisposizioni future:** database già predisposto per Template di Messa (`isTemplate`, `templateCategory`) e interfaccia `MixerController` per futura integrazione con RØDECaster Pro II.

## Aprire, compilare e debuggare in Android Studio (Motorola G85)
1. Apri questa cartella (`coro`) in **Android Studio** (*File → Open*).
2. Verifica in *Settings → Languages & Frameworks → Android SDK → SDK Tools* che sia installato **NDK (Side by side)** (necessario per il sintetizzatore MIDI nativo `coro_midi`).
3. Collega il **Motorola G85** via USB con **Debug USB** attivo nelle *Opzioni sviluppatore*.
4. Premi **Run (`Shift+F10`)** o **Debug (`Shift+F9`)**.

## Compilazione da terminale
```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```
L'APK di debug viene generato in `app\build\outputs\apk\debug\app-debug.apk`.

## Documentazione
- Specifica dei requisiti funzionali: [docs/REQUISITI_FUNZIONALI.md](docs/REQUISITI_FUNZIONALI.md)
