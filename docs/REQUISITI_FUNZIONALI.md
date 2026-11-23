# Requisiti Funzionali — App Coro Parrocchiale

## 1. Obiettivo e Contesto d'Uso
Applicazione Android nativa con interfaccia touch ad alto contrasto e tasti grandi, per programmare ed eseguire le basi musicali (MIDI e MP3) durante la Santa Messa, sostituendo la postazione PC con vanBasco.

- **Utenti:** 1–2 operatori del coro (la stessa persona programma la messa e lancia le basi).
- **Uscita audio:** cavo jack / adattatore USB-C collegato direttamente all'impianto audio della chiesa.
- **Distribuzione:** installazione diretta (APK / Android Studio), nessun login, nessun Play Store, funzionamento **100% offline**.
- **Dispositivi:** sviluppo e prove su **Motorola G85**; dispositivo finale previsto un **tablet** (layout adattivo).

## 2. Parti della Messa (Rito Romano — Messale Italiano)
Catalogo completo delle 18 fasi liturgiche (configurabili messa per messa) più fasi personalizzate:
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
- **Libreria da cartella (SAF):** selezione di una cartella sul dispositivo con indicizzazione automatica ricorsiva di `.mid`, `.midi`, `.kar`, `.mp3`, `.wav`, `.m4a`, `.ogg`, ricerca rapida e filtri. Include brani di prova integrati.
- **Impostazioni per brano salvate come default in libreria** (e sovrascrivibili per la singola messa):
  - Durata sfumatura (`0,5–15 s`)
  - Punto di attacco (`mm:ss`, con anteprima audio e cattura al volo)
  - Cambio tonalità (`-12..+12` semitoni; nei MIDI preserva il canale 10 percussioni)
  - Volume relativo brano (`20%–150%`)
- **Modalità Programmazione:** una messa alla volta, attivazione/disattivazione fasi, più brani per fase, salvataggio automatico.
- **Modalità Esecuzione:** tasti grandi ad alto contrasto, schermo sempre acceso, avvio manuale dei brani successivi (nessun loop né concatenazione), monitor volume sistema.
- **Protezione tocchi accidentali:** pressione prolungata (default `450 ms`, regolabile `300–800 ms`) con barra di riempimento e vibrazione su Avvia, Sfuma, Stop immediato e Uscita; blocco mutuo degli altri brani durante la riproduzione.
- **Motore audio MIDI + MP3:** sintetizzatore SoundFont `.sf2` integrato con banco suoni General MIDI pronto all'uso + possibilità di caricare un `.sf2` personalizzato; sfumatura fluida e micro-rampa anti-click sullo stop; servizio Foreground durante la riproduzione.
- **Predisposizioni future:** schema DB già pronto per Template di Messa (`isTemplate`, `templateCategory`) e interfaccia `MixerController` agganciata agli eventi di esecuzione per futura integrazione con RØDECaster Pro II.
