# NuvioTV - Rapport d'audit et traduction française

**Date** : 2026-03-29
**Fichier source** : `app/src/main/res/values/strings.xml` (EN)
**Fichier traduit** : `app/src/main/res/values-fr/strings.xml` (FR)

---

## Résumé chiffré

| Métrique | Avant | Après |
|---|---|---|
| Clés EN | 1058 | 1058 |
| Clés FR | 1006 | 1058 |
| Couverture | 95.1% | **100%** |
| Strings manquantes | 52 | **0** |
| Strings orphelines | 0 | 0 |
| Erreurs de placeholders | 0 | 0 |
| Vouvoiement | ~100+ occurrences | **0** |
| Incohérences glossaire | ~10 | **0** |

---

## Strings ajoutées (52)

### Genres (29 strings)
| Clé | EN | FR |
|---|---|---|
| `genre_action` | Action | Action |
| `genre_adventure` | Adventure | Aventure |
| `genre_animation` | Animation | Animation |
| `genre_comedy` | Comedy | Comédie |
| `genre_crime` | Crime | Policier |
| `genre_documentary` | Documentary | Documentaire |
| `genre_drama` | Drama | Drame |
| `genre_family` | Family | Famille |
| `genre_fantasy` | Fantasy | Fantastique |
| `genre_history` | History | Histoire |
| `genre_horror` | Horror | Horreur |
| `genre_kids` | Kids | Enfants |
| `genre_music` | Music | Musique |
| `genre_mystery` | Mystery | Mystère |
| `genre_news` | News | Actualités |
| `genre_reality` | Reality | Téléréalité |
| `genre_romance` | Romance | Romance |
| `genre_science_fiction` | Science Fiction | Science-fiction |
| `genre_sci_fi_fantasy` | Sci-Fi & Fantasy | Sci-Fi & Fantastique |
| `genre_soap` | Soap | Soap |
| `genre_talk` | Talk | Talk-show |
| `genre_thriller` | Thriller | Thriller |
| `genre_tv_movie` | TV Movie | Téléfilm |
| `genre_war` | War | Guerre |
| `genre_war_politics` | War & Politics | Guerre & Politique |
| `genre_western` | Western | Western |
| `genre_action_adventure` | Action & Adventure | Action & Aventure |

### Stream Info (18 strings)
| Clé | EN | FR |
|---|---|---|
| `stream_info_section_source` | SOURCE | SOURCE |
| `stream_info_section_file` | FILE | FICHIER |
| `stream_info_section_video` | VIDEO | VIDÉO |
| `stream_info_section_audio` | AUDIO | AUDIO |
| `stream_info_section_subtitle` | SUBTITLE | SOUS-TITRE |
| `stream_info_filename` | Filename | Nom du fichier |
| `stream_info_size` | Size | Taille |
| `stream_info_codec` | Codec | Codec |
| `stream_info_resolution` | Resolution | Résolution |
| `stream_info_frame_rate` | Frame Rate | Fréquence d'images |
| `stream_info_bitrate` | Bitrate | Bitrate |
| `stream_info_channels` | Channels | Canaux |
| `stream_info_sample_rate` | Sample Rate | Fréquence d'échantillonnage |
| `stream_info_language` | Language | Langue |
| `stream_info_name` | Name | Nom |
| `stream_info_source` | Source | Source |
| `stream_info_subtitle_source_addon` | Addon | Addon |
| `stream_info_subtitle_source_embedded` | Embedded | Intégré |

### Bibliothèque / Autres (5 strings)
| Clé | EN | FR |
|---|---|---|
| `library_filter_genre` | Genre | Genre |
| `library_filter_year` | Year | Année |
| `library_source_nuvio` | NUVIO | NUVIO |
| `library_source_trakt` | TRAKT | TRAKT |
| `library_syncing_library` | Syncing library... | Synchronisation de la bibliothèque... |
| `detail_more_like_this_powered_by_tmdb` | Powered by TMDB | Propulsé par TMDB |
| `detail_more_like_this_powered_by_trakt` | Powered by Trakt | Propulsé par Trakt |

---

## Corrections de cohérence avec NuvioMobile

| Clé | Avant (FR) | Après (FR) | Raison |
|---|---|---|---|
| `continue_watching` | Visionnage en cours | Continuer à regarder | Glossaire NuvioMobile |
| `detail_tab_cast` | Créateur et casting | Créateur et distribution | Cast = Distribution |
| `pause_cast_label` | Casting | Distribution | Cast = Distribution |
| `skip_intro` | Passer le générique | Passer l'intro | Skip Intro = Passer l'intro |
| `watchlist_added` | Ajouté à la liste de suivi | Ajouté à ma liste | Watchlist = Ma liste |
| `watchlist_removed` | Retiré de la liste de suivi | Retiré de ma liste | Watchlist = Ma liste |
| `display_mode_resolution` | Resolution | Résolution | Non traduit |
| `display_mode_refresh` | Fréquence d'image | Fréquence de rafraîchissement | Terme plus précis |

Toutes les références à "visionnage en cours" dans d'autres strings ont également été harmonisées vers "Continuer à regarder" (tmdb_enrich_continue_watching_*, trakt_continue_watching_*, layout_blur_cw_next_up*, trakt_watch_progress_*).

---

## Corrections de tutoiement

Plus de **100 occurrences** de vouvoiement corrigées. Exemples principaux :

