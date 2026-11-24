# Requisiti Funzionali — App Coro Parrocchiale

## 1. Obiettivo e Contesto d'Uso
Applicazione Android nativa con interfaccia touch ad alto contrasto e tasti grandi, per programmare ed eseguire le basi musicali (MIDI e MP3) durante la Santa Messa, sostituendo la postazione PC con vanBasco.

- **Utenti:** 1–2 operatori del coro (la stessa persona programma la messa e lancia le basi).
- **Uscita audio:** cavo jack / adattatore USB-C collegato direttamente all'impianto audio della chiesa.
- **Distribuzione:** installazione diretta (APK / Android Studio), nessun login, nessun Play Store, funzionamento **100% offline**.
- **Dispositivi:** layout adattivo edge-to-edge per smartphone (anche con caratteri di sistema grandi), tablet e desktop con scorciatoie da tastiera.

## 2. Parti della Messa (Rito Romano — Messale Italiano)
Catalogo completo delle 18 fasi liturgiche (configurabili messa per messa) più fasi personalizzate e **13 preset liturgici** (*Feriale*, *Domenicale*, *Solenne*, *Avvento/Quaresima*, *Natale*, *Palme*, *Pentecoste/Corpus Domini*, *Giovedì Santo*, *Venerdì Santo*, *Veglia Pasquale*, *Pasqua*, *Matrimonio*, *Esequie/Funerale*) oltre ai **preset personalizzati salvabili**:
1. Canto d'Ingresso *(default attiva)*
2. Aspersione
3. Atto Penitenziale / Signore pietà (Kyrie)
4. Gloria *(default attiva)*
5. Salmo Responsoriale *(default attiva)*
6. Sequenza
7. Acclamazione al Vangelo (Alleluia / Lode e onore) *(default attiva)*
8. Credo
9. Preghiera dei Fedeli
10. Offertorio (Presentazione dei doni) *(default attiva)*
11. Santo (Sanctus) *(default attiva)*
12. Acclamazione all'Anamnesi (Mistero della fede)
13. Amen solenne (Dossologia)
14. Padre Nostro
15. Agnello di Dio (Agnus Dei)
16. Comunione *(default attiva, tipicamente multi-brano)*
17. Dopo la Comunione / Ringraziamento
18. Canto Finale / Congedo *(default attiva)*

## 3. Requisiti Principali
- **Libreria da cartella (SAF):** selezione di una cartella sul dispositivo con indicizzazione automatica ricorsiva di `.mid`, `.midi`, `.kar`, `.mp3`, `.wav`, `.m4a`, `.ogg`, `.flac`, ricerca rapida e filtri. Include brani di prova integrati.
- **Impostazioni per brano salvate come default in libreria** (e sovrascrivibili per la singola messa):
  - Durata sfumatura (`0,5–15 s`)
  - Punto di attacco (`mm:ss`, con anteprima audio e cattura al volo)
  - Cambio tonalità (`-12..+12` semitoni; nei MIDI preserva il canale 10 percussioni)
  - Volume relativo brano (`20%–150%`)
- **Modalità Programmazione:** una messa attiva alla volta, preset liturgici e preset utente salvabili (con fasi e brani), attivazione/disattivazione fasi, più brani per fase, salvataggio automatico.
- **Modalità Esecuzione:** tasti grandi ad alto contrasto, schermo sempre acceso, avvio manuale dei brani successivi (nessun loop né concatenazione), monitor volume sistema.
- **Protezione tocchi accidentali:** pressione prolungata (default `450 ms`, regolabile `300–800 ms`) con barra di riempimento e vibrazione su Avvia, Sfuma, Stop immediato e Uscita; blocco mutuo degli altri brani durante la riproduzione.
- **Motore audio MIDI + Audio:** sintetizzatore nativo **FluidSynth v2.6.2** (256 voci, riverbero/chorus, 48 kHz nativo, cache RAM, limiter $C^1$) con banco suoni General MIDI `TimGM6mb.sf2` pronto all'uso + possibilità di importare banchi `.sf2` / `.sf3` personalizzati (con link diretti a banchi gratuiti); ExoPlayer per MP3/WAV/FLAC; sfumatura fluida, micro-rampa anti-click sullo stop e servizio Foreground durante la riproduzione.
- **Predisposizioni future:** interfaccia `MixerController` agganciata agli eventi di esecuzione e campi `mixerConfigJson` su fasi e brani per futura integrazione con RØDECaster Pro II.
