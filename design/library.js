const params = new URLSearchParams(location.search);
const concept = LUNO_CONCEPTS.find(c => c.id === params.get('concept')) || LUNO_CONCEPTS[0];
document.title = `Luno · ${concept.id} ${concept.name}`;
const icons = {
  search: '<circle cx="10.7" cy="10.7" r="6.7"/><path d="m16 16 4.5 4.5"/>',
  play: '<path fill="currentColor" stroke="none" d="M8 4.5v15l12-7.5z"/>',
  pause: '<path fill="currentColor" stroke="none" d="M6 5h4v14H6zm8 0h4v14h-4z"/>',
  heart: '<path d="M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1.1-1.1a5.5 5.5 0 0 0-7.8 7.8L12 21l8.8-8.6a5.5 5.5 0 0 0 0-7.8Z"/>',
  shuffle: '<path d="M3 6h2c6 0 8 12 14 12h2M3 18h2c2.5 0 4-2 5.5-4.5M13.5 10.5C15 8 17 6 19 6h2m-4-4 4 4-4 4m0 4 4 4-4 4"/>',
  songs: '<path d="M9 18V5l11-2v13M9 7l11-2"/><ellipse cx="6" cy="18" rx="3" ry="2.5"/><ellipse cx="17" cy="16" rx="3" ry="2.5"/>',
  playlists: '<path d="M4 5h14M4 10h14M4 15h8m5-1v7l5-3.5Z"/>',
  more: '<circle cx="12" cy="5" r="1" fill="currentColor"/><circle cx="12" cy="12" r="1" fill="currentColor"/><circle cx="12" cy="19" r="1" fill="currentColor"/>',
  sort: '<path d="M5 6h14M5 12h10M5 18h6"/>',
  home: '<path d="m3 10 9-7 9 7v11h-6v-7H9v7H3Z"/>',
  library: '<path d="M4 3v18M10 3v18m5-18 5 18"/>',
  close: '<path d="m6 6 12 12M6 18 18 6"/>',
  arrow: '<path d="m9 5 7 7-7 7"/>',
  back: '<path d="m14 5-7 7 7 7"/>',
  check: '<path d="m5 12 4 4L19 6"/>',
  signal: '<path d="M5 17v4m5-8v8m5-12v12m5-17v17"/>',
  wifi: '<path d="M2 8a16 16 0 0 1 20 0M5 12a11 11 0 0 1 14 0M8 16a6 6 0 0 1 8 0"/><circle cx="12" cy="20" r=".8" fill="currentColor"/>',
  battery: '<rect x="2" y="6" width="18" height="12" rx="2"/><path d="M22 10v4"/><rect x="5" y="9" width="12" height="6" rx=".7" fill="currentColor" stroke="none"/>'
};
const icon = (name, cls = '') => `<svg class="icon ${cls}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${icons[name] || icons.songs}</svg>`;
const art = (n, cls = '') => `<img class="art ${cls}" src="assets/cover-${n}.svg" alt="" draggable="false">`;
const tracks = [
  {id:1,title:'Calling on a Feeling',artist:'Maximum Love',album:'Maximum Love',art:1,duration:237,added:14,liked:false},
  {id:2,title:'Raise Your Weapon',artist:'deadmau5',album:'4×4=12',art:2,duration:502,added:13,liked:true},
  {id:3,title:'Black and White',artist:'Σtella, Redinho',album:'Up and Away',art:3,duration:213,added:12,liked:true},
  {id:4,title:'Never Enough (Re:um Remix)',artist:'AMANZI, Re:um',album:'Never Enough',art:4,duration:261,added:11,liked:true},
  {id:5,title:'Oh Lordy',artist:'Re:um',album:'Oh Lordy',art:5,duration:224,added:10,liked:true},
  {id:6,title:'Don’t Stop',artist:'Cally D, Aekin',album:'Don’t Stop',art:6,duration:196,added:9,liked:false},
  {id:7,title:'Midnight City',artist:'M83',album:'Hurry Up, We’re Dreaming',art:7,duration:243,added:8,liked:true},
  {id:8,title:'A Walk',artist:'Tycho',album:'Dive',art:8,duration:316,added:7,liked:false},
  {id:9,title:'Innerbloom',artist:'RÜFÜS DU SOL',album:'Bloom',art:3,duration:578,added:6,liked:true},
  {id:10,title:'Sunset Lover',artist:'Petit Biscuit',album:'Presence',art:8,duration:237,added:5,liked:true},
  {id:11,title:'Something About Us',artist:'Daft Punk',album:'Discovery',art:5,duration:232,added:4,liked:true},
  {id:12,title:'Nightcall',artist:'Kavinsky',album:'OutRun',art:1,duration:258,added:3,liked:true}
];
const playlists = [
  {id:'liked',name:'Liked Songs',description:'Songs you marked as favorites',count:14,minutes:281,art:0,added:99},
  {id:'unsorted',name:'Unsorted',description:'Your library additions',count:26,minutes:112,art:8,added:98},
  {id:'rap',name:'Rap',description:'Playlist',count:82,minutes:310,art:6,added:6},
  {id:'synthwave',name:'Synthwave',description:'Playlist',count:64,minutes:274,art:1,added:5},
  {id:'soulshred',name:'SoulShred',description:'Playlist',count:51,minutes:198,art:3,added:4},
  {id:'hardvibes',name:'Hard_Vibes',description:'Playlist',count:96,minutes:407,art:5,added:3},
  {id:'rock',name:'Rock',description:'Playlist',count:74,minutes:292,art:2,added:2}
];
const state = {view:concept.defaultView,query:'',sort:concept.id === '05' ? 'az':'recent',current:tracks[0],playing:false};
const phone = document.getElementById('phone');
const cover = p => p.id === 'liked' ? `<div class="art liked-art">${icon('heart')}</div>` : art(p.art);
const compactPlay = () => `<button class="icon-button primary small-play" data-action="play" aria-label="Play library">${icon('play')}</button>`;
const shuffle = (label = true) => `<button class="shuffle-button" data-action="shuffle" aria-label="Shuffle library">${icon('shuffle')}${label ? '<span>Shuffle</span>':''}</button>`;
const actions = (cls = '') => `<div class="playback-actions ${cls}"><button class="play-button" data-action="play">${icon('play')}<span>Play</span></button>${shuffle()}</div>`;
const title = (right = 'more') => `<header class="title-row"><div><h1>Your Library</h1><p class="library-meta">480 songs <span>·</span> 6 playlists</p></div>${right === 'play' ? compactPlay() : `<button class="icon-button library-more" data-action="options" aria-label="Library options">${icon('more')}</button>`}</header>`;
const tabs = (cls = '') => `<nav class="view-tabs ${cls}" aria-label="Library view"><button data-view="songs" aria-pressed="${state.view === 'songs'}">${icon('songs')}<span>Songs</span></button><button data-view="playlists" aria-pressed="${state.view === 'playlists'}">${icon('playlists')}<span>Playlists</span></button></nav>`;
const sort = () => `<label class="sort-control">${icon('sort')}<select aria-label="Sort library"><option value="recent">Recently added</option><option value="az">A–Z</option><option value="za">Z–A</option><option value="duration">Duration</option></select></label>`;
const search = (cls = '', play = false) => `<div class="search-box ${cls}"><label>${icon('search')}<input type="search" placeholder="Search your library" aria-label="Search your library" autocomplete="off" spellcheck="false"></label><button class="clear-search" data-action="clear" aria-label="Clear search" hidden>${icon('close')}</button>${play ? compactPlay():''}</div>`;
const head = (label = 'Recently added') => `<div class="result-head"><div><h2 data-list-heading>${label}</h2><span data-result-count></span></div>${sort()}</div>`;
const results = (cls = '') => `<section class="results ${cls}" data-results aria-label="Library items"></section>`;
const likedShortcut = () => `<button class="liked-shortcut" data-playlist="liked"><span class="liked-symbol">${icon('heart')}</span><span><strong>Liked Songs</strong><small>14 songs</small></span>${icon('arrow')}</button>`;
const blockTabs = () => `<div class="overview-tiles"><button data-view="songs"><div><small>YOUR MUSIC</small><strong>Songs</strong><span>480 tracks</span></div><span class="overview-art">${art(2)}${art(1)}</span></button><button data-view="playlists"><div><small>COLLECTIONS</small><strong>Playlists</strong><span>6 playlists</span></div><span class="overview-art">${art(8)}${art(3)}</span></button></div>`;
const layouts = {
  '01': () => `${title()}${actions('unified-actions')}${search()}<div class="view-line">${tabs('underline')}${sort()}</div>${likedShortcut()}${head()}${results()}`,
  '02': () => `${title()}<section class="stack-hero"><div class="hero-count"><small>YOUR COLLECTION</small><h2>480 songs.<br>One library.</h2><p>Ready whenever you are.</p></div><div class="cover-stack">${art(3)}${art(2)}${art(1)}</div><div class="hero-play">${compactPlay()}${shuffle()}</div></section>${search()}${tabs('segmented')}${head()}${results()}`,
  '03': () => `<div class="grid-header">${title()}<div class="header-play">${compactPlay()}${shuffle(false)}</div></div>${search()}<div class="view-line">${tabs('pills')}${sort()}</div>${head('Your collection')}${results('collection-grid')}`,
  '04': () => `<div class="layer-header">${title()}${search()}</div><div class="content-sheet">${actions('sheet-actions')}${tabs('segmented')}${head()}${results()}</div>`,
  '05': () => `${title()}<div class="index-top"><p>Every track. Easy to find.</p>${actions('compact-actions')}</div>${search('search-line')}<div class="view-line">${tabs('underline')}${sort()}</div><div class="indexed-content">${results('indexed-results')}<nav class="alphabet" aria-label="Jump to title">${'ABCDIMNORS'.split('').map(l=>`<button data-letter="${l}" aria-label="Jump to ${l}">${l}</button>`).join('')}</nav></div>`,
  '06': () => `<section class="canopy-hero"><div class="cover-wall" aria-hidden="true">${[1,2,3,8,5,6].map(n=>art(n)).join('')}</div>${title()}<div class="canopy-copy"><span>480 songs</span><h2>Your music.<br>Within reach.</h2></div>${actions()}</section><div class="canopy-body">${search('floating-search')}${tabs('underline')}${head()}${results()}</div>`,
  '07': () => `${title()}${search()}<div class="split-layout">${tabs('vertical-tabs')}<div class="rail-main">${actions('rail-actions')}${head()}${results()}</div></div>`,
  '08': () => `${title()}${search()}${blockTabs()}${actions('wide-actions')}<div class="block-collection">${head()}${results()}</div>`,
  '09': () => `${title()}${tabs('large-tabs')}<div class="joined-tools">${search('joined-search',true)}${shuffle(false)}</div>${head()}${results('spaced-results')}`,
  '10': () => `<div class="shelf-heading">${title('play')}${shuffle(false)}</div><div class="view-line">${tabs('underline')}${sort()}</div>${search('shelf-search')}${head('Your collection')}${results('record-sleeves')}`
};
const selectedPreview = concept.id === '10' && params.get('preview') === 'selected';
if (selectedPreview) {
  layouts['10'] = () => `<section class="shelf-hero"><div class="shelf-cover-wall" aria-hidden="true">${[1,2,3,8,5,6,1,2,3].map(n=>`<span>${art(n)}</span>`).join('')}</div><div class="shelf-heading">${title('play')}${shuffle(false)}</div><div class="view-line">${tabs('underline')}${sort()}</div>${search('search-line')}${head('Your collection')}</section>${results('record-sleeves')}`;
  phone.classList.add('selected-preview');
}
phone.classList.add(`concept-${concept.className}`);
phone.innerHTML = `<div class="status-bar"><span>9:41</span><div>${icon('signal')}${icon('wifi')}${icon('battery')}</div></div><div class="library-scroll">${layouts[concept.id]()}</div><footer class="app-footer"><section class="mini-player" aria-label="Currently playing"><div class="player-progress"><span></span></div><div class="player-content"><span data-player-art>${art(state.current.art)}</span><button class="player-track" data-action="now-playing"><strong data-player-title>${state.current.title}</strong><small data-player-artist>${state.current.artist}</small></button><button class="icon-button player-heart" data-action="current-like" aria-label="Add current song to favorites">${icon('heart')}</button><button class="icon-button player-toggle" data-action="toggle" aria-label="Play current song">${icon('play')}</button></div></section><nav class="bottom-nav" aria-label="App navigation"><span>${icon('home')}<small>Home</small></span><span>${icon('search')}<small>Search</small></span><span class="active" aria-current="page">${icon('library')}<small>Your Library</small></span></nav><div class="gesture-bar"><span></span></div></footer><dialog class="detail-sheet"><div class="sheet-handle"></div><button class="icon-button sheet-close" data-action="close-sheet" aria-label="Close">${icon('close')}</button><div class="sheet-body"></div></dialog><div class="toast" role="status" aria-live="polite"></div>`;
const esc = value => String(value).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const duration = seconds => `${Math.floor(seconds/60)}:${String(seconds%60).padStart(2,'0')}`;
const filteredItems = () => {
  const query = state.query.toLocaleLowerCase();
  const source = state.view === 'songs' ? tracks : playlists;
  const items = source.filter(item => (state.view === 'songs' ? `${item.title} ${item.artist} ${item.album}` : item.name).toLocaleLowerCase().includes(query));
  const comparator = {recent:(a,b)=>b.added-a.added,az:(a,b)=>(a.title || a.name).localeCompare(b.title || b.name),za:(a,b)=>(b.title || b.name).localeCompare(a.title || a.name),duration:(a,b)=>(b.duration || b.minutes)-(a.duration || a.minutes)}[state.sort];
  return items.sort((a,b) => {
    if(state.view === 'playlists') {
      const rank = item => item.id === 'liked' ? 0 : item.id === 'unsorted' ? 1 : 2;
      if(rank(a) !== rank(b)) return rank(a)-rank(b);
    }
    return comparator(a,b);
  });
};
const trackRow = track => `<article class="track-row${state.current.id === track.id ? ' current-track':''}" data-track-row="${track.id}"><button class="track-main" data-track="${track.id}" aria-label="Play ${esc(track.title)}">${art(track.art)}<span class="track-copy"><strong>${esc(track.title)}</strong><small>${esc(track.artist)} <span class="row-duration">· ${duration(track.duration)}</span></small></span></button><button class="row-heart ${track.liked?'is-liked':''}" data-like="${track.id}" aria-label="${track.liked?'Remove '+esc(track.title)+' from':'Add '+esc(track.title)+' to'} favorites" aria-pressed="${track.liked}">${icon('heart')}</button><button class="row-more" data-track-options="${track.id}" aria-label="Options for ${esc(track.title)}">${icon('more')}</button></article>`;
const playlistCard = p => `<article class="playlist-card ${p.id === 'liked'?'liked-card':''}"><button class="playlist-main" data-playlist="${p.id}" aria-label="Open ${p.name}">${cover(p)}<span class="playlist-copy"><strong>${p.name}</strong><small>${p.count} songs${concept.id === '10'?' · '+Math.round(p.minutes/60)+' hr':''}</small></span>${concept.id === '10'?'<span class="sleeve-mark">'+icon('arrow')+'</span>':''}</button><button class="playlist-more" data-playlist-options="${p.id}" aria-label="Options for ${p.name}">${icon('more')}</button></article>`;
function renderResults() {
  const items = filteredItems();
  const target = phone.querySelector('[data-results]');
  target.classList.toggle('song-results',state.view === 'songs');
  target.classList.toggle('playlist-results',state.view === 'playlists');
  if (!items.length) target.innerHTML = `<div class="empty-state">${icon('search')}<h3>No matches</h3><p>Try another song, artist or playlist.</p><button data-action="clear">Clear search</button></div>`;
  else if(concept.id === '05' && state.view === 'songs' && ['az','za'].includes(state.sort)) {
    let letter='';
    target.innerHTML = items.map(t=>{const next=t.title[0].toUpperCase();const label=next!==letter?`<h2 class="letter-heading" id="letter-${next}">${next}</h2>`:'';letter=next;return label+trackRow(t);}).join('');
  } else target.innerHTML = items.map(state.view === 'songs' ? trackRow:playlistCard).join('');
  phone.querySelectorAll('[data-view]').forEach(button=>{const active=button.dataset.view===state.view;button.classList.toggle('selected',active);button.setAttribute('aria-pressed',active);});
  phone.querySelectorAll('select').forEach(select=>select.value=state.sort);
  phone.querySelectorAll('[data-list-heading]').forEach(el=>el.textContent=state.query?'Search results':state.view==='playlists'?'Your collection':({recent:'Recently added',az:'All songs',za:'All songs',duration:'Longest first'})[state.sort]);
  phone.querySelectorAll('[data-result-count]').forEach(el=>el.textContent=state.query?`${items.length} ${items.length===1?'result':'results'}`:state.view==='songs'?'480 songs':'6 playlists + Liked Songs');
  phone.querySelector('.alphabet')?.classList.toggle('is-hidden',state.view!=='songs');
  phone.querySelector('.clear-search').hidden=!state.query;
  phone.querySelectorAll('.liked-shortcut').forEach(el=>el.hidden=state.view==='playlists'||!!state.query);
}
function updatePlayer() {
  phone.querySelector('[data-player-art]').innerHTML=art(state.current.art);
  phone.querySelector('[data-player-title]').textContent=state.current.title;
  phone.querySelector('[data-player-artist]').textContent=state.current.artist;
  const toggle=phone.querySelector('.player-toggle');
  toggle.innerHTML=icon(state.playing?'pause':'play');
  toggle.setAttribute('aria-label',state.playing?'Pause current song':'Play current song');
  const heart=phone.querySelector('.player-heart');
  heart.classList.toggle('is-liked',state.current.liked);
  heart.setAttribute('aria-pressed',state.current.liked);
  heart.setAttribute('aria-label',state.current.liked?'Remove current song from favorites':'Add current song to favorites');
  phone.querySelectorAll('.sheet-option[data-action="toggle"]').forEach(button => {
    button.innerHTML = `${icon(state.playing?'pause':'play')}${state.playing?'Pause':'Play'}`;
  });
}
function toggleFavorite(track) {
  track.liked = !track.liked;
  renderResults();
  updatePlayer();
  phone.querySelectorAll(`[data-like="${track.id}"]`).forEach(button => {
    if (button.classList.contains('sheet-option')) {
      button.innerHTML = `${icon('heart')}${track.liked?'Remove from':'Add to'} favorites`;
    } else {
      button.classList.toggle('is-liked', track.liked);
      button.setAttribute('aria-pressed', track.liked);
      button.setAttribute('aria-label', `${track.liked?'Remove':'Add'} ${track.title} ${track.liked?'from':'to'} favorites`);
    }
  });
}
let toastTimeout;
function toast(text) { const el=phone.querySelector('.toast');el.textContent=text;el.classList.add('visible');clearTimeout(toastTimeout);toastTimeout=setTimeout(()=>el.classList.remove('visible'),2200); }
const sheet=phone.querySelector('.detail-sheet');
let sheetTrigger;
function openSheet(html,trigger) {sheetTrigger=trigger;phone.querySelector('.sheet-body').innerHTML=html;sheet.showModal();phone.querySelector('.sheet-close').focus();}
sheet.addEventListener('close',()=>sheetTrigger?.focus());
sheet.addEventListener('click',e=>{if(e.target===sheet)sheet.close();});
phone.querySelector('input').addEventListener('input',e=>{state.query=e.target.value;renderResults();});
phone.querySelectorAll('select').forEach(select=>select.addEventListener('change',e=>{state.sort=e.target.value;renderResults();}));
phone.addEventListener('click',e=>{
  const button=e.target.closest('button');if(!button)return;
  if(button.dataset.view){state.view=button.dataset.view;renderResults();return;}
  if(button.dataset.track){state.current=tracks.find(t=>t.id===Number(button.dataset.track));state.playing=true;updatePlayer();renderResults();if(sheet.open)sheet.close();return;}
  if(button.dataset.like){toggleFavorite(tracks.find(t=>t.id===Number(button.dataset.like)));return;}
  if(button.dataset.letter){const letter=button.dataset.letter;state.sort='az';renderResults();phone.querySelector(`#letter-${letter}`)?.scrollIntoView({behavior:'smooth',block:'start'});return;}
  if(button.dataset.playlist){const p=playlists.find(p=>p.id===button.dataset.playlist);const sample=p.id==='liked'?tracks.filter(t=>t.liked):tracks.slice(0,6);openSheet(`<div class="playlist-detail">${cover(p)}<small>PLAYLIST</small><h2>${p.name}</h2><p>${p.count} songs</p></div><div class="sheet-tracks">${sample.map(trackRow).join('')}</div>`,button);return;}
  if(button.dataset.trackOptions){const t=tracks.find(t=>t.id===Number(button.dataset.trackOptions));openSheet(`<div class="track-detail">${art(t.art)}<h2>${esc(t.title)}</h2><p>${esc(t.artist)}</p></div><button class="sheet-option" data-track="${t.id}">${icon('play')}Play song</button><button class="sheet-option" data-like="${t.id}">${icon('heart')}${t.liked?'Remove from':'Add to'} favorites</button>`,button);return;}
  if(button.dataset.playlistOptions){const p=playlists.find(p=>p.id===button.dataset.playlistOptions);openSheet(`<h2>${p.name}</h2><button class="sheet-option" data-playlist="${p.id}">${icon('playlists')}Open playlist</button>`,button);return;}
  if(button.dataset.setSort){state.sort=button.dataset.setSort;renderResults();sheet.close();return;}
  const action=button.dataset.action;
  if(action==='clear'){state.query='';phone.querySelector('input').value='';renderResults();phone.querySelector('input').focus();}
  if(action==='play'||action==='shuffle'){const items=filteredItems();if(!items.length)return;const candidates=state.view==='songs'?items:tracks;state.current=action==='shuffle'?candidates[Math.floor(Math.random()*candidates.length)]:candidates[0];state.playing=true;updatePlayer();renderResults();toast(action==='shuffle'?'Shuffling your library':'Playing your library');}
  if(action==='toggle'){state.playing=!state.playing;updatePlayer();}
  if(action==='current-like')toggleFavorite(state.current);
  if(action==='close-sheet')sheet.close();
  if(action==='now-playing'){openSheet(`<div class="track-detail now-playing">${art(state.current.art)}<small>NOW PLAYING</small><h2>${esc(state.current.title)}</h2><p>${esc(state.current.artist)}</p></div><button class="sheet-option" data-action="toggle">${icon(state.playing?'pause':'play')}${state.playing?'Pause':'Play'}</button>`,button);}
  if(action==='options'){openSheet(`<h2>Sort library</h2>${[['recent','Recently added'],['az','Title A–Z'],['za','Title Z–A'],['duration','Duration']].map(([value,label])=>`<button class="sheet-option" data-set-sort="${value}">${icon(state.sort===value?'check':'sort')}${label}</button>`).join('')}`,button);}
});
renderResults();
updatePlayer();
