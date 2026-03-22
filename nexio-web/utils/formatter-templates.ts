export type StreamTemplateDefinition = {
  id: string
  label: string
  source: 'builtin' | 'custom'
  nameTemplate: string
  descriptionTemplate: string
}

export const builtInTemplates: StreamTemplateDefinition[] = [
  {
    id: 'torrentio',
    label: 'Torrentio',
    source: 'builtin',
    nameTemplate: '{stream.proxied::istrue["🕵️‍♂️ "||""]}{stream.private::istrue["🔑 "||""]}{stream.type::=p2p["[P2P] "||""]}{service.id::exists["[{service.shortName}"||""]}{service.cached::istrue["]+ "||""]}{service.cached::isfalse[" download] "||""]}{addon.name} {stream.resolution::exists["{stream.resolution}"||"Unknown"]}\n{stream.visualTags::exists["{stream.visualTags::join(\' | \')}"||""]}',
    descriptionTemplate: '{stream.message::exists["ℹ️{stream.message}"||""]}\n{stream.folderName::exists["{stream.folderName}"||""]}\n{stream.filename::exists["{stream.filename}"||""]}\n{stream.size::>0["💾{stream.size::bytes2} "||""]}{stream.folderSize::>0["/ 💾{stream.folderSize::bytes2}"||""]}{stream.seeders::>=0["👤{stream.seeders} "||""]}{stream.age::exists["📅{stream.age} "||""]}{stream.indexer::exists["⚙️{stream.indexer}"||""]}\n{stream.languageEmojis::exists["{stream.languageEmojis::join(\' / \')}"||""]}{stream.subtitles::exists::and::stream.languageEmojis::exists[" "||""]}{stream.subtitles::exists["Subs / {stream.subtitleEmojis::join(\' / \')}"||""]}'
  },
  {
    id: 'torbox',
    label: 'TorBox',
    source: 'builtin',
    nameTemplate: '{stream.proxied::istrue["🕵️‍♂️ "||""]}{stream.private::istrue["🔑 "||""]}{stream.type::=p2p["[P2P] "||""]}{addon.name}{stream.library::istrue[" (Your Media) "||""]}{service.cached::istrue[" (Instant "||""]}{service.cached::isfalse[" ("||""]}{service.id::exists["{service.shortName})"||""]}{stream.resolution::exists[" ({stream.resolution})"||""]}',
    descriptionTemplate: 'Quality: {stream.quality::exists["{stream.quality}"||"Unknown"]}\nName: {stream.filename::exists["{stream.filename}"||"Unknown"]}\nSize: {stream.size::>0["{stream.size::bytes} "||""]}{stream.folderSize::>0["/ {stream.folderSize::bytes} "||""]}{stream.indexer::exists["| Source: {stream.indexer} "||""]}{stream.duration::>0["| Duration: {stream.duration::time} "||""]}\nLanguages: {stream.languages::exists["{stream.languages::join(\', \')}"||""]}{stream.subtitles::exists::and::stream.languages::exists[" | "||""]}{stream.subtitles::exists["Subtitles: {stream.subtitles::join(\', \')}"||""]}\n{stream.message::exists["Message: {stream.message}"||""]}'
  },
  {
    id: 'gdrive',
    label: 'GDrive',
    source: 'builtin',
    nameTemplate: '{stream.proxied::istrue["🕵️ "||""]}{stream.private::istrue["🔑 "||""]}{stream.type::=p2p["[P2P] "||""]}{service.shortName::exists["[{service.shortName}"||""]}{service.cached::istrue["⚡] "||""]}{service.cached::isfalse["⏳] "||""]}{addon.name}{stream.library::istrue[" (Your Media)"||""]} {stream.resolution::exists["{stream.resolution}"||""]}{stream.seadexBest::istrue[" (Best)"||""]}{stream.seadex::istrue::and::stream.seadexBest::isfalse[" (SeaDex Alt.)"||""]}',
    descriptionTemplate: '{stream.quality::exists["🎥 {stream.quality} "||""]}{stream.encode::exists["🎞️ {stream.encode} "||""]}{stream.releaseGroup::exists["🏷️ {stream.releaseGroup} "||""]}{stream.network::exists["📡 {stream.network} "||""]}\n{stream.visualTags::exists["📺 {stream.visualTags::join(\' | \')} "||""]}{stream.audioTags::exists["🎧 {stream.audioTags::join(\' | \')} "||""]}{stream.audioChannels::exists["🔊 {stream.audioChannels::join(\' | \')}"||""]}\n{stream.size::>0["📦 {stream.size::sbytes} "||""]}{stream.folderSize::>0["/ {stream.folderSize::sbytes} "||""]}{stream.bitrate::>0["({stream.bitrate::sbitrate})"||""]}{stream.duration::>0["⏱️ {stream.duration::time} "||""]}{stream.seeders::>0["👥 {stream.seeders} "||""]}{stream.age::exists["📅 {stream.age} "||""]}{stream.indexer::exists["🔍 {stream.indexer}"||""]}\n{stream.languages::exists["🌎 {stream.languages::join(\' | \')}"||""]}{stream.subtitles::exists["📝 {stream.subtitles::join(\' | \')}"||""]}\n{stream.filename::exists["📁"||""]} {stream.folderName::exists["{stream.folderName}/"||""]}{stream.filename::exists["{stream.filename}"||""]}\n{stream.message::exists["ℹ️ {stream.message}"||""]}'
  },
  {
    id: 'lightgdrive',
    label: 'Light GDrive',
    source: 'builtin',
    nameTemplate: '{stream.proxied::istrue["🕵️ "||""]}{stream.private::istrue["🔑 "||""]}{stream.type::=p2p["[P2P] "||""]}{service.shortName::exists["[{service.shortName}"||""]}{stream.library::istrue["☁️"||""]}{service.cached::istrue["⚡] "||""]}{service.cached::isfalse["⏳] "||""]}{addon.name}{stream.resolution::exists[" {stream.resolution}"||""]}{stream.seadexBest::istrue[" (Best)"||""]}{stream.seadex::istrue::and::stream.seadexBest::isfalse[" (SeaDex Alt.)"||""]}',
    descriptionTemplate: '{stream.title::exists["📁 {stream.title::title}"||""]}{stream.year::exists[" ({stream.year})"||""]}{stream.seasonEpisode::exists[" {stream.seasonEpisode::join(\' • \')}"||""]}\n{stream.quality::exists["🎥 {stream.quality} "||""]}{stream.encode::exists["🎞️ {stream.encode} "||""]}{stream.releaseGroup::exists["🏷️ {stream.releaseGroup}"||""]}{stream.network::exists["📡 {stream.network} "||""]}\n{stream.visualTags::exists["📺 {stream.visualTags::join(\' • \')} "||""]}{stream.audioTags::exists["🎧 {stream.audioTags::join(\' • \')} "||""]}{stream.audioChannels::exists["🔊 {stream.audioChannels::join(\' • \')}"||""]}\n{stream.size::>0["📦 {stream.size::sbytes} "||""]}{stream.folderSize::>0["/ {stream.folderSize::sbytes} "||""]}{stream.duration::>0["⏱️ {stream.duration::time} "||""]}{stream.age::exists["📅 {stream.age} "||""]}{stream.indexer::exists["🔍 {stream.indexer}"||""]}\n{stream.languageEmojis::exists["🌐 {stream.languageEmojis::join(\' / \')}"||""]}{stream.subtitles::exists["📝 {stream.subtitleEmojis::join(\' / \')}"||""]}\n{stream.message::exists["ℹ️ {stream.message}"||""]}'
  },
  {
    id: 'universal',
    label: 'Universal',
    source: 'builtin',
    nameTemplate: `{stream.resolution::exists["{stream.resolution::replace('2160p','[[icon:4k]]')::replace('1440p','[[icon:2k]]')::replace('1080p','[[icon:fullhd]]')::replace('720p','[[icon:hd]]')::replace('576p','[[icon:sd]]')::replace('480p','[[icon:sd]]')}"||""]}{stream.resolution::exists::and::stream.title::exists[" • "||""]}{stream.title::exists["{stream.title::title::truncate(30)}"||"?"]}{stream.year::exists[" ({stream.year})"||""]}{stream.seasonEpisode::exists[" ({stream.seasonEpisode::join(' ')::replace('S','Season ')::replace('E','Episode ')})"||""]}{stream.seadexBest::istrue[" 🏆"||""]}{stream.seadex::istrue::and::stream.seadexBest::isfalse[" 🥈"||""]}{stream.message::~Download["{tools.removeLine}"||""]}`,
    descriptionTemplate: `🎥 {stream.quality::exists["{stream.quality::title::replace('Bluray Remux','Lossless BD')::replace('Bluray','Blu-ray')::replace('Web-Dl','Streaming')::replace('Web-dl','Streaming')::replace('Webrip','Web Rip')::replace('Hdrip','HD Rip')::replace('Dvdrip','DVD Rip')::replace('Hdtv','HDTV')::replace('Cam','CAM')::replace('Ts','Telesync')::replace('Tc','Telecine')::replace('Scr','Screener')}"||""]}  • {stream.audioTags::exists["{stream.audioTags::join('  ')::replace('Atmos','[[icon:atmos]]')::replace('TrueHD','[[icon:truehd]]')::replace('DTS-HD MA','[[icon:dtshd]]')::replace('DTS:X','[[icon:dtsx]]')::replace('DD+','[[icon:ddp]]')::replace('DD','[[icon:dd]]')::replace('EAC3','[[icon:ddp]]')::replace('AC3','[[icon:dd]]')::replace('DTS','[[icon:dts]]')}"||"[[icon:stereo]]"]}{stream.audioChannels::exists[" {stream.audioChannels::join('/')}"||""]} • ⏱️ {stream.duration::>0["{stream.duration::time}"||"Unknown"]}
💾 {stream.size::>0["{stream.size::bytes}"||"Unknown"]} • ☁️ {service.name::exists["{service.name}"||"Unknown"]} • {addon.name}
{stream.uLanguages::exists["🗣️ {stream.uLanguageEmojis::join(' ')} • "||""]}{stream.seasonPack::istrue["📦 Pack • "||""]}{stream.filename::~NF["[[icon:netflix]] Netflix"||""]}{stream.filename::~DSNP["[[icon:disneyplus]] Disney+"||""]}{stream.filename::~HMAX["[[icon:hbo]] HBO Max"||""]}{stream.filename::~.MAX.["[[icon:max]] Max"||""]}{stream.filename::~AMZN["[[icon:prime]] Amazon"||""]}{stream.filename::~APTV["[[icon:appletv]] Apple TV+"||""]}{stream.filename::~PMTP["[[icon:paramount]] Paramount+"||""]}{stream.filename::~PCOK["[[icon:peacock]] Peacock"||""]}{stream.filename::~CRTC["[[icon:crunchyroll]] Crunchyroll"||""]}{stream.filename::~CR.["[[icon:crunchyroll]] Crunchyroll"||""]}{stream.filename::~NF::or::stream.filename::~DSNP::or::stream.filename::~HMAX::or::stream.filename::~.MAX.::or::stream.filename::~AMZN::or::stream.filename::~APTV::or::stream.filename::~PMTP::or::stream.filename::~PCOK::or::stream.filename::~CRTC::or::stream.filename::~CR.::and::stream.releaseGroup::exists[" • "||""]}{stream.releaseGroup::exists["👤 {stream.releaseGroup::truncate(10)}"||""]}{stream.seadexBest::istrue[" • 🏆 BEST"||""]}{stream.seadex::istrue::and::stream.seadexBest::isfalse[" • 🥈 ALT"||""]}{stream.repack::istrue[" • 🔄 Repack"||""]}
📄 {stream.filename::exists["{stream.filename}"||"—"]}`
  },
  {
    id: 'prism',
    label: 'Prism',
    source: 'builtin',
    nameTemplate: '{stream.resolution::exists["{stream.resolution::replace(\'2160p\', \'🔥4K UHD\')::replace(\'1440p\',\'✨ QHD\')::replace(\'1080p\',\'🚀 FHD\')::replace(\'720p\',\'💿 HD\')::replace(\'576p\',\'💩 Low Quality\')::replace(\'480p\',\'💩 Low Quality\')::replace(\'360p\',\'💩 Low Quality\')::replace(\'240p\',\'💩 Low Quality\')::replace(\'144p\',\'💩 Low Quality\')}"||"💩 Unknown"]}',
    descriptionTemplate: '{stream.title::exists["🎬 {stream.title::title} "||""]}{stream.year::exists["({stream.year}) "||""]}{stream.formattedSeasons::exists["🍂 {stream.formattedSeasons} "||""]}{stream.formattedEpisodes::exists["🎞️ {stream.formattedEpisodes}"||""]}'
  },
  {
    id: 'minimalisticgdrive',
    label: 'Minimalistic GDrive',
    source: 'builtin',
    nameTemplate: '{stream.resolution::exists["{stream.resolution::replace(\'2160p\',\'✨ 4K\')::replace(\'1440p\',\'📀 2K\')::replace(\'1080p\',\'🧿1080p\')::replace(\'720p\',\'💿720p\')}"||"N/A"]}{service.cached::istrue[" 🎫 "||""]}{service.cached::isfalse[" 🎟️ "||""]}',
    descriptionTemplate: '{stream.visualTags::exists["🔆 {stream.visualTags::join(\' • \')}  "||""]}{stream.audioTags::exists["🔊 {stream.audioTags::join(\' • \')}"||""]}'
  },
  {
    id: 'tamtaro',
    label: 'Tamtaro',
    source: 'builtin',
    nameTemplate: '{stream.resolution::exists["{stream.resolution::replace(\'2160p\',\'   4K \')::replace(\'1440p\',\'    2K \')::replace(\'p\',\'P\')}"||"     "]}{stream.type::exists["{stream.type::replace(\'debrid\',\'    \')::replace(\'p2p\',\'⁽ᵖ²ᵖ⁾\')::replace(\'live\',\'⁽ˡᶦᵛᵉ⁾\')::replace(\'http\',\'⁽ʷᵉᵇ⁾\')::replace(\'usenet\',\'⁽ⁿᶻᵇ⁾\')::replace(\'stremio-usenet\',\'⁽ⁿᶻᵇ⁾\')::replace(\'info\',\'⁽ᶦⁿᶠᵒ⁾\')::replace(\'statistic\',\'⁽ˢᵗᵃᵗˢ⁾\')::replace(\'external\',\'⁽ᵉˣᵗ⁾\')::replace(\'error\',\'⁽ᵉʳʳᵒʳ⁾\')::replace(\'youtube\',\'⁽ʸᵗ⁾\')}"||""]}{service.cached::istrue["⚡"||""]}{service.cached::isfalse["⏳"||""]}{stream.quality::exists["\\n  〈{stream.quality::title::replace(\'Bluray Remux\',\'Remux\')::replace(\'Web-dl\',\'Web-dl\')::replace(\'Hc Hd-rip\',\'HC HDRip\')::replace(\'Hdrip\',\'HDRip\')}〉"||""]}{stream.message::~Download["{tools.removeLine}\\n"||""]}{stream.nSeScore::exists["\\n  {stream.nSeScore::star::replace(\'⯪\',\'☆\')}"||""]}{stream.message::~Download["{tools.removeLine}\\n"||""]}',
    descriptionTemplate: '{stream.title::exists::and::stream.library::isfalse["✎  {stream.title::title::truncate(15)}"||""]}{stream.title::exists::and::stream.library::istrue["☁︎  {stream.title::title::truncate(15)} "||""]}{stream.year::exists::and::stream.episodes::exists::isfalse::and::stream.seasons::exists::isfalse[" ({stream.year})"||""]}{stream.seasonEpisode::exists["  {stream.seasonEpisode::join(\'·\')::replace(\'E\',\'ᴇ\')::replace(\'S\',\'s\')}"||""]}\n{stream.audioTags::exists["♬  {stream.audioTags::lsort::join(\' · \')}  "||""]}{stream.audioChannels::exists["♯  {stream.audioChannels::join(\' · \')} "||""]}\n{stream.size::>0["◈  {stream.size::sbytes}"||""]}{stream.message::~Download["{tools.removeLine}"||""]}'
  }
]
