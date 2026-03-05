<template>
  <section class="glass" style="padding:1.5rem; border-radius: var(--radius-xl); display:grid; gap:1.2rem;">
    <div style="display:flex; justify-content:space-between; align-items:flex-end; gap:1rem; flex-wrap:wrap;">
      <div>
        <p class="badge">Catalog Inventory</p>
        <h2 class="section-title" style="font-size:1.9rem; margin:0.7rem 0 0.4rem;">Account-visible catalogs.</h2>
        <p style="margin:0; color: var(--text-soft); max-width: 50rem; line-height:1.6;">
          This list is built from addon manifests plus the active Trakt and MDBList catalog configuration.
        </p>
      </div>
      <span class="badge"><strong>{{ catalogs.length }}</strong> catalogs</span>
    </div>

    <article v-if="catalogs.length === 0" class="field-shell">
      <label>No catalogs detected</label>
      <p>Built-in Cinemeta catalogs appear here after manifest inspection succeeds. Trakt and MDBList catalogs appear when their integrations are connected and configured.</p>
    </article>

    <div v-else class="grid-2">
      <article v-for="catalog in catalogs" :key="catalog.key" class="field-shell">
        <div style="display:flex; justify-content:space-between; gap:1rem; align-items:flex-start;">
          <div>
            <label>{{ catalog.catalogName }}</label>
            <p>{{ catalog.addonName }} · {{ catalog.type }}</p>
            <p style="word-break: break-word;">{{ catalog.key }}</p>
          </div>
          <span class="badge">{{ catalog.source }}</span>
        </div>
      </article>
    </div>
  </section>
</template>

<script setup lang="ts">
import type { AddonCatalogRecord } from '~/types/portal'

defineProps<{
  catalogs: AddonCatalogRecord[]
}>()
</script>
