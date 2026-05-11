package com.nexio.tv.ui.screens.home

// Plan B Task 6a — all helpers migrated:
//   overlayFromMap + overlayFromMapEnriched  →  HomeArtworkOverlayKeys.kt (as top-level extensions)
//   homeOverlayItemKey()                     →  inlined as homeDisplayItemKey(apiType, id) at each call site
//   displayHashForHomeOverlay()              →  inlined as toHomeDisplayMetadata().hydratedHomeDisplayHash() at each call site
//
// This file is intentionally left empty pending deletion in Task 6b.
