// Libreta for drivers in the browser (iPhone, or any phone without the Android app).
// Same Firestore data and rules as the Android app: drivers/{id}, drivers/{id}/shifts/{yyyy-MM-ddTHH:mm}, …
import { initializeApp } from "https://www.gstatic.com/firebasejs/11.0.2/firebase-app.js";
import {
  getAuth, signInAnonymously, onAuthStateChanged, signOut, connectAuthEmulator,
} from "https://www.gstatic.com/firebasejs/11.0.2/firebase-auth.js";
import {
  initializeFirestore, persistentLocalCache, persistentMultipleTabManager, connectFirestoreEmulator,
  doc, getDoc, getDocFromServer, getDocs, setDoc, updateDoc, addDoc, deleteDoc, collection, query, where, orderBy, onSnapshot, serverTimestamp,
} from "https://www.gstatic.com/firebasejs/11.0.2/firebase-firestore.js";

const LOCAL = location.hostname === "localhost" || location.hostname === "127.0.0.1";

const app = initializeApp({
  apiKey: "AIzaSyBX0YmxiWuFI3CB-kJcg7dDx0Lpl_4bwyw",
  authDomain: "libreta-sklad.firebaseapp.com",
  projectId: LOCAL ? "demo-libreta" : "libreta-sklad",
  storageBucket: "libreta-sklad.firebasestorage.app",
  messagingSenderId: "37346689865",
  appId: "1:37346689865:web:94cbb0f563ab7f1dbe1098",
});
const auth = getAuth(app);
// Offline cache: shifts started without signal show at once and sync later.
const db = initializeFirestore(app, { localCache: persistentLocalCache({ tabManager: persistentMultipleTabManager() }) });
if (LOCAL) {
  connectAuthEmulator(auth, "http://127.0.0.1:9099", { disableWarnings: true });
  connectFirestoreEmulator(db, "127.0.0.1", 8080);
}

const OVERDUE_MINUTES = 12 * 60;
const DRIVER_KEY = "libreta.driverId";
const $ = (id) => document.getElementById(id);

// ---------- time helpers (local "yyyy-MM-ddTHH:mm" strings, like the Android app) ----------

