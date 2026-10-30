// Fills the download buttons and file lists from the GitHub Releases API.
// Without a network, or past the API's unauthenticated limit, the static links on the page stay as they are.
(function () {
  var REPOS = {
    android: 'Orangeriger1998/OTStudio',
    desktop: 'Orangeriger1998/OTStudio-PC'
  };
  var MATCH = {
    android: function (n) { return /\.apk$/i.test(n); },
    trainer: function (n) { return /LoRA-Trainer\.exe$/i.test(n); },
    windows: function (n) { return /Setup-.*\.exe$/i.test(n); },
    linux: function (n) { return /\.(AppImage|deb)$/i.test(n); }
  };

  function size(bytes) {
    var mb = bytes / 1048576;
    return (mb >= 100 ? Math.round(mb) : mb.toFixed(1)) + ' MB';
  }

  function date(iso) {
    try {
      return new Date(iso).toLocaleDateString(undefined, { year: 'numeric', month: 'long', day: 'numeric' });
    } catch (e) { return ''; }
  }

  function fetchReleases(repo) {
    return fetch('https://api.github.com/repos/' + repo + '/releases?per_page=10', {
      headers: { Accept: 'application/vnd.github+json' }
    }).then(function (r) { if (!r.ok) { throw new Error(r.status); } return r.json(); });
  }

  // The newest release that carries a file matching the platform.
  function latestWith(releases, test) {
    for (var i = 0; i < releases.length; i++) {
      var hits = (releases[i].assets || []).filter(function (a) { return test(a.name); });
      if (hits.length) { return { release: releases[i], assets: hits, all: releases[i].assets || [] }; }
    }
    return null;
  }

  function sumFor(all, name) {
    for (var i = 0; i < all.length; i++) {
      if (all[i].name === name + '.sha256') { return all[i]; }
    }
    return null;
  }

  function fillButton(platform, found) {
    var buttons = document.querySelectorAll('[data-button="' + platform + '"]');
    Array.prototype.forEach.call(buttons, function (b) {
      var main = found.assets[0];
      b.href = main.browser_download_url;
      var sub = b.querySelector('.sub');
      if (sub) {
        var text = found.release.tag_name.replace(/^v/, '') + ' · ' + size(main.size);
        if (found.assets.length > 1) { text += ' and ' + (found.assets.length - 1) + ' more'; }
        sub.textContent = text;
      }
    });
  }

  function fillCard(platform, found) {
    var cards = document.querySelectorAll('[data-platform="' + platform + '"]');
    Array.prototype.forEach.call(cards, function (card) {
      var v = card.querySelector('.version');
      if (v) { v.textContent = 'Version ' + found.release.tag_name.replace(/^v/, ''); }
      var d = card.querySelector('.date');
      if (d) { d.textContent = 'Released ' + date(found.release.published_at); }
      var list = card.querySelector('.files');
      if (list) {
        list.innerHTML = '';
        found.assets.forEach(function (a) {
          var li = document.createElement('li');
          var link = document.createElement('a');
          link.className = 'name';
          link.href = a.browser_download_url;
          link.textContent = a.name;
          li.appendChild(link);
          var s = document.createElement('span');
          s.className = 'size';
          s.textContent = size(a.size);
          li.appendChild(s);
          var sum = sumFor(found.all, a.name);
          if (sum) {
            var c = document.createElement('a');
            c.className = 'sum';
            c.href = sum.browser_download_url;
            c.textContent = 'SHA-256';
            li.appendChild(c);
          }
          list.appendChild(li);
        });
      }
      var notes = card.querySelector('.notes-link');
      if (notes) { notes.href = found.release.html_url; }
    });
  }

  function apply(platform, found) {
    if (!found) { return; }
    fillButton(platform, found);
    fillCard(platform, found);
  }

  if (!window.fetch) { return; }
  fetchReleases(REPOS.android).then(function (releases) {
    apply('android', latestWith(releases, MATCH.android));
    apply('trainer', latestWith(releases, MATCH.trainer));
  }).catch(function () {});
  fetchReleases(REPOS.desktop).then(function (releases) {
    apply('windows', latestWith(releases, MATCH.windows));
    apply('linux', latestWith(releases, MATCH.linux));
  }).catch(function () {});
})();
