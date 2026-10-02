const selected = new Set();
const grid = document.getElementById('concept-grid');
const viewer = document.getElementById('viewer');
const viewerBody = document.getElementById('viewer-body');
const viewerTitle = document.getElementById('viewer-title');
let lastTrigger;
const device = (c) => `<div class="viewer-device"><iframe src="concept.html?concept=${c.id}" title="${c.name} interactive library prototype"></iframe></div>`;
grid.innerHTML = LUNO_CONCEPTS.map(c => `<article class="concept-card" data-id="${c.id}">
  <div class="card-caption"><div><span class="concept-number">CONCEPT ${c.id}</span><h2>${c.name}</h2><p>${c.type}</p></div><label class="select-label"><input type="checkbox" value="${c.id}" aria-label="Compare ${c.name}">Compare</label></div>
  <button class="preview-launch" data-open="${c.id}" aria-label="Explore ${c.name}"><div class="preview-viewport"><iframe src="concept.html?concept=${c.id}&preview=1" tabindex="-1" title="${c.name} preview" loading="lazy"></iframe></div></button>
  <div class="card-bottom"><p>${c.summary}</p><button data-open="${c.id}">Explore ↗</button></div></article>`).join('');
const resize = new ResizeObserver(entries => entries.forEach(({target, contentRect}) => {
  target.style.setProperty('--scale', Math.min((contentRect.width - 44) / 390, .84));
}));
document.querySelectorAll('.concept-card').forEach(card => resize.observe(card));
function openConcept(id, trigger) {
  const c = LUNO_CONCEPTS.find(c => c.id === id);
  lastTrigger = trigger;
  viewer.classList.remove('compare-mode');
  viewerTitle.textContent = `Concept ${c.id} · ${c.name}`;
  viewerBody.innerHTML = `${device(c)}<section class="concept-notes"><p class="eyebrow">YOUR LIBRARY / ${c.id}</p><h2>${c.name}</h2><p>${c.summary}</p><h3>Background</h3><p>${c.background}</p><h3>Search</h3><p>${c.search}</p><h3>Controls</h3><p>${c.controls}</p><a href="concept.html?concept=${c.id}" target="_blank" rel="noopener">Open full screen ↗</a></section>`;
  viewer.showModal();
  document.getElementById('viewer-close').focus();
}
grid.addEventListener('click', e => {
  const trigger = e.target.closest('[data-open]');
  if (trigger) openConcept(trigger.dataset.open, trigger);
});
grid.addEventListener('change', e => {
  if (e.target.type !== 'checkbox') return;
  if (e.target.checked && selected.size === 2) { e.target.checked = false; return; }
  if (e.target.checked) selected.add(e.target.value); else selected.delete(e.target.value);
  document.getElementById('compare-count').textContent = `${selected.size} / 2`;
});
document.getElementById('compare-button').addEventListener('click', e => {
  if (selected.size < 2) {
    const first = grid.querySelector('input:not(:checked)');
    first?.focus();
    document.getElementById('compare-count').textContent = 'Select two';
    return;
  }
  lastTrigger = e.currentTarget;
  viewer.classList.add('compare-mode');
  viewerTitle.textContent = 'Compare library concepts';
  viewerBody.innerHTML = [...selected].map(id => {
    const c = LUNO_CONCEPTS.find(c => c.id === id);
    return `<section class="compare-preview"><h2>${c.id} / ${c.name}</h2>${device(c)}<a href="concept.html?concept=${c.id}" target="_blank" rel="noopener">Open full screen ↗</a></section>`;
  }).join('');
  viewer.showModal();
  document.getElementById('viewer-close').focus();
});
document.getElementById('viewer-close').addEventListener('click', () => viewer.close());
viewer.addEventListener('click', e => { if (e.target === viewer) viewer.close(); });
viewer.addEventListener('close', () => { viewerBody.innerHTML = ''; lastTrigger?.focus(); });
