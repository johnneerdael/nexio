# Custom Formatter: The Ultimate Guide

The Nexio Custom Formatter is a powerful templating engine that gives you 100% control over how your streams appear. This guide covers every variable, modifier, and logical operator available in the system.

## 🏗️ Core Syntax

A template consists of static text and **Variable Blocks**.
- **Simple Variable**: `{stream.filename}`
- **With Modifier**: `{stream.size::sbytes}`
- **Chained Modifiers**: `{stream.filename::lower::truncate(20)}`
- **Conditional**: `{stream.seeders::>0["👤 {stream.seeders}"||""]}`

---

## 💎 Variables Reference

Variables are grouped into five main categories. You can access any property of these objects.

### 📦 `stream` (The Media File)
This is the most used category. It contains data parsed from the filename and metadata.

| Variable | Type | Description |
| :--- | :--- | :--- |
| `{stream.filename}` | String | The original filename of the stream. |
| `{stream.folderName}` | String | The name of the parent folder (if applicable). |
| `{stream.size}` | Number | File size in bytes. |
| `{stream.bitrate}` | Number | Total bitrate in bps. |
| `{stream.resolution}` | String | E.g., `2160p`, `1080p`, `720p`. |
| `{stream.quality}` | String | E.g., `BluRay REMUX`, `WEB-DL`, `HDTV`. |
| `{stream.encode}` | String | E.g., `HEVC`, `AVC`, `AV1`, `XviD`. |
| `{stream.visualTags}` | Array | List of tags like `DV`, `HDR10+`, `IMAX`, `10bit`. |
| `{stream.audioTags}` | Array | List of tags like `Atmos`, `DTS:X`, `TrueHD`, `FLAC`. |
| `{stream.audioChannels}` | Array | E.g., `7.1`, `5.1`, `2.0`. |
| `{stream.languages}` | Array | List of audio languages (e.g., `English`, `French`). |
| `{stream.subtitles}` | Array | List of subtitle languages. |
| `{stream.seeders}` | Number | Current seeder count (for P2P streams). |
| `{stream.age}` | Number | Age of the stream in hours. |
| `{stream.indexer}` | String | The source indexer (e.g., `Torrentio`, `Jackett`). |
| `{stream.type}` | String | `p2p`, `usenet`, `http`, `live`, `youtube`. |
| `{stream.proxied}` | Boolean | Whether the stream is routed through a proxy. |
| `{stream.private}` | Boolean | Whether it's from a private tracker. |
| `{stream.library}` | Boolean | Whether the file is already in your debrid library. |
| `{stream.infoHash}` | String | The unique hash for the torrent (if applicable). |
| `{stream.message}` | String | Provider-specific status message. |
| `{stream.edition}` | String | E.g., `Director's Cut`, `Extended`. |
| `{stream.regraded}` | Boolean | Whether the video has been HDR regraded. |
| `{stream.repack}` | Boolean | Whether the stream is a repack/proper. |

### 🛠️ `service` (The Debrid/Provider)
| Variable | Type | Description |
| :--- | :--- | :--- |
| `{service.name}` | String | Full name (e.g., `Real-Debrid`). |
| `{service.shortName}` | String | Short name (e.g., `RD`, `PM`, `AD`). |
| `{service.cached}` | Boolean | `true` if it's an instant stream (⚡). |
| `{service.premium}` | Boolean | Whether the account is in premium status. |

### 🧩 `addon` (The Source)
| Variable | Type | Description |
| :--- | :--- | :--- |
| `{addon.name}` | String | Name of the addon (e.g., `Torrentio`). |

### 🎬 `metadata` (The Content)
| Variable | Type | Description |
| :--- | :--- | :--- |
| `{metadata.title}` | String | E.g., `The Matrix`. |
| `{metadata.year}` | Number | E.g., `1999`. |
| `{metadata.genres}` | Array | List of genres. |
| `{metadata.runtime}` | Number | Total runtime in minutes. |
| `{metadata.queryType}` | String | `movie`, `series`, or `anime`. |

### 👤 User-Preference Variants
These variants return data filtered or sorted based on your account settings (e.g., `Preferred Languages`).

