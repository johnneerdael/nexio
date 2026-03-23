import { defaultSettings } from '~/utils/portal-defaults'
import type { CatalogId, PortalSettings } from '~/types/portal'

const settings: PortalSettings = defaultSettings()

const schemaVersion: number = settings.schemaVersion
const enabledTraktCatalogs: CatalogId[] = settings.catalogs.trakt.catalogEnabledSet
const traktCatalogOrder: CatalogId[] = settings.catalogs.trakt.catalogOrder
const mdbListCatalogOrder: string[] = settings.catalogs.mdblist.catalogOrder
const hiddenPersonalListKeys: string[] = settings.catalogs.mdblist.hiddenPersonalListKeys
const heroCatalogKeys: string[] = settings.catalogs.home.heroCatalogKeys

void schemaVersion
void enabledTraktCatalogs
void traktCatalogOrder
void mdbListCatalogOrder
void hiddenPersonalListKeys
void heroCatalogKeys

// @ts-expect-error Contract v2 moves synced home catalog settings out of layout.
settings.layout

// @ts-expect-error Contract v2 moves synced Trakt catalog settings under catalogs.trakt.
settings.trakt

// @ts-expect-error Contract v2 moves MDBList catalog selection state under catalogs.mdblist.
settings.integrations.mdblist.hiddenPersonalListKeys
