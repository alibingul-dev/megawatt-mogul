/* Megawatt Mogul — service worker
   The game is one big file with everything inlined, so caching it is the whole job.
   index.html goes network-first with a short timeout, so a fresh build reaches the
   player the moment they have signal, and the cached copy carries them when they
   do not. Everything else is cache-first: those files never change without a
   version bump here. */

const CACHE = 'mwmogul-v0.93.1';
const SHELL = [
  './',
  './index.html',
  './manifest.json',
  './icon-192.png',
  './icon-512.png',
  './icon-512-maskable.png'
];
const NET_TIMEOUT = 4000;
const SNAP = 'mwm-snap';      // the game's own one-line note for the daily report; never leaves the phone

self.addEventListener('install', e => {
  e.waitUntil(
    caches.open(CACHE)
      .then(c => c.addAll(SHELL))
      .then(() => self.skipWaiting())
      .catch(() => self.skipWaiting())
  );
});

self.addEventListener('activate', e => {
  e.waitUntil(
    caches.keys()
      .then(keys => Promise.all(keys.filter(k => k !== CACHE && k !== SNAP).map(k => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

function fromNetwork(req, ms) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('timeout')), ms);
    fetch(req).then(res => { clearTimeout(timer); resolve(res); },
                    err => { clearTimeout(timer); reject(err); });
  });
}

self.addEventListener('fetch', e => {
  const req = e.request;
  if (req.method !== 'GET') return;

  const url = new URL(req.url);
  if (url.origin !== self.location.origin) return;

  const isPage = req.mode === 'navigate' ||
                 url.pathname.endsWith('/') ||
                 url.pathname.endsWith('index.html');

  if (isPage) {
    e.respondWith(
      fromNetwork(req, NET_TIMEOUT)
        .then(res => {
          const copy = res.clone();
          caches.open(CACHE).then(c => c.put('./index.html', copy));
          return res;
        })
        .catch(() => caches.match('./index.html').then(r => r || caches.match('./')))
    );
    return;
  }

  e.respondWith(
    caches.match(req).then(hit => hit || fetch(req).then(res => {
      if (res && res.status === 200) {
        const copy = res.clone();
        caches.open(CACHE).then(c => c.put(req, copy));
      }
      return res;
    }).catch(() => hit))
  );
});


/* Daily report. The game writes {t, on, title, body} into the mwm-snap cache when it saves.
   Android Chrome wakes this worker for periodic background sync on its own schedule; we show
   the line at most once in 20 hours, and only if the game has not been played for 8 hours. */
self.addEventListener('periodicsync', e => {
  if (e.tag === 'mwm-daily') e.waitUntil(dailyNote());
});
async function dailyNote() {
  const c = await caches.open(SNAP);
  const r = await c.match('./__snap');
  if (!r) return;
  const s = await r.json();
  if (!s.on || !s.body) return;
  const now = Date.now();
  if (now - s.t < 8 * 3600e3) return;
  const l = await c.match('./__last');
  const last = l ? +(await l.text()) : 0;
  if (now - last < 20 * 3600e3) return;
  const wins = await self.clients.matchAll({ type: 'window' });
  if (wins.some(w => w.visibilityState === 'visible')) return;
  await c.put('./__last', new Response(String(now)));
  await self.registration.showNotification(s.title || 'Megawatt Mogul', {
    body: s.body, icon: './icon-192.png', badge: './icon-192.png', tag: 'mwm-daily'
  });
}
self.addEventListener('notificationclick', e => {
  e.notification.close();
  e.waitUntil(self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then(ws => {
    for (const w of ws) if ('focus' in w) return w.focus();
    return self.clients.openWindow('./');
  }));
});