| Variable | Description |
| :--- | :--- |
| `{stream.uLanguages}` | Your preferred audio languages present in this stream. |
| `{stream.uSubtitles}` | Your preferred subtitle languages present in this stream. |
| `{stream.uLanguageEmojis}` | Emojis for your preferred languages (e.g., 🇬🇧). |
| `{stream.uLanguageCodes}` | ISO codes for your preferred languages (e.g., EN). |
| `{stream.wedontknowwhatakilometeris}` | Replaces `🇬🇧` with `🇺🇸🦅` for all flags. |
| `{stream.uWedontknowwhatakilometeris}` | Same as above, but for your preferred languages only. |

### 📊 Technical & Ranking Scores
| Variable | Description |
| :--- | :--- |
| `{stream.regexScore}` | Raw score from your ranked regex patterns. |
| `{stream.nRegexScore}` | Normalized regex score (0-100). |
| `{stream.seScore}` | Raw score from Stream Expressions. |
| `{stream.nSeScore}` | Normalized Stream Expression score (0-100). |
| `{stream.seadex}` | Boolean: Is this a curated SeaDex release? |
| `{stream.seadexBest}` | Boolean: Is this the "Best" release on SeaDex? |

### 🛠️ `tools` (Formatting Utilities)
| Variable | Result |
| :--- | :--- |
| `{tools.newLine}` | Forces a new line in the output. |
| `{tools.removeLine}` | If a condition fails, this removes the entire line to prevent empty gaps. |

---

## ✨ Modifiers

Modifiers transform values. They can be chained: `{stream.size::sbytes::upper}`.

### 🔠 Text Modifiers
| Modifier | Example Result |
| :--- | :--- |
| `::upper` | `HELLO WORLD` |
| `::lower` | `hello world` |
| `::title` | `Hello World` |
| `::trim` | Removes leading/trailing spaces. |
| `::truncate(N)` | `Shortened tex...` |
| `::replace('old', 'new')` | Replaces text. |
| `::remove('text')` | Deletes specific text. |
| `::reverse` | Reverses text or array. |
| `::base64` | Encodes string to Base64. |
| `::smallcaps` | `sᴍᴀʟʟ ᴄᴀᴘs ᴛᴇxᴛ` |
| `::star` | Converts `0-100` to `★★★★½` |
| `::pstar` | Same as `::star`, but pads to 5 stars (`★★★★½` ➔ `★★★★½☆`). |

### 🔢 Numeric & Unit Modifiers
| Modifier | Result |
| :--- | :--- |
| `::bytes` | E.g., `12.45 GiB` (Base 1024) |
| `::bytes10` | E.g., `12.45 GB` (Base 1000) |
| `::sbytes` | Concise bytes (e.g., `12.4GB`, `850MB`) |
| `::rbytes` | Rounds to whole bytes. |
| `::bitrate` | E.g., `15.2 Mbps` |
| `::sbitrate` | Concise bitrate formatting. |
| `::rbitrate` | Rounds to whole Mbps. |
| `::time` | Formats ms to `1h 20m 15s`. |
| `::age` | Formats hours to `23h` or `12d`. |
| `::comma` | Formats `1000` to `1,000`. |
| `::hex` | Number ➔ Hexadecimal. |
| `::octal` | Number ➔ Octal. |
| `::binary` | Number ➔ Binary. |

### ⛓️ Array Modifiers
| Modifier | Result |
| :--- | :--- |
| `::join(' sep ')` | Flattens array: `Tag1 | Tag2` |
| `::first` | Gets the first element. |
| `::last` | Gets the last element. |
| `::get(N)` | Gets element at index N. |
| `::slice(S, E)`| Gets elements from index S to E. |
| `::random` | Gets a random element. |
| `::length` | Returns the number of items. |
| `::sort` | Smart numeric/alpha sort. |
| `::rsort` | Reverse smart sort. |
| `::lsort` | Strict alphabetical sort. |

### 🌍 Localization Modifiers
| Modifier | Result |
| :--- | :--- |
| `::flag` | Language name ➔ Emoji Flag (e.g., `English` ➔ `🇬🇧`). |
| `::langcode` | Language name ➔ ISO 639-1 (e.g., `French` ➔ `FR`). |
| `::lang` | ISO code ➔ Full Name (e.g., `es` ➔ `Spanish`). |

---

## 🧠 Logic & Conditionals

Conditions allow for dynamic UI logic. 

