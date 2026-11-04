// BetterPod <-> YouTube Music bridge (written for BetterPod).
// Android runs this at the very start of every music.youtube.com page, before YouTube's own code.
// It talks to the app through window.BP (BP.state, BP.reply, BP.skipAds).
(function () {
  if (window.top !== window || window.__bp) return;

  // ---------------------------------------------------------------- 1. Keep playing with the screen off
  // Mobile pages pause music when they think they're hidden. Here, the page is always "visible".
  function always(value) { return { get: function () { return value; }, configurable: true }; }
  try {
    Object.defineProperty(Document.prototype, 'hidden', always(false));
    Object.defineProperty(Document.prototype, 'visibilityState', always('visible'));
    Object.defineProperty(Document.prototype, 'webkitHidden', always(false));
    Object.defineProperty(Document.prototype, 'webkitVisibilityState', always('visible'));
  } catch (e) {}
  ['visibilitychange', 'webkitvisibilitychange'].forEach(function (type) {
    var stop = function (e) { e.stopImmediatePropagation(); };
    window.addEventListener(type, stop, true);
    document.addEventListener(type, stop, true);
  });

  // ---------------------------------------------------------------- 2. Remember next/previous handlers
  // YouTube tells the browser what "next track" means (for headphone buttons). We keep a copy.
  var handlers = {};
  try {
    var ms = navigator.mediaSession;
    if (ms && ms.setActionHandler) {
      var original = ms.setActionHandler.bind(ms);
      ms.setActionHandler = function (action, fn) {
        handlers[action] = fn;
        try { return original(action, fn); } catch (e) {}
      };
    }
  } catch (e) {}

  function one(sel) { return document.querySelector(sel); }
  function clickFirst(sels) {
    for (var i = 0; i < sels.length; i++) {
      var el = one(sels[i]);
      if (el) { el.click(); return true; }
    }
    return false;
  }
  function video() { return one('video'); }
  function text(el) { return el ? (el.textContent || '').trim() : ''; }

  // ---------------------------------------------------------------- 3. Ads: mute, jump to the end, press Skip
  var mutedByUs = false;
  function handleAds() {
    var player = one('#movie_player');
    var v = video();
    var ad = !!(player && (player.classList.contains('ad-showing') || player.classList.contains('ad-interrupting')));
    var on = true;
    try { on = BP.skipAds(); } catch (e) {}
    if (ad && on && v) {
      if (!v.muted) { v.muted = true; mutedByUs = true; }
      if (isFinite(v.duration) && v.duration > 0 && v.currentTime < v.duration - 0.2) v.currentTime = v.duration;
      clickFirst(['.ytp-skip-ad-button', '.ytp-ad-skip-button-modern', '.ytp-ad-skip-button']);
    } else if (!ad && mutedByUs && v) {
      v.muted = false;
      mutedByUs = false;
    }
    return ad;
  }

  // "Are you still listening?" -> yes.
  function answerStillThere() {
    var box = one('ytmusic-you-there-renderer');
    if (box && box.offsetParent !== null) {
      var b = box.querySelector('button, tp-yt-paper-button');
      if (b) b.click();
    }
  }

  // ---------------------------------------------------------------- 4. Tell the app what's playing (every second)
  var adNow = false;
  function snapshot() {
    var v = video();
    var md = null;
    try { md = navigator.mediaSession && navigator.mediaSession.metadata; } catch (e) {}
    var bar = one('ytmusic-player-bar');
    var byline = text(bar && bar.querySelector('.byline')).split('•').map(function (s) { return s.trim(); });
    var art = '';
    if (md && md.artwork && md.artwork.length) art = md.artwork[md.artwork.length - 1].src;
    else {
      var img = bar && bar.querySelector('img');
      if (img) art = img.src || '';
    }
    var cfg = window.ytcfg && window.ytcfg.get ? window.ytcfg : null;
    return {
      title: (md && md.title) || text(bar && bar.querySelector('.title')),
      artist: (md && md.artist) || byline[0] || '',
      album: (md && md.album) || byline[1] || '',
      art: art,
      videoId: location.pathname === '/watch' ? (new URLSearchParams(location.search).get('v') || '') : '',
      playing: !!(v && !v.paused && !v.ended),
      loading: !!(v && !v.paused && v.readyState < 3),
      pos: v ? Math.floor(v.currentTime * 1000) : 0,
      dur: v && isFinite(v.duration) ? Math.floor(v.duration * 1000) : 0,
      ad: adNow,
      ready: !!(cfg && cfg.get('INNERTUBE_CONTEXT'))
    };
  }
  function report() { try { BP.state(JSON.stringify(snapshot())); } catch (e) {} }
  function reportSoon() { setTimeout(report, 200); setTimeout(report, 900); }
  setInterval(function () {
    adNow = handleAds();
    answerStillThere();
    report();
  }, 1000);

  // ---------------------------------------------------------------- 5. Asking YouTube Music for lists
  // The website gets its data from /youtubei/v1/... We ask the same way, from inside the page,
  // so your sign-in cookies come along automatically.
  var SONGS_ONLY = 'EgWKAQIIAWoMEA4QChADEAQQCRAF';

  function cookie(name) {
    var m = document.cookie.match(new RegExp('(?:^|; )' + name + '=([^;]*)'));
    return m ? m[1] : null;
  }
  // Signed-in requests carry a hash of your session cookie, like the real site does.
  function authHeader() {
    var sid = cookie('SAPISID') || cookie('__Secure-3PAPISID');
    if (!sid || !window.crypto || !crypto.subtle) return Promise.resolve(null);
    var ts = Math.floor(Date.now() / 1000);
    var data = new TextEncoder().encode(ts + ' ' + sid + ' ' + location.origin);
    return crypto.subtle.digest('SHA-1', data).then(function (buf) {
      var hex = Array.prototype.map.call(new Uint8Array(buf), function (b) {
        return ('0' + b.toString(16)).slice(-2);
      }).join('');
      return 'SAPISIDHASH ' + ts + '_' + hex;
    });
  }
  function innertube(endpoint, body) {
    var cfg = window.ytcfg;
    body.context = cfg.get('INNERTUBE_CONTEXT');
    return authHeader().then(function (auth) {
      var headers = {
        'Content-Type': 'application/json',
        'X-Origin': location.origin,
        'X-Goog-AuthUser': String(cfg.get('SESSION_INDEX') || 0)
      };
      var visitor = cfg.get('VISITOR_DATA');
      if (visitor) headers['X-Goog-Visitor-Id'] = visitor;
      if (auth) headers['Authorization'] = auth;
      return fetch('/youtubei/v1/' + endpoint + '?prettyPrint=false', {
        method: 'POST', headers: headers, credentials: 'include', body: JSON.stringify(body)
      });
    }).then(function (r) {
      if (!r.ok) throw new Error('YouTube Music answered ' + r.status);
      return r.json();
    });
  }

  // YouTube's answers are deeply nested. Walk the whole thing and pick out every song / album / playlist row.
  function runs(t) {
    if (!t) return '';
    if (t.simpleText) return t.simpleText;
    return (t.runs || []).map(function (r) { return r.text; }).join('');
  }
  function find(o, key, depth) {
    depth = depth || 0;
    if (!o || typeof o !== 'object' || depth > 25) return null;
    if (!Array.isArray(o) && o[key] !== undefined) return o[key];
    for (var k in o) {
      var hit = find(o[k], key, depth + 1);
      if (hit !== null && hit !== undefined) return hit;
    }
    return null;
  }
  function lastThumb(o) {
    var list = find(o, 'thumbnails');
    return list && list.length ? list[list.length - 1].url : '';
  }
  function fromListItem(r) {
    var cols = (r.flexColumns || []).map(function (c) {
      var x = c.musicResponsiveListItemFlexColumnRenderer;
      return x ? x.text : null;
    });
    var nav = r.navigationEndpoint && r.navigationEndpoint.browseEndpoint;
    return {
      title: runs(cols[0]),
      sub: cols.slice(1).map(runs).filter(Boolean).join(' • '),
      videoId: (r.playlistItemData && r.playlistItemData.videoId) || find(cols[0], 'videoId') || find(r.overlay, 'videoId') || '',
      playlistId: '',
      browseId: nav ? nav.browseId : '',
      thumb: lastThumb(r.thumbnail)
    };
  }
  function fromTwoRow(r) {
    var ep = r.navigationEndpoint || {};
    return {
      title: runs(r.title),
      sub: runs(r.subtitle),
      videoId: ep.watchEndpoint ? ep.watchEndpoint.videoId || '' : '',
      playlistId: ep.watchEndpoint ? ep.watchEndpoint.playlistId || '' : '',
      browseId: ep.browseEndpoint ? ep.browseEndpoint.browseId : '',
      thumb: lastThumb(r.thumbnailRenderer)
    };
  }
  function collect(o, out, depth) {
    if (!o || typeof o !== 'object' || depth > 40) return;
    if (Array.isArray(o)) {
      for (var i = 0; i < o.length; i++) collect(o[i], out, depth + 1);
      return;
    }
    for (var k in o) {
      if (k === 'musicResponsiveListItemRenderer') out.push(fromListItem(o[k]));
      else if (k === 'musicTwoRowItemRenderer') out.push(fromTwoRow(o[k]));
      else collect(o[k], out, depth + 1);
    }
  }
  function items(json) {
    var out = [];
    collect(json, out, 0);
    var seen = {};
    return out.filter(function (x) {
      var key = x.videoId || x.browseId;
      if (!x.title || !key || seen[key]) return false;
      seen[key] = true;
      return true;
    });
  }

  // ---------------------------------------------------------------- 6. Buttons the app can press
  function press(action, sels) {
    if (handlers[action]) {
      try { handlers[action]({ action: action }); return; } catch (e) {}
    }
    clickFirst(sels);
  }
  window.__bp = {
    play: function () { var v = video(); if (v) v.play(); reportSoon(); },
    pause: function () { var v = video(); if (v) v.pause(); reportSoon(); },
    seek: function (ms) { var v = video(); if (v) v.currentTime = ms / 1000; reportSoon(); },
    next: function () { press('nexttrack', ['ytmusic-player-bar .next-button', '.next-button']); reportSoon(); },
    prev: function () { press('previoustrack', ['ytmusic-player-bar .previous-button', '.previous-button']); reportSoon(); },
    api: function (id, kind, arg) {
      var job = kind === 'search'
        ? innertube('search', { query: arg, params: SONGS_ONLY }).then(function (j) {
            var found = items(j);
            return found.length ? found : innertube('search', { query: arg }).then(items);
          })
        : innertube('browse', { browseId: arg }).then(items);
      job.then(function (list) {
        BP.reply(id, JSON.stringify({ items: list.slice(0, 300) }));
      }).catch(function (e) {
        BP.reply(id, JSON.stringify({ error: String((e && e.message) || e) }));
      });
    }
  };
})();