const pad = (n) => String(n).padStart(2, "0");
const dateKey = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const timeKey = (d) => `${dateKey(d)}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
const parseTime = (s) => (s ? new Date(`${s}:00`) : null);
const parseDate = (s) => new Date(`${s}T00:00:00`);
const hhmm = (d) => `${pad(d.getHours())}:${pad(d.getMinutes())}`;
const hm = (minutes) => `${Math.floor(minutes / 60)}:${pad(minutes % 60)}`;
const minutesBetween = (a, b) => Math.max(0, Math.round((b - a) / 60000));
const DAYS = ["ne", "po", "ut", "st", "št", "pi", "so"];
const MONTHS = ["január", "február", "marec", "apríl", "máj", "jún", "júl", "august", "september", "október", "november", "december"];
const day = (d) => `${DAYS[d.getDay()]} ${d.getDate()}.${d.getMonth() + 1}.`;
const count = (n, one, few, many) => `${n} ${n === 1 ? one : n >= 2 && n <= 4 ? few : many}`;

const ABSENCES = { VACATION: "Dovolenka", SICK: "PN", CARE: "OČR", COMP_OFF: "Náhradné voľno", UNPAID: "Neplatené voľno" };
const STATUSES = { PENDING: "čaká na schválenie", REJECTED: "zamietnutá" };
// Absences entered by the owner have no status – they count like approved ones.
const approved = (a) => (a.status || "APPROVED") === "APPROVED";

/** Slovak days off – the same list as Payroll.kt in the Android app. */
function holidays(year) {
  const fixed = ["01-01", "01-06", "05-01", "05-08", "07-05", "08-29", "09-01", "09-15", "11-01", "11-17", "12-24", "12-25", "12-26"]
    .filter((md) => !(year >= 2025 && md === "09-01") && !(year >= 2026 && md === "11-17")
      && !(year === 2026 && (md === "05-08" || md === "09-15")));
  const easter = easterSunday(year);
  const shift = (days) => dateKey(new Date(easter.getFullYear(), easter.getMonth(), easter.getDate() + days));
  return new Set([...fixed.map((md) => `${year}-${md}`), shift(-2), shift(1)]);
}

function easterSunday(y) {
  const a = y % 19, b = Math.floor(y / 100), c = y % 100, d = Math.floor(b / 4), e = b % 4;
  const f = Math.floor((b + 8) / 25), g = Math.floor((b - f + 1) / 3), h = (19 * a + b - d - g + 15) % 30;
  const i = Math.floor(c / 4), k = c % 4, l = (32 + 2 * e + 2 * i - h - k) % 7, m = Math.floor((a + 11 * h + 22 * l) / 451);
  const month = Math.floor((h + l - 7 * m + 114) / 31), dayOfMonth = ((h + l - 7 * m + 114) % 31) + 1;
  return new Date(y, month - 1, dayOfMonth);
}

/** Working days (Mon–Fri, not a day off) from first to last (Date, inclusive). */
function workingDays(first, last) {
  let n = 0;
  for (const d = new Date(first); d <= last; d.setDate(d.getDate() + 1)) {
    const weekday = d.getDay();
    if (weekday !== 0 && weekday !== 6 && !holidays(d.getFullYear()).has(dateKey(d))) n++;
  }
  return n;
}

function workingDaysIn(absence, year, month) {
  return workingDaysBetween(absence, new Date(year, month, 1), new Date(year, month + 1, 0));
}

function workingDaysBetween(absence, first, last) {
  return workingDays(new Date(Math.max(parseDate(absence.from), first)), new Date(Math.min(parseDate(absence.to), last)));
}

/**
 * Vacation days left this year – the same as Vacation.kt: the owner's balance from the payslip (or 0 on 31 December),
 * plus the yearly entitlement for every new year, minus approved vacations after that date. null for workers on an agreement.
 */
function vacationLeft(year) {
  if (!driver || driver.type === "PART_TIME") return null;
  const start = vacationStart(year);
  const base = driver.vacationAsOf ? driver.vacationBalance ?? 0 : 0;
  const first = new Date(start.getFullYear(), start.getMonth(), start.getDate() + 1);
  const used = vacationAbsences.filter((a) => a.type === "VACATION" && approved(a))
    .reduce((sum, a) => sum + workingDaysBetween(a, first, new Date(year, 11, 31)), 0);
  return base + (driver.vacationPerYear ?? 20) * (year - start.getFullYear()) - used;
}

function vacationStart(year) {
  return driver?.vacationAsOf ? parseDate(driver.vacationAsOf) : new Date(year - 1, 11, 31);
}

/** 4.5 -> "4,5" */
const decimal = (n) => String(Math.round(n * 100) / 100).replace(".", ",");

const shiftOf = (snap) => {
  const s = snap.data();
  const start = parseTime(s.start);
  const end = parseTime(s.end);
  const total = end ? minutesBetween(start, end) : 0;
  return {
    id: snap.id, start, end, note: s.note || "", vehicle: s.vehicle || "",
    kmStart: s.kmStart ?? null, kmEnd: s.kmEnd ?? null, addedLater: !!s.enteredAt,
    worked: end ? Math.max(0, total) : 0,
  };
};

// ---------- state ----------

let driverId = localStorage.getItem(DRIVER_KEY);
let driver = null;
let open = null;
let vehicles = [];
/** Worked minutes of today's finished shifts. */
let todaysMinutes = 0;
let viewMonth = new Date(new Date().getFullYear(), new Date().getMonth(), 1);
let monthShifts = [];
let monthAbsences = [];
let vacationAbsences = [];
let vacationFrom = null;
let unsubscribers = [];
let monthUnsubscribers = [];
let vacationUnsubscribe = null;

const shiftsRef = () => collection(db, "drivers", driverId, "shifts");

// ---------- join ----------

function showJoin(message) {
  stopListening();
  $("main").hidden = true;
  $("join").hidden = false;
  const iOS = /iPhone|iPad|iPod/.test(navigator.userAgent) && !navigator.standalone;
  $("ios-hint").hidden = !iOS;
  setJoinStatus(message);
}

function setJoinStatus(text) {
  $("join-status").textContent = text || "";
  $("join-status").hidden = !text;
}

$("join-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const code = $("code").value.toUpperCase().replace(/[^A-Z0-9]/g, "");
  if (code.length !== 8) return setJoinStatus("Kód má 8 znakov – dostaneš ho od majiteľa.");
  const button = event.submitter;
  button.disabled = true;
  setJoinStatus("");
  try {
    if (!auth.currentUser) await signInAnonymously(auth);
    const codeDoc = await getDoc(doc(db, "joinCodes", code));
    if (!codeDoc.exists()) throw new Error("Neplatný kód");
    const id = codeDoc.data().driverId;
    // The code proves the driver: it takes the record over from an old phone (e.g. after Safari lost the sign-in).
    await updateDoc(doc(db, "drivers", id), { uid: auth.currentUser.uid, linkCode: code });
    driverId = id;
    localStorage.setItem(DRIVER_KEY, id);
    showMain();
  } catch (e) {
    setJoinStatus(e.message === "Neplatný kód" ? e.message
      : "Nepodarilo sa pripojiť. Si pripojený na internet?");
  } finally {
    button.disabled = false;
  }
});

// ---------- main screen ----------

function stopListening() {
  [...unsubscribers, ...monthUnsubscribers, vacationUnsubscribe].filter(Boolean).forEach((u) => u());
  unsubscribers = [];
  monthUnsubscribers = [];
  vacationUnsubscribe = null;
  vacationFrom = null;
}

/** Absences after the date the vacation balance counts from; listens again when the owner changes that date. */
function listenVacation() {
  const from = dateKey(vacationStart(new Date().getFullYear()));
  if (from === vacationFrom) return;
  vacationUnsubscribe?.();
  vacationFrom = from;
  vacationUnsubscribe = onSnapshot(query(collection(db, "drivers", driverId, "absences"), where("to", ">", from)), (snap) => {
    vacationAbsences = snap.docs.map((d) => ({ id: d.id, ...d.data() }));
    render();
  });
}

function showMain() {
  $("join").hidden = true;
  $("main").hidden = false;
  stopListening();
  unsubscribers.push(onSnapshot(doc(db, "drivers", driverId), (snap) => {
    if (!snap.exists()) return unlinked();
    driver = snap.data();
    $("driver-name").textContent = driver.name || "Libreta";
    listenVacation();
    render();
    if (!driver.phone && !sessionStorage.getItem("askedPhone")) {
      sessionStorage.setItem("askedPhone", "1");
      showProfile();
    }
  }, accessDenied));
  unsubscribers.push(onSnapshot(query(shiftsRef(), where("end", "==", null)), (snap) => {
    const shifts = snap.docs.map(shiftOf).sort((a, b) => b.start - a.start);
    open = shifts[0] || null;
    render();
  }));
  unsubscribers.push(onSnapshot(query(shiftsRef(), where("start", ">=", `${dateKey(new Date())}T00:00`)), (snap) => {
    todaysMinutes = snap.docs.map(shiftOf).filter((s) => s.end && dateKey(s.start) === dateKey(new Date()))
      .reduce((sum, s) => sum + s.worked, 0);
    render();
  }));
  unsubscribers.push(onSnapshot(collection(db, "vehicles"), (snap) => {
    vehicles = snap.docs.map((d) => d.data()).filter((v) => v.active !== false).sort((a, b) => a.plate.localeCompare(b.plate));
  }));
  showMonth(viewMonth);
}

/**
 * The driver record was refused. Right after signing in this can be a passing glitch, so ask the server first:
 * still linked -> listen again (at most 3 times), really unlinked -> back to the code screen.
 */
let restarts = 0;
function accessDenied(error) {
  stopListening();
  setTimeout(async () => {
    try {
      const snap = await getDocFromServer(doc(db, "drivers", driverId));
      if (snap.exists() && snap.data().uid === auth.currentUser?.uid && restarts++ < 3) return showMain();
      if (snap.exists() && snap.data().uid === auth.currentUser?.uid) return showError(error);
    } catch (e) {
      if (e.code !== "permission-denied") return showError(e);
    }
    unlinked();
  }, 1500);
}

function unlinked() {
  localStorage.removeItem(DRIVER_KEY);
  driverId = null;
  signOut(auth).finally(() => showJoin("Tento telefón bol odpojený. Zadaj znova kód od majiteľa."));
}

function render() {
  if (!driver) return;
  const button = $("shift-button");
  const now = new Date();
  const left = vacationLeft(now.getFullYear());
  const waiting = vacationAbsences.filter((a) => a.status === "PENDING").reduce((sum, a) => sum + workingDays(parseDate(a.from), parseDate(a.to)), 0);
  $("vacation-button").hidden = left == null || driver.active === false;
  $("add-shift-button").hidden = driver.active === false;
  $("vacation-left").textContent = left == null ? "" : `Dovolenka: zostáva ${decimal(left)} d${waiting ? ` · čaká ${waiting} d` : ""}`;
  $("overdue").hidden = !(open && minutesBetween(open.start, now) >= OVERDUE_MINUTES);
  if (open) {
    $("status").textContent = `Na smene ${hm(minutesBetween(open.start, now))} h`;
    const today = dateKey(open.start) === dateKey(now);
    $("since").textContent = `od ${today ? "" : `${open.start.getDate()}.${open.start.getMonth() + 1}. `}${hhmm(open.start)}`
      + (open.vehicle ? ` · ${open.vehicle}` : "");
    button.textContent = "■  Ukončiť smenu";
    button.className = "shift-button stop";
    button.disabled = false;
  } else if (driver.active === false) {
    $("status").textContent = "Účet je neaktívny";
    $("since").textContent = "Ozvi sa majiteľovi.";
    button.textContent = "▶  Začať smenu";
    button.className = "shift-button start";
    button.disabled = true;
  } else {
    $("status").textContent = "Nie si na smene";
    $("since").textContent = todaysMinutes ? `Dnes odpracované ${hm(todaysMinutes)} h` : "";
    button.textContent = "▶  Začať smenu";
    button.className = "shift-button start";
    button.disabled = false;
  }
}

setInterval(render, 30000);

$("shift-button").addEventListener("click", () => (open ? endShift() : startShift()));

async function startShift() {
  let vehicle = "";
  let kmStart = null;
  if (vehicles.length) {
    const choices = [...vehicles.map((v) => v.plate + (v.name ? ` · ${v.name}` : "")), "Bez vozidla"];
    const result = await dialog({
      title: "Ktorým vozidlom ideš?",
      body: `<div class="choices">${choices.map((c, i) => `<label class="choice"><input type="radio" name="vehicle" value="${i}" ${i === 0 ? "checked" : ""}>${escape(c)}</label>`).join("")}</div>
        <label class="field"><span>Stav tachometra (km) – nepovinné</span><input name="km" inputmode="numeric" pattern="[0-9]*"></label>`,
      ok: "Začať smenu",
    });
    if (!result) return;
    vehicle = vehicles[Number(result.get("vehicle"))]?.plate || "";
    kmStart = toInt(result.get("km"));
  }
  const now = new Date();
  // Stored under its start, so a driver may have several shifts a day (older versions used the date).
  setDoc(doc(shiftsRef(), timeKey(now)), {
    start: timeKey(now), end: null, breakMin: 0, note: "", vehicle, kmStart, startLoggedAt: serverTimestamp(),
  }).catch(showError);
}

async function endShift() {
  const shift = open;
  const now = new Date();
  const result = await dialog({
    title: "Ukončiť smenu",
    message: `${hhmm(shift.start)} – ${hhmm(now)} · ${hm(minutesBetween(shift.start, now))} h`,
    body: (shift.vehicle ? `<label class="field"><span>Km na konci${shift.kmStart != null ? ` (začiatok ${shift.kmStart})` : ""}</span><input name="km" inputmode="numeric" pattern="[0-9]*"></label>` : ""),
    ok: "Ukončiť",
    validate: (data) => {
      const km = toInt(data.get("km"));
      if (km != null && shift.kmStart != null && km < shift.kmStart) return `Km na konci sú menej ako na začiatku (${shift.kmStart}).`;
      return null;
    },
  });
  if (!result) return;
  const update = { end: timeKey(new Date()), endLoggedAt: serverTimestamp() };
  const km = toInt(result.get("km"));
  if (km != null) update.kmEnd = km;
  updateDoc(doc(shiftsRef(), shift.id), update).catch(showError);
}

// ---------- forgotten past shift ----------

$("add-shift-button").addEventListener("click", addPastShift);

/**
 * A past shift the driver forgot to start – like Repo.addPastShift in the Android app: stored under its start,
 * refused if it overlaps another shift, marked with enteredAt so the owner sees it was added afterwards.
 * End before start = next day.
 */
async function addPastShift() {
  const yesterday = new Date();
  yesterday.setDate(yesterday.getDate() - 1);
  const build = (data) => {
    const date = String(data.get("date") || "");
    const start = parseTime(`${date}T${data.get("start")}`);
    const end = parseTime(`${date}T${data.get("end")}`);
    if (!date || !start || !end || isNaN(start) || isNaN(end)) return null;
    if (end <= start) end.setDate(end.getDate() + 1);
    return { start, end };
  };
  const plates = vehicles.map((v) => v.plate);
  const result = await dialog({
    title: "Doplniť smenu",
    message: "Smena, ktorú si zabudol začať v aplikácii. Majiteľ uvidí, že je doplnená dodatočne.",
    body: `<label class="field"><span>Deň</span><input name="date" type="date" required max="${dateKey(new Date())}" value="${dateKey(yesterday)}"></label>
      <div class="field-row">
        <label class="field"><span>Od</span><input name="start" type="time" required value="06:00"></label>
        <label class="field"><span>Do</span><input name="end" type="time" required value="14:00"></label>
      </div>`
      + (plates.length ? `<label class="field"><span>Vozidlo</span><select name="vehicle"><option value="">Bez vozidla</option>${plates.map((p) => `<option>${escape(p)}</option>`).join("")}</select></label>
      <div class="field-row">
        <label class="field"><span>Km začiatok</span><input name="kmStart" inputmode="numeric" pattern="[0-9]*"></label>
        <label class="field"><span>Km koniec</span><input name="kmEnd" inputmode="numeric" pattern="[0-9]*"></label>
      </div>` : "")
      + `<label class="field"><span>Poznámka (napr. trasa)</span><input name="note" maxlength="200"></label>`,
    ok: "Uložiť",
    validate: (data) => {
      const s = build(data);
      if (!s) return "Vyplň deň a časy.";
      if (dateKey(s.start) > dateKey(new Date())) return "Smenu v budúcnosti nemôžeš doplniť.";
      if (s.end > new Date()) return "Smena ešte neskončila – dnešnú začni tlačidlom Začať smenu.";
      if (minutesBetween(s.start, s.end) <= 0) return "Skontroluj časy – odpracovaný čas musí byť väčší ako 0.";
      const kmStart = toInt(data.get("kmStart")), kmEnd = toInt(data.get("kmEnd"));
      if (kmStart != null && kmEnd != null && kmEnd < kmStart) return "Km na konci sú menej ako na začiatku.";
      return null;
    },
  });
  if (!result) return;
  const s = build(result);
  try {
    // A shift started up to a day earlier may still run into this one (e.g. a night shift).
    const from = new Date(s.start);
    from.setDate(from.getDate() - 1);
    const snap = await getDocs(query(shiftsRef(), where("start", ">=", timeKey(from)), where("start", "<", timeKey(s.end))));
    const clash = snap.docs.map(shiftOf).find((o) => o.start < s.end && (o.end || new Date()) > s.start);
    if (clash) {
      return dialog({
        title: "Smena sa prekrýva",
        message: `Prekrýva sa so smenou ${day(clash.start)} ${hhmm(clash.start)} – ${clash.end ? hhmm(clash.end) : "…"}. Opravu urobí majiteľ.`,
      });
    }
  } catch {
    return showError(new Error("Nepodarilo sa overiť tvoje smeny. Si pripojený na internet?"));
  }
  setDoc(doc(shiftsRef(), timeKey(s.start)), {
    start: timeKey(s.start), end: timeKey(s.end), breakMin: 0, note: String(result.get("note") || "").trim().slice(0, 200),
    vehicle: String(result.get("vehicle") || ""), kmStart: toInt(result.get("kmStart")), kmEnd: toInt(result.get("kmEnd")),
    enteredAt: serverTimestamp(),
  }).catch(() => showError(new Error("Smenu sa nepodarilo uložiť – mesiac je možno už uzavretý. Ozvi sa majiteľovi.")));
}

// ---------- month overview ----------

function showMonth(month) {
  viewMonth = month;
  monthUnsubscribers.forEach((u) => u());
  monthShifts = [];
  monthAbsences = [];
  const from = dateKey(month);
  const next = new Date(month.getFullYear(), month.getMonth() + 1, 1);
  monthUnsubscribers = [
    onSnapshot(query(shiftsRef(), where("start", ">=", `${from}T00:00`), where("start", "<", `${dateKey(next)}T00:00`), orderBy("start")), (snap) => {
      monthShifts = snap.docs.map(shiftOf);
      renderMonth();
    }),
    onSnapshot(query(collection(db, "drivers", driverId, "absences"), where("to", ">=", from)), (snap) => {
      const last = dateKey(new Date(month.getFullYear(), month.getMonth() + 1, 0));
      monthAbsences = snap.docs.map((d) => ({ id: d.id, ...d.data() })).filter((a) => a.from <= last);
      renderMonth();
    }),
  ];
  renderMonth();
}

function renderMonth() {
  const y = viewMonth.getFullYear();
  const m = viewMonth.getMonth();
  $("month-title").textContent = `${MONTHS[m][0].toUpperCase()}${MONTHS[m].slice(1)} ${y}`;
  const finished = monthShifts.filter((s) => s.end);
  const minutes = finished.reduce((sum, s) => sum + s.worked, 0);
  const days = new Set(finished.map((s) => dateKey(s.start))).size;
  const km = finished.reduce((sum, s) => sum + (s.kmStart != null && s.kmEnd != null && s.kmEnd >= s.kmStart ? s.kmEnd - s.kmStart : 0), 0);
  const absenceDays = {};
  for (const a of monthAbsences.filter(approved)) absenceDays[a.type] = (absenceDays[a.type] || 0) + workingDaysIn(a, y, m);

  $("total-hours").textContent = `${hm(minutes)} h`;
  $("totals").textContent = [
    `${count(days, "deň", "dni", "dní")} · ${count(finished.length, "smena", "smeny", "smien")}${days ? ` · priemer ${hm(Math.round(minutes / days))} h/deň` : ""}`,
    Object.entries(absenceDays).filter(([, n]) => n > 0).map(([t, n]) => `${ABSENCES[t] || t} ${count(n, "deň", "dni", "dní")}`).join(" · "),
    km ? `Najazdené: ${km} km` : "",
  ].filter(Boolean).join("\n");
  const unfinished = monthShifts.length - finished.length;
  $("month-warning").textContent = unfinished ? "Neukončená smena sa do súčtu nezapočítava." : "";
  $("month-warning").hidden = !unfinished;

  const entries = [
    ...monthShifts.map((s) => ({ date: s.start, html: shiftRow(s) })),
    ...monthAbsences.map((a) => ({ date: parseDate(a.from), html: absenceRow(a, y, m) })),
  ].sort((a, b) => b.date - a.date);
  $("entries").innerHTML = entries.map((e) => e.html).join("");
  $("entries").querySelectorAll("[data-request]").forEach((li) => {
    li.addEventListener("click", () => ownRequest(monthAbsences.find((a) => a.id === li.dataset.request)));
  });
  $("entries").hidden = !entries.length;
  $("empty").hidden = entries.length > 0;
}

function shiftRow(s) {
  const sub = [
    s.end ? null : "prebieha",
    s.vehicle ? s.vehicle + (s.kmStart != null && s.kmEnd != null ? ` · ${s.kmEnd - s.kmStart} km` : "") : null,
    s.note || null,
    s.addedLater ? "✎ doplnená dodatočne" : null,
  ].filter(Boolean).join(" · ");
  return `<li><div class="text-col"><div class="title">${day(s.start)}  ${hhmm(s.start)} – ${s.end ? hhmm(s.end) : "…"}</div>`
    + `${sub ? `<div class="sub">${escape(sub)}</div>` : ""}</div><div class="value">${s.end ? `${hm(s.worked)} h` : ""}</div></li>`;
}

function absenceRow(a, y, m) {
  const from = parseDate(a.from);
  const to = parseDate(a.to);
  const range = a.from === a.to ? day(from) : `${day(from)} – ${day(to)}`;
  const title = (ABSENCES[a.type] || a.type) + (approved(a) ? "" : ` · ${STATUSES[a.status] || a.status}`);
  return `<li${approved(a) ? "" : ` class="request" data-request="${escape(a.id)}"`}><div class="text-col"><div class="title">${escape(title)}</div>`
    + `<div class="sub">${escape([range, a.note].filter(Boolean).join(" · "))}</div></div>`
    + `<div class="value">${count(workingDaysIn(a, y, m), "deň", "dni", "dní")}</div></li>`;
}

// ---------- vacation requests ----------

$("vacation-button").addEventListener("click", requestVacation);

async function requestVacation() {
  const tomorrow = new Date();
  tomorrow.setDate(tomorrow.getDate() + 1);
  const left = vacationLeft(new Date().getFullYear());
  const days = (data) => {
    const from = String(data.get("from") || ""), to = String(data.get("to") || "");
    return from && to && from <= to ? workingDays(parseDate(from), parseDate(to)) : null;
  };
  const result = await dialog({
    title: "Žiadosť o dovolenku",
    message: "Žiadosť pošleš majiteľovi na schválenie." + (left != null ? `\nZostatok dovolenky: ${decimal(left)} d` : ""),
    body: `<label class="field"><span>Od</span><input name="from" type="date" required value="${dateKey(tomorrow)}"></label>
      <label class="field"><span>Do</span><input name="to" type="date" required value="${dateKey(tomorrow)}"></label>
      <label class="field"><span>Poznámka (nepovinné)</span><input name="note" maxlength="200"></label>`,
    ok: "Poslať žiadosť",
    validate: (data) => {
      const n = days(data);
      if (n == null) return "Koniec je pred začiatkom.";
      if (n === 0) return "V zvolenom období nie je žiadny pracovný deň.";
      return null;
    },
  });
  if (!result) return;
  addDoc(collection(db, "drivers", driverId, "absences"), {
    type: "VACATION", from: String(result.get("from")), to: String(result.get("to")),
    note: String(result.get("note") || "").trim().slice(0, 200), status: "PENDING", requestedAt: serverTimestamp(),
  }).catch(showError);
}

/** A waiting request can be withdrawn, a rejected one cleared away. */
async function ownRequest(a) {
  if (!a) return;
  const waiting = a.status === "PENDING";
  const n = workingDays(parseDate(a.from), parseDate(a.to));
  const ok = await dialog({
    title: waiting ? "Zrušiť žiadosť?" : "Žiadosť bola zamietnutá",
    message: `${ABSENCES[a.type] || a.type} ${day(parseDate(a.from))} – ${day(parseDate(a.to))} · ${count(n, "pracovný deň", "pracovné dni", "pracovných dní")}`,
    ok: waiting ? "Zrušiť žiadosť" : "Odstrániť zo zoznamu",
  });
  if (ok) deleteDoc(doc(db, "drivers", driverId, "absences", a.id)).catch(showError);
}

$("prev").addEventListener("click", () => showMonth(new Date(viewMonth.getFullYear(), viewMonth.getMonth() - 1, 1)));
$("next").addEventListener("click", () => showMonth(new Date(viewMonth.getFullYear(), viewMonth.getMonth() + 1, 1)));

// ---------- menu: profile, sign out ----------

$("menu-button").addEventListener("click", (e) => {
  e.stopPropagation();
  $("menu").hidden = !$("menu").hidden;
});
document.addEventListener("click", () => { $("menu").hidden = true; });

$("profile-button").addEventListener("click", showProfile);

async function showProfile() {
  if (!driver) return;
  const result = await dialog({
    title: "Môj profil",
    message: `${driver.name}\n${driver.type === "PART_TIME" ? "Brigádnik" : "Zamestnanec"}`,
    body: `<label class="field"><span>Telefónne číslo (uvidí ho majiteľ)</span><input name="phone" type="tel" value="${escape(driver.phone || "")}"></label>`,
    ok: "Uložiť",
  });
  if (!result) return;
  updateDoc(doc(db, "drivers", driverId), { phone: String(result.get("phone")).trim().slice(0, 30) }).catch(showError);
}

$("logout-button").addEventListener("click", async () => {
  const ok = await dialog({
    title: "Odhlásiť sa?",
    message: "Znova sa prihlásiš rovnakým kódom od majiteľa.",
    ok: "Odhlásiť",
  });
  if (!ok) return;
  // Release the driver's record first, so the same code links a phone again without the owner.
  stopListening();
  const timeout = new Promise((_, reject) => setTimeout(() => reject(new Error("timeout")), 15000));
  try {
    await Promise.race([updateDoc(doc(db, "drivers", driverId), { uid: null }), timeout]);
  } catch {
    showMain();
    return dialog({ title: "Odhlásenie zlyhalo", message: "Si pripojený na internet?" });
  }
  localStorage.removeItem(DRIVER_KEY);
  driverId = null;
  driver = null;
  await signOut(auth);
  showJoin();
});

// ---------- dialog, errors, helpers ----------

/** Shows the shared <dialog>; resolves with the form data on OK, null on cancel. */
function dialog({ title, message = "", body = "", ok = "OK", validate }) {
  const d = $("dialog");
  $("dialog-title").textContent = title;
  $("dialog-message").textContent = message;
  $("dialog-message").hidden = !message;
  $("dialog-body").innerHTML = body;
  $("dialog-ok").textContent = ok;
  $("dialog-error").hidden = true;
  let warned = false;
  return new Promise((resolve) => {
    const onOk = (e) => {
      const data = new FormData($("dialog-form"));
      const error = validate?.(data, warned);
      if (error) {
        e.preventDefault();
        warned = true;
        $("dialog-error").textContent = error;
        $("dialog-error").hidden = false;
        return;
      }
      cleanup();
      d.close();
      resolve(data);
    };
    const onClose = () => { cleanup(); resolve(null); };
    const cleanup = () => {
      $("dialog-ok").removeEventListener("click", onOk);
      d.removeEventListener("close", onClose);
    };
    $("dialog-ok").addEventListener("click", onOk);
    d.addEventListener("close", onClose);
    d.showModal();
  });
}

function showError(e) {
  console.warn(e);
  dialog({ title: "Nepodarilo sa uložiť", message: e?.message || String(e) });
}

function toInt(value) {
  const n = parseInt(String(value ?? "").trim(), 10);
  return Number.isFinite(n) ? n : null;
}

function escape(text) {
  return String(text).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

const updateOnline = () => { $("offline").hidden = navigator.onLine; };
addEventListener("online", updateOnline);
addEventListener("offline", updateOnline);
updateOnline();

// ---------- start ----------

onAuthStateChanged(auth, (user) => {
  if (user && driverId) showMain();
  else showJoin();
});

if ("serviceWorker" in navigator && !LOCAL) navigator.serviceWorker.register("sw.js");