### Syntax
`{variable::operator::operand["Value if True"||"Value if False"]}`

### Operators
- `istrue` / `isfalse`: For boolean values.
- `exists`: Returns true if value is NOT null/empty.
- `>`, `<`, `>=`, `<=`, `=`, `!=`: Numeric or string comparison.
- `~`: Contains (e.g., `{stream.filename::~"Remux"}`).
- `$`: Starts with.
- `^`: Ends with.

### Logical Chaining (Comparators)
You can chain multiple variables and conditions using these comparators:
- `::and::`: Both sides must be true.
- `::or::`: Either side can be true.
- `::xor::`: Exactly one side must be true.
- `::neq::`: Values must be different.
- `::equal::`: Values must be equal.
- `::left::`: Always returns the left value.
- `::right::`: Always returns the right value.

**Example**: `{stream.seeders::>10::and::service.cached::istrue["🔥 POPULAR"||""]}`

---

## 🖼️ Icon Tokens

Nexio supports high-quality inline icons to make your stream list feel premium. Use the `[[icon:id]]` syntax.

### 🌐 Network & Service Icons
| Token | Icon Description |
| :--- | :--- |
| `[[icon:netflix]]` | Netflix Logo |
| `[[icon:disneyplus]]` | Disney+ Logo |
| `[[icon:hbo]]` / `[[icon:max]]` | HBO / Max Logo |
| `[[icon:prime]]` | Prime Video Logo |
| `[[icon:appletv]]` | Apple TV+ Logo |
| `[[icon:paramount]]` | Paramount+ Logo |
| `[[icon:peacock]]` | Peacock Logo |
| `[[icon:crunchyroll]]` | Crunchyroll Logo |

### 📺 Resolution & Quality Icons
| Token | Icon Description |
| :--- | :--- |
| `[[icon:4k]]` | 4K Badge |
| `[[icon:2k]]` | 1440p Badge |
| `[[icon:fullhd]]` | 1080p Badge |
| `[[icon:hd]]` | 720p Badge |
| `[[icon:sd]]` | standard Definition Badge |

---

## 🏗️ Complex Real-World Examples

### 1. The Dynamic Network Badge
Show the network icon only if it matches a known streaming service.
```text
{stream.network::exists["[[icon:{stream.network::lower}]] "||""]}
```

### 2. Premium Resolution Headers
A clean, visual resolution indicator followed by the filename.
```text
{stream.resolution::="2160p"["[[icon:4k]]"||"{stream.resolution::="1080p"["[[icon:fullhd]]"||"[[icon:hd]]"]}" ]} {stream.filename::truncate(30)}
```

### 3. Smart Audio/Video Tags (With Logic)
Show visual tags joined by a dot, but only if they exist. Uses `::and::` to show a separator only when both exist.
```text
{stream.visualTags::exists["📺 {stream.visualTags::join(' • ')} "||""]}{stream.visualTags::exists::and::stream.audioTags::exists[" | "||""]}{stream.audioTags::exists["🎧 {stream.audioTags::join(' • ')}"||""]}
```

### 4. Debrid & Library Status Icons
A concise indicator for cached (⚡), downloading (⏳), or library (☁︎) status.
```text
{stream.library::istrue["☁︎"||""]}{service.cached::istrue["⚡"||""]}{service.cached::isfalse["⏳"||""]}
```

### 5. Multi-Language Flags (User Preferred)
Show flags for ONLY your preferred languages found in the stream.
```text
{stream.uLanguages::exists["🌐 {stream.uLanguageEmojis::join(' ')}"||""]}
```

### 6. SeaDex Best Release Highlight
Highlight anime releases that are marked as "Best" on SeaDex.
```text
{stream.seadexBest::istrue["🏆 BEST RELEASE :: "||""]}{stream.filename}
```

---

## 🚀 Live Formatter Workspace

Don't guess—test! In the Nexio Web App, go to **Settings > Custom Formatter**. You can:
1.  **Select a Preset**: Start with Prism or Tamtaro and modify them.
2.  **Toggle JSON Context**: See exactly what data is available for your current stream.
3.  **Real-Time Preview**: Any change you make in the editor is immediately reflected in the preview window on the right.
4.  **Simulation Modes**: Switch between "Remux", "Web-DL", and "Low Quality" presets to see how your formatter handles different content.
