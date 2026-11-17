const minute = 60_000;
const day = 1440 * minute;
const beijingOffset = 480 * minute;
const storageKey = 'codex-display.reminders.v1';

function dayStart(at) {
  return Math.floor((at + beijingOffset) / day) * day - beijingOffset;
}

function minutesAt(time) {
  const [hours, minutes] = time.split(':').map(Number);
  return hours * 60 + minutes;
}

function isoInBeijing(at) {
  return new Date(at + beijingOffset).toISOString();
}

export function defaultReminders(now = Date.now()) {
  return {
    water: { enabled: true, start: '09:00', end: '19:00', interval: 120, since: now, acknowledged: 0 },
    tasks: [],
  };
}

export function firstDailyOccurrence(time, now) {
  const today = dayStart(now) + minutesAt(time) * minute;
  return today > now ? today : today + day;
}

export function reminderStatus(plan, now = Date.now()) {
  const due = [];
  const upcoming = [];
  const water = plan.water;

  if (water.enabled) {
    const start = dayStart(now) + minutesAt(water.start) * minute;
    const end = dayStart(now) + minutesAt(water.end) * minute;
    const interval = water.interval * minute;
    const latest = start + Math.floor((Math.min(now, end) - start) / interval) * interval;
    if (latest >= start && latest >= water.since && latest > water.acknowledged) {
      due.push({ id: 'water', title: '喝水', at: latest });
    }
    const next = start + Math.max(0, Math.floor((now - start) / interval) + 1) * interval;
    upcoming.push({ title: '喝水', at: next <= end ? next : start + day });
  }

  for (const task of plan.tasks) {
    if (task.startsAt > now) {
      upcoming.push({ title: task.title, at: task.startsAt });
      continue;
    }
    const elapsedDays = task.daily ? Math.floor((now - task.startsAt) / day) : 0;
    const latest = task.startsAt + elapsedDays * day;
    if (latest > task.acknowledged) {
      due.push({ id: task.id, title: task.title, at: latest });
    }
    if (task.daily) {
      upcoming.push({ title: task.title, at: latest + day });
    }
  }

  return {
    due: due.sort((a, b) => a.at - b.at),
    next: upcoming.sort((a, b) => a.at - b.at)[0],
  };
}

export function acknowledge(plan, reminder) {
  if (reminder.id === 'water') {
    plan.water.acknowledged = reminder.at;
    return;
  }
  const index = plan.tasks.findIndex(task => task.id === reminder.id);
  if (index === -1) {
    return;
  }
  if (plan.tasks[index].daily) {
    plan.tasks[index].acknowledged = reminder.at;
  } else {
    plan.tasks.splice(index, 1);
  }
}

function shortDate(at, now) {
  const iso = isoInBeijing(at);
  const time = iso.slice(11, 16);
  const offset = (dayStart(at) - dayStart(now)) / day;
  if (offset === 0) {
    return time;
  }
  if (offset === 1) {
    return `明天 ${time}`;
  }
  return `${iso.slice(0, 10)} ${time}`;
}

