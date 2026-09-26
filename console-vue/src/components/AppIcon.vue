<template>
  <svg
    xmlns="http://www.w3.org/2000/svg"
    :width="size"
    :height="size"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="1.8"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    <!-- search -->
    <template v-if="name === 'search'">
      <circle cx="11" cy="11" r="7" />
      <path d="m20 20-3.5-3.5" />
    </template>

    <!-- swap (two-way arrows) -->
    <template v-else-if="name === 'swap'">
      <path d="M7 4 4 7l3 3" />
      <path d="M4 7h16" />
      <path d="m17 20 3-3-3-3" />
      <path d="M20 17H4" />
    </template>

    <!-- arrow right (long) -->
    <template v-else-if="name === 'arrow-right'">
      <path d="M4 12h16" />
      <path d="m14 6 6 6-6 6" />
    </template>

    <!-- calendar -->
    <template v-else-if="name === 'calendar'">
      <rect x="3.5" y="5" width="17" height="15.5" rx="3" />
      <path d="M3.5 10h17" />
      <path d="M8 2.5V7M16 2.5V7" />
    </template>

    <!-- user -->
    <template v-else-if="name === 'user'">
      <circle cx="12" cy="8" r="4" />
      <path d="M4.5 20.5c1.3-3.2 4.1-5 7.5-5s6.2 1.8 7.5 5" />
    </template>

    <!-- users -->
    <template v-else-if="name === 'users'">
      <circle cx="9" cy="8.5" r="3.5" />
      <path d="M2.8 19.5c1.1-2.8 3.4-4.3 6.2-4.3s5.1 1.5 6.2 4.3" />
      <path d="M16 5.4a3.5 3.5 0 0 1 0 6.2" />
      <path d="M17.8 15.5c1.7.6 2.9 1.9 3.5 3.9" />
    </template>

    <!-- ticket -->
    <template v-else-if="name === 'ticket'">
      <path d="M4 8.5V7a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v1.5a2.5 2.5 0 0 0 0 7V17a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-1.5a2.5 2.5 0 0 0 0-7Z" />
      <path d="M14 5v2.5M14 11v2M14 16.5V19" stroke-dasharray="0.1 3.4" />
    </template>

    <!-- order / list -->
    <template v-else-if="name === 'list'">
      <path d="M8.5 6.5H20M8.5 12H20M8.5 17.5H20" />
      <path d="M4.5 6.5h.01M4.5 12h.01M4.5 17.5h.01" stroke-width="2.4" />
    </template>

    <!-- profile / id card -->
    <template v-else-if="name === 'idcard'">
      <rect x="3" y="5" width="18" height="14.5" rx="3" />
      <circle cx="8.6" cy="11" r="2" />
      <path d="M5.6 16.2c.7-1.5 1.7-2.2 3-2.2s2.3.7 3 2.2" />
      <path d="M14.5 9.5H18M14.5 13h3.5" />
    </template>

    <!-- train -->
    <template v-else-if="name === 'train'">
      <rect x="5" y="3.5" width="14" height="13.5" rx="3.5" />
      <path d="M5 10.5h14" />
      <path d="M9 3.5v7M15 3.5v7" stroke-width="1.4" />
      <circle cx="9" cy="14" r=".2" />
      <circle cx="15" cy="14" r=".2" />
      <path d="m7.5 20.5 1.8-3.5M16.5 20.5l-1.8-3.5" />
    </template>

    <!-- clock -->
    <template v-else-if="name === 'clock'">
      <circle cx="12" cy="12" r="8.5" />
      <path d="M12 7.5V12l3 2" />
    </template>

    <!-- plus -->
    <template v-else-if="name === 'plus'">
      <path d="M12 5v14M5 12h14" />
    </template>

    <!-- trash -->
    <template v-else-if="name === 'trash'">
      <path d="M4 7h16" />
      <path d="M9.5 7V5.5A1.5 1.5 0 0 1 11 4h2a1.5 1.5 0 0 1 1.5 1.5V7" />
      <path d="M6 7l1 12a2 2 0 0 0 2 1.8h6A2 2 0 0 0 17 19l1-12" />
      <path d="M10 11v6M14 11v6" />
    </template>

    <!-- edit -->
    <template v-else-if="name === 'edit'">
      <path d="M4 20h4L19.5 8.5a2.1 2.1 0 0 0-3-3L5 17v3Z" />
      <path d="m14.5 7.5 3 3" />
    </template>

    <!-- logout -->
    <template v-else-if="name === 'logout'">
      <path d="M14 4H7a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h7" />
      <path d="M10 12h10.5" />
      <path d="m17 8.5 3.5 3.5-3.5 3.5" />
    </template>

    <!-- chevron down -->
    <template v-else-if="name === 'chevron-down'">
      <path d="m6 9.5 6 6 6-6" />
    </template>

    <!-- chevron left -->
    <template v-else-if="name === 'chevron-left'">
      <path d="m14.5 6-6 6 6 6" />
    </template>

    <!-- chevron right -->
    <template v-else-if="name === 'chevron-right'">
      <path d="m9.5 6 6 6-6 6" />
    </template>

    <!-- close -->
    <template v-else-if="name === 'close'">
      <path d="m6 6 12 12M18 6 6 18" />
    </template>

    <!-- check -->
    <template v-else-if="name === 'check'">
      <path d="m5 12.5 4.5 4.5L19 7.5" />
    </template>

    <!-- check circle (filled-ish) -->
    <template v-else-if="name === 'check-circle'">
      <circle cx="12" cy="12" r="8.5" />
      <path d="m8.5 12.2 2.4 2.4 4.8-5" />
    </template>

    <!-- warning -->
    <template v-else-if="name === 'warning'">
      <path d="M12 4 2.8 19.5h18.4L12 4Z" />
      <path d="M12 10v4M12 16.8v.01" />
    </template>

    <!-- info -->
    <template v-else-if="name === 'info'">
      <circle cx="12" cy="12" r="8.5" />
      <path d="M12 11v5M12 8v.01" />
    </template>

    <!-- lock -->
    <template v-else-if="name === 'lock'">
      <rect x="5.5" y="10.5" width="13" height="9.5" rx="2.5" />
      <path d="M8.5 10.5V8a3.5 3.5 0 0 1 7 0v2.5" />
    </template>

    <!-- wallet / pay -->
    <template v-else-if="name === 'wallet'">
      <path d="M4 7.5A2.5 2.5 0 0 1 6.5 5h11A2.5 2.5 0 0 1 20 7.5v9a2.5 2.5 0 0 1-2.5 2.5h-11A2.5 2.5 0 0 1 4 16.5v-9Z" />
      <path d="M15 12h5" />
      <circle cx="15.5" cy="12" r=".4" fill="currentColor" />
    </template>

    <!-- seat / chair -->
    <template v-else-if="name === 'seat'">
      <path d="M6 4h2.5a2 2 0 0 1 2 1.7l.9 6.8a2 2 0 0 1-2 2.3H8" />
      <path d="M18 4h-2.5" />
      <path d="M6 4v8a2 2 0 0 0 2 2h9.5a2 2 0 0 1 2 2v1" />
      <path d="M7 15v5M17 15v5" />
    </template>

    <!-- refresh -->
    <template v-else-if="name === 'refresh'">
      <path d="M20 12a8 8 0 1 1-2.4-5.7" />
      <path d="M20 3.5V7h-3.5" />
    </template>

    <!-- filter -->
    <template v-else-if="name === 'filter'">
      <path d="M4 6h16l-6.3 7.3v5.2L10.3 20v-6.7L4 6Z" />
    </template>

    <!-- location / station -->
    <template v-else-if="name === 'location'">
      <path d="M12 21s-6.5-5.4-6.5-10.2A6.5 6.5 0 0 1 12 4.2a6.5 6.5 0 0 1 6.5 6.6C18.5 15.6 12 21 12 21Z" />
      <circle cx="12" cy="10.7" r="2.3" />
    </template>

    <!-- back arrow -->
    <template v-else-if="name === 'arrow-left'">
      <path d="M20 12H4" />
      <path d="m10 6-6 6 6 6" />
    </template>

    <!-- settings -->
    <template v-else-if="name === 'settings'">
      <circle cx="12" cy="12" r="3" />
      <path d="M19 12a7 7 0 0 0-.2-1.6l2-1.5-2-3.4-2.3 1a7 7 0 0 0-2.8-1.6L13.3 2h-2.6l-.4 2.9a7 7 0 0 0-2.8 1.6l-2.3-1-2 3.4 2 1.5A7 7 0 0 0 5 12c0 .5.1 1.1.2 1.6l-2 1.5 2 3.4 2.3-1a7 7 0 0 0 2.8 1.6l.4 2.9h2.6l.4-2.9a7 7 0 0 0 2.8-1.6l2.3 1 2-3.4-2-1.5c.1-.5.2-1.1.2-1.6Z" />
    </template>

    <!-- home / building -->
    <template v-else-if="name === 'home'">
      <path d="m4 11 8-7 8 7" />
      <path d="M6 9.5V19a1.5 1.5 0 0 0 1.5 1.5h9A1.5 1.5 0 0 0 18 19V9.5" />
    </template>

    <template v-else>
      <circle cx="12" cy="12" r="8.5" />
    </template>
  </svg>
</template>

<script setup>
defineProps({
  name: { type: String, required: true },
  size: { type: [Number, String], default: 18 }
})
</script>
