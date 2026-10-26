// ENJT · web instalada (N49, v0.4.60)
// Con conexión, siempre se descarga lo último publicado; sin conexión, se usa la última copia guardada.
// Solo toca los archivos de la propia web: el ranking y la nube (Supabase) van siempre por la red.
const CACHE = "enjt-web";

self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", e => e.waitUntil(self.clients.claim()));

self.addEventListener("fetch", e => {
  const req = e.request;
  if (req.method !== "GET" || new URL(req.url).origin !== self.location.origin) return;
  e.respondWith(
    fetch(req, {cache: "no-store"}).then(res => {
      if (res.ok) {
        const copy = res.clone();
        caches.open(CACHE).then(c => c.put(req, copy));
      }
      return res;
    }).catch(() => caches.match(req, {ignoreSearch: true}).then(r => r || caches.match("./")))
  );
});