function mountReminders() {
  const el = id => document.getElementById(id);
  const strip = el('reminder-strip');
  const caption = el('reminder-caption');
  const confirmButton = el('reminder-confirm');
  const dialog = el('reminder-settings');
  const repeat = el('task-repeat');
  const dateInput = el('task-date');
  const taskTime = el('task-time');
  const taskTitle = el('task-title');
  let plan = defaultReminders();
  let activeReminder;
  let canSave = true;

  function readPlan(raw) {
    if (raw) {
      const stored = JSON.parse(raw);
      if (stored?.water && Array.isArray(stored.tasks)) {
        plan = stored;
      }
    }
  }

  function save() {
    try {
      localStorage.setItem(storageKey, JSON.stringify(plan));
      canSave = true;
    } catch {
      canSave = false;
    }
    el('reminder-storage-error').hidden = canSave;
  }

  function render() {
    const now = Date.now();
    const { due, next } = reminderStatus(plan, now);
    activeReminder = due[0];
    strip.dataset.due = Boolean(activeReminder);
    confirmButton.hidden = !activeReminder;
    let text = '暂无提醒';
    if (activeReminder) {
      const extra = due.length > 1 ? ` · 另有 ${due.length - 1} 项` : '';
      text = `到点了 · ${activeReminder.title}${extra}`;
    } else if (next) {
      text = `下次提醒 · ${shortDate(next.at, now)} ${next.title}`;
    }
    if (caption.textContent !== text) {
      caption.textContent = text;
    }
  }

  function renderTasks() {
    const list = el('reminder-task-list');
    list.replaceChildren();
    el('reminder-no-tasks').hidden = plan.tasks.length > 0;
    for (const task of plan.tasks) {
      const item = document.createElement('li');
      const description = document.createElement('span');
      const iso = isoInBeijing(task.startsAt);
      const time = task.daily ? `每天 ${iso.slice(11, 16)}` : iso.slice(0, 16).replace('T', ' ');
      description.textContent = `${task.title} · ${time}`;
      const remove = document.createElement('button');
      remove.type = 'button';
      remove.textContent = '删除';
      remove.setAttribute('aria-label', `删除 ${task.title}`);
      remove.addEventListener('click', () => {
        plan.tasks = plan.tasks.filter(entry => entry.id !== task.id);
        save();
        renderTasks();
        render();
      });
      item.append(description, remove);
      list.append(item);
    }
  }

  function updateDateField() {
    const daily = repeat.value === 'daily';
    el('task-date-field').hidden = daily;
    dateInput.required = !daily;
    dateInput.disabled = daily;
  }

  try {
    readPlan(localStorage.getItem(storageKey));
  } catch {
    // The in-memory plan still works when browser storage is unavailable.
  }
  save();

  el('reminder-open').addEventListener('click', () => {
    el('water-enabled').checked = plan.water.enabled;
    el('water-start').value = plan.water.start;
    el('water-end').value = plan.water.end;
    el('water-interval').value = plan.water.interval;
    el('water-feedback').textContent = '';
    el('task-feedback').textContent = '';
    const nextMinute = Math.ceil((Date.now() + 1) / minute) * minute;
    const iso = isoInBeijing(nextMinute);
    dateInput.value = iso.slice(0, 10);
    taskTime.value = iso.slice(11, 16);
    updateDateField();
    renderTasks();
    dialog.showModal();
  });
  el('reminder-close').addEventListener('click', () => dialog.close());
  repeat.addEventListener('change', updateDateField);

  el('water-form').addEventListener('submit', event => {
    event.preventDefault();
    const enabled = el('water-enabled').checked;
    const start = el('water-start').value;
    const end = el('water-end').value;
    const interval = el('water-interval').valueAsNumber;
    const feedback = el('water-feedback');
    if (minutesAt(end) < minutesAt(start)) {
      feedback.textContent = '结束时间需在开始时间之后，或与开始时间相同。';
      return;
    }
    const water = plan.water;
    if (enabled !== water.enabled || start !== water.start || end !== water.end || interval !== water.interval) {
      plan.water = { enabled, start, end, interval, since: Date.now(), acknowledged: 0 };
      save();
      render();
    }
    feedback.textContent = enabled ? '已保存喝水时段。' : '已关闭喝水提醒。';
  });

  el('task-form').addEventListener('submit', event => {
    event.preventDefault();
    const title = taskTitle.value.trim();
    const time = taskTime.value;
    const daily = repeat.value === 'daily';
    const now = Date.now();
    const startsAt = daily ? firstDailyOccurrence(time, now) : Date.parse(`${dateInput.value}T${time}:00+08:00`);
    const feedback = el('task-feedback');
    if (!title) {
      feedback.textContent = '请输入待办名称。';
      return;
    }
    if (!Number.isFinite(startsAt) || (!daily && startsAt <= now)) {
      feedback.textContent = '请选择晚于当前时间的日期和时间。';
      return;
    }
    plan.tasks.push({ id: crypto.randomUUID(), title, daily, startsAt, acknowledged: 0 });
    taskTitle.value = '';
    save();
    renderTasks();
    render();
    feedback.textContent = `已添加 · ${shortDate(startsAt, now)}`;
  });

  confirmButton.addEventListener('click', () => {
    if (activeReminder) {
      acknowledge(plan, activeReminder);
      save();
      render();
    }
  });

  window.addEventListener('storage', event => {
    if (event.key === storageKey && event.newValue) {
      readPlan(event.newValue);
      renderTasks();
      render();
    }
  });
  document.addEventListener('visibilitychange', render);
  render();
  setInterval(render, 1000);
}

if (typeof document !== 'undefined') {
  mountReminders();
}
