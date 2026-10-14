# Lifematic

Geteilte Litematica-Baupläne für den Server.

* **plugin/** – Paper-Plugin (Server 26.3). Speichert Baupläne, verteilt sie, fragt Spieler beim Betreten eines Bauplatzes, rechnet den Fortschritt.
* **mod/** – Fabric-Mod (Client 26.3). Taste **B** teilt die gewählte Litematica-Platzierung, empfangene Baupläne erscheinen direkt in Litematica. Litematica, MaLiLib und Fabric API sind eingebettet.

Jeder Push baut beide Dateien über GitHub Actions. Die fertigen JARs liegen im Release **latest**.
