const loops = new Map();

function getLoop(id) {
  if (loops.has(id)) return loops.get(id);
  const audio = new Audio('shredder_loop.ogg');
  audio.loop = true;
  audio.preload = 'auto';
  audio.volume = 0;
  const state = { audio, target: 0 };
  loops.set(id, state);
  return state;
}

window.addEventListener('message', (event) => {
  if (event.data?.action !== 'syncShredderAudio') return;
  const active = new Set();
  for (const source of event.data.sources || []) {
    const id = String(source.id);
    active.add(id);
    const state = getLoop(id);
    state.target = Math.max(0, Math.min(1, Number(source.volume) || 0));
    if (state.target > 0 && state.audio.paused) state.audio.play().catch(() => {});
  }
  for (const [id, state] of loops) {
    if (!active.has(id)) state.target = 0;
  }
});

setInterval(() => {
  for (const [id, state] of loops) {
    state.audio.volume += (state.target - state.audio.volume) * 0.28;
    if (state.target === 0 && state.audio.volume < 0.004) {
      state.audio.pause();
      state.audio.currentTime = 0;
      loops.delete(id);
    }
  }
}, 80);