| Type de correction | Exemple avant | Exemple après |
|---|---|---|
| Vous avez | Vous avez été déconnecté | Tu as été déconnecté |
| Veuillez | Veuillez vous reconnecter | Reconnecte-toi |
| Votre / Vos | Vérifiez votre connexion | Vérifie ta connexion |
| Impératif -ez | Sélectionnez un profil | Sélectionne un profil |
| Impératif -issez | Saisissez le code PIN | Saisis le code PIN |
| Impératif -ez (er) | Réessayez | Réessaie |
| Souhaitez | vous souhaitez | tu souhaites |

Toutes les sections touchées : HomeScreen, ErrorState, ContinueWatching, Hero, MetaDetails, AppLanguage, Font, Theme, PlaybackAudio, MDBList, AnimeSkip, Trakt, Profile, Addon, Plugin, Auth, Sync, Search, Library, Account, Update, Supporters.

---

## Correction de traduction inexacte

| Clé | Avant (FR - inexact) | Après (FR - corrigé) |
|---|---|---|
| `account_sign_in_description` | "La synchronisation de la bibliothèque et de la progression ne fonctionne que lorsque Trakt n'est pas connecté" | "La synchronisation de la bibliothèque utilise Nuvio Sync quand Trakt n'est pas connecté, et la progression peut utiliser Trakt ou Nuvio Sync" |
| `profile_selection_hint` | "Utilisez le pavé directionnel pour choisir un profil" | "Maintiens enfoncé pour gérer le profil" |
| `auth_qr_scan_instruction` | Correction d'un double espace ("le  code QR") | "Scanne le code QR" |

---

## Termes laissés volontairement en anglais (68 strings)

Conformément au glossaire NuvioMobile, les termes techniques suivants restent en anglais :

**Noms propres / Marques** : Nuvio, Trakt, TMDB, IMDb, Letterboxd, Rotten Tomatoes, Metacritic, YouTube, Anime Skip, Nuvio Sync, Ko-fi, GitHub

**Termes techniques** : Addons, Plugins, Audio, Codec, Bitrate, Source, PIN, Style, Collections, Productions, Torrent, Spoiler, Overlay Canvas

**Labels UI inchangeables** : NUVIO, TRAKT, LOCAL, AUDIO, SOURCE, Normal, Standard, Compact, Dense, Wi-Fi, Ethernet, dB

---

## Points d'attention pour révision humaine

1. ~~**"Lire" vs "Regarder"**~~ -- **CORRIGÉ** dans le post-audit ci-dessous.

2. **"Continuer à regarder"** : Terme plus long que "Visionnage en cours" pour un header TV. Vérifier l'affichage sur l'interface TV réelle.

3. **Genres** : Certains genres gardent l'anglais (Soap, Thriller, Western, Romance) car ce sont des termes universellement compris en français. NuvioMobile charge les genres dynamiquement depuis l'API TMDB (pas de liste hardcodée), donc pas de risque d'incohérence.

4. **"Distribution" pour Cast** : Terme français correct mais moins courant que "Casting" dans le langage courant. Choix dicté par le glossaire NuvioMobile.

5. **Tutoiement dans les messages techniques** : Les messages d'erreur détaillés (error_state_*) utilisent maintenant le tutoiement, ce qui pourrait paraître familier dans un contexte technique. C'est cohérent avec le choix global.

---

## Corrections post-audit

**Date** : 2026-03-29

### 1. "Lire" → "Regarder" sur les boutons d'action Play

Conformément au glossaire NuvioMobile (`Watch Now → Regarder`), tous les boutons d'action principaux ont été corrigés. Les descriptions de réglages automatiques (autoplay, trailers en arrière-plan) conservent "Lire" car elles décrivent un comportement technique du lecteur, pas une action utilisateur.

| Clé | Avant | Après |
|---|---|---|
| `hero_play` | Lire | Regarder |
| `hero_play_episode` | Lire S%1$d, E%2$d | Regarder S%1$d, E%2$d |
| `hero_play_trailer` | Lire la bande-annonce | Regarder la bande-annonce |
| `episodes_play` | Lire | Regarder |
| `play_manually` | Lire manuellement | Regarder manuellement |
| `detail_btn_play` | Lire | Regarder |
| `detail_btn_play_episode` | Lire S%1$dE%2$d | Regarder S%1$dE%2$d |
| `next_episode_play` | Lire | Regarder |

**Strings conservant "Lire" (contexte réglages/autoplay, pas des boutons d'action) :**
- `audio_autoplay_trailers_sub` -- "Lire automatiquement les bandes-annonces..."
- `autoplay_reuse_last_link_sub` -- "Lire automatiquement ton dernier flux..."
- `autoplay_mode_first_desc` -- "Lire automatiquement la première source..."
- `autoplay_mode_regex_desc` -- "Lire la première source dont le texte..."
- `layout_autoplay_trailer_sub` -- "Lire l'aperçu de la bande-annonce..."
- `layout_autoplay_trailer_expanded_sub` -- "Lire la bande-annonce dans l'arrière-plan..."
- `layout_trailer_muted` -- "Lire la bande-annonce en sourdine"

### 2. Genres -- Aucune correction nécessaire

NuvioMobile charge les noms de genres dynamiquement depuis l'API TMDB (via `tmdbService.getMovieGenres()` / `tmdbService.getTvGenres()`). Il n'existe pas de liste hardcodée de genres traduits dans NuvioMobile. Les genres ajoutés dans NuvioTV suivent les traductions standard TMDB en français, garantissant la cohérence.
