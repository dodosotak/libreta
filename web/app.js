// Libreta for drivers in the browser (iPhone, or any phone without the Android app).
// Same Firestore data and rules as the Android app: drivers/{id}, drivers/{id}/shifts/{yyyy-MM-ddTHH:mm}, …
import { initializeApp } from "https://www.gstatic.com/firebasejs/11.0.2/firebase-app.js";
import {
  getAuth, signInAnonymously, onAuthStateChanged, signOut, connectAuthEmulator,
} from "https://www.gstatic.com/firebasejs/11.0.2/firebase-auth.js";
import {
  initializeFirestore, persistentLocalCache, persistentMultipleTabManager, connectFirestoreEmulator,
  doc, getDoc, getDocFromServer, getDocs, setDoc, updateDoc, addDoc, deleteDoc, collection, query, where, orderBy, onSnapshot, serverTimestamp,
  runTransaction,
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

const ABSENCES = {
  VACATION: "Dovolenka", SICK: "PN", CARE: "OČR", COMP_OFF: "Náhradné voľno", UNPAID: "Neplatené voľno",
  DOCTOR: "Lekár", DOCTOR_FAMILY: "Lekár – sprevádzanie",
};
/** Doctor's visits: may be a few hours of one day; 7 days a year each (as Doctor in Models.kt). */
const BY_HOURS = new Set(["DOCTOR", "DOCTOR_FAMILY"]);
const DOCTOR_DAYS = 7;
const LONGER_VACATION = 25;
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
  let added = 0;
  for (let y = start.getFullYear() + 1; y <= year; y++) added += vacationEntitlement(y);
  return base + added - used;
}

/** 25 days from the year the employee turns 33 or when caring for a child, otherwise the base (as Driver.vacationEntitlement). */
function vacationEntitlement(year) {
  const base = driver?.vacationPerYear ?? 20;
  const longer = driver?.caresForChild || (driver?.birthYear != null && year - driver.birthYear >= 33);
  return longer ? Math.max(base, LONGER_VACATION) : base;
}

/** Minutes of the contract's working day (úväzok / 5). */
const dailyMinutes = () => Math.round((driver?.weeklyHours ?? 40) * 60 / 5);

/** Minutes away: the hours of a doctor's visit, otherwise working days × the contract's day (from–to as Date). */
function absenceMinutes(a, first, last) {
  if (a.hours != null) return parseDate(a.from) >= first && parseDate(a.from) <= last ? Math.round(a.hours * 60) : 0;
  return workingDaysBetween(a, first, last) * dailyMinutes();
}

/** Doctor's minutes left this year for [type] (approved visits only). */
function doctorLeft(type, year, except = null) {
  const used = vacationAbsences.filter((a) => a.type === type && approved(a) && a.id !== except)
    .reduce((sum, a) => sum + absenceMinutes(a, new Date(year, 0, 1), new Date(year, 11, 31)), 0);
  return DOCTOR_DAYS * dailyMinutes() - used;
}

/** 630 -> "10,5" */
const hoursText = (minutes) => decimal(minutes / 60);

function vacationStart(year) {
  return driver?.vacationAsOf ? parseDate(driver.vacationAsOf) : new Date(year - 1, 11, 31);
}

/** Absences ending after this day are needed: the vacation balance and this year's doctor's visits. */
function absencesFrom(year) {
  const start = vacationStart(year);
  const yearStart = new Date(year - 1, 11, 31);
  return start < yearStart ? start : yearStart;
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
/** The work day's deliveries (all drivers share them) and the ones not delivered yet of any day. */
let dayDeliveries = [];
let unfinishedDeliveries = [];
let deliveriesDay = null;
let deliveriesDayUnsubscribe = null;
/** Drivers' last sent positions (locations/{driverId}); listened once this phone's link is recorded. */
let locations = [];
let locationsUnsubscribe = null;
/** Phone list for calling (contacts/{driverId}, contacts/owner) – only names and numbers. */
let contacts = [];
let contactsUnsubscribe = null;
let locating = false;
/** Bumped by stopListening, so a link write still on its way doesn't start a listener for a stopped screen. */
let locationsRun = 0;

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
  [...unsubscribers, ...monthUnsubscribers, vacationUnsubscribe, deliveriesDayUnsubscribe, locationsUnsubscribe, contactsUnsubscribe]
    .filter(Boolean).forEach((u) => u());
  locationsUnsubscribe = null;
  contactsUnsubscribe = null;
  locations = [];
  contacts = [];
  locationsRun++;
  unsubscribers = [];
  monthUnsubscribers = [];
  vacationUnsubscribe = null;
  vacationFrom = null;
  deliveriesDayUnsubscribe = null;
  deliveriesDay = null;
}

/** Absences after the date the vacation balance counts from; listens again when the owner changes that date. */
function listenVacation() {
  const from = dateKey(absencesFrom(new Date().getFullYear()));
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
    listenLocations();
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
  unsubscribers.push(onSnapshot(query(collection(db, "deliveries"), where("status", "in", ["OPEN", "IN_PROGRESS"])), (snap) => {
    unfinishedDeliveries = snap.docs.map(deliveryOf);
    renderDeliveries();
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
  listenDeliveries();
  renderDeliveries();
  renderLocations();
  const button = $("shift-button");
  const now = new Date();
  const left = vacationLeft(now.getFullYear());
  const waiting = vacationAbsences.filter((a) => a.status === "PENDING" && a.type === "VACATION").reduce((sum, a) => sum + workingDays(parseDate(a.from), parseDate(a.to)), 0);
  $("vacation-button").hidden = left == null || driver.active === false;
  $("add-shift-button").hidden = driver.active === false;
  $("vacation-left").textContent = left == null ? ""
    : `Dovolenka: zostáva ${decimal(left)} d${waiting ? ` · čaká ${waiting} d` : ""}`
      + ` · lekár ${hoursText(doctorLeft("DOCTOR", now.getFullYear()))} h`;
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

// ---------- deliveries ----------

const deliveryOf = (snap) => ({ id: snap.id, ...snap.data() });

/** Deliveries belong to the day of the running shift – a night shift goes on past midnight. */
const workDay = () => dateKey(open ? open.start : new Date());

/** Listens to the work day's list; again when the day changes (a shift started, midnight passed). */
function listenDeliveries() {
  const key = workDay();
  if (key === deliveriesDay) return;
  deliveriesDayUnsubscribe?.();
  deliveriesDay = key;
  dayDeliveries = [];
  deliveriesDayUnsubscribe = onSnapshot(query(collection(db, "deliveries"), where("date", "==", key)), (snap) => {
    dayDeliveries = snap.docs.map(deliveryOf);
    renderDeliveries();
  });
}

const tsTime = (ts) => (ts?.toDate ? hhmm(ts.toDate()) : "");

function deliveryStatus(d) {
  if (d.status === "IN_PROGRESS") return `🚚 vezie ${d.driverName || ""}${d.takenAt ? ` od ${tsTime(d.takenAt)}` : ""}`;
  if (d.status === "DONE") return `✅ doručil ${d.driverName || ""} ${tsTime(d.doneAt)}`.trim();
  return "voľná";
}

const RANK = { OPEN: 1, IN_PROGRESS: 2, DONE: 3 };

/** The work day's list plus deliveries left over from earlier days; mine on the way first, delivered last. */
function visibleDeliveries() {
  const key = workDay();
  const all = new Map(dayDeliveries.map((d) => [d.id, d]));
  unfinishedDeliveries.filter((d) => d.date < key).forEach((d) => all.set(d.id, d));
  const rank = (d) => (d.status === "IN_PROGRESS" && d.driverId === driverId ? 0 : RANK[d.status] || 1);
  return [...all.values()].sort((a, b) => rank(a) - rank(b) || a.date.localeCompare(b.date) || (a.order || 0) - (b.order || 0));
}

function renderDeliveries() {
  if (!driver) return;
  const list = visibleDeliveries();
  const key = workDay();
  $("deliveries-day").textContent = key === dateKey(new Date()) ? "" : `· ${day(parseDate(key))}`;
  const n = (s) => list.filter((d) => d.status === s).length;
  $("deliveries-counts").textContent = [
    n("OPEN") && `voľné ${n("OPEN")}`, n("IN_PROGRESS") && `vezú sa ${n("IN_PROGRESS")}`, n("DONE") && `doručené ${n("DONE")}`,
  ].filter(Boolean).join(" · ");
  const active = driver.active !== false;
  $("delivery-list").innerHTML = list.map((d) => {
    const mine = d.driverId === driverId && d.status === "IN_PROGRESS";
    const sub = [d.date !== key ? `z ${day(parseDate(d.date))}` : null, d.place, d.note, deliveryStatus(d)].filter(Boolean).join(" · ");
    const button = d.status === "OPEN" && active ? `<button class="small-button" data-take="${escape(d.id)}">Beriem</button>`
      : mine ? `<button class="small-button done-button" data-done="${escape(d.id)}">Doručené</button>` : "";
    const cls = ["delivery", d.status === "DONE" ? "done" : "", mine ? "mine" : ""].filter(Boolean).join(" ");
    return `<li class="${cls}" data-delivery="${escape(d.id)}"><div class="text-col"><div class="title">${escape(d.title || "")}</div>`
      + `<div class="sub">${escape(sub)}</div></div>${button}</li>`;
  }).join("");
  $("delivery-list").hidden = !list.length;
  $("deliveries-empty").hidden = list.length > 0;
}

$("delivery-list").addEventListener("click", (e) => {
  const find = (id) => visibleDeliveries().find((d) => d.id === id);
  const take = e.target.closest("[data-take]");
  const done = e.target.closest("[data-done]");
  const row = e.target.closest("[data-delivery]");
  if (take) takeDelivery(find(take.dataset.take));
  else if (done) finishDelivery(find(done.dataset.done));
  else if (row) deliveryDetail(find(row.dataset.delivery));
});

/** A transaction, so two drivers tapping at once can't both get it. */
async function takeDelivery(d) {
  if (!d || !driver) return;
  const ref = doc(db, "deliveries", d.id);
  try {
    const problem = await runTransaction(db, async (t) => {
      const snap = await t.get(ref);
      if (!snap.exists()) return "Zásielku medzitým majiteľ odstránil.";
      const current = snap.data();
      if (current.status !== "OPEN") return current.driverId === driverId ? null : `Túto zásielku už vzal ${current.driverName || "iný vodič"}.`;
      t.update(ref, { status: "IN_PROGRESS", driverId, driverName: driver.name, takenAt: serverTimestamp() });
      return null;
    });
    if (problem) dialog({ title: "Zásielka je obsadená", message: problem });
  } catch (e) {
    console.warn(e);
    // Another driver got it a moment earlier: the server refuses the write (no retry) – say who has it.
    const current = await getDocFromServer(ref).then((s) => s.data()).catch(() => null);
    if (current && current.status !== "OPEN" && current.driverId !== driverId) {
      dialog({ title: "Zásielka je obsadená", message: `Túto zásielku už vzal ${current.driverName || "iný vodič"}.` });
    } else {
      dialog({ title: "Nepodarilo sa", message: "Zásielku sa nepodarilo vziať. Si pripojený na internet?" });
    }
  }
}

async function finishDelivery(d) {
  if (!d) return;
  const ok = await dialog({ title: "Doručené?", message: [d.title, d.place].filter(Boolean).join("\n"), ok: "Áno, doručené" });
  if (ok) updateDoc(doc(db, "deliveries", d.id), { status: "DONE", doneAt: serverTimestamp() }).catch(showError);
}

/** Opens the place in Apple Maps on an iPhone, Google Maps elsewhere. */
function navigate(place) {
  const q = encodeURIComponent(place);
  const iOS = /iPhone|iPad|iPod/.test(navigator.userAgent);
  window.open(iOS ? `https://maps.apple.com/?q=${q}` : `https://www.google.com/maps/search/?api=1&query=${q}`, "_blank");
}

async function deliveryDetail(d) {
  if (!d) return;
  const mine = d.driverId === driverId && d.status === "IN_PROGRESS";
  const canTake = d.status === "OPEN" && driver?.active !== false;
  const choices = [
    d.place ? ["navigate", `📍 Navigovať – ${d.place}`] : null,
    canTake ? ["take", "Beriem"] : null,
    mine ? ["done", "Doručené"] : null,
    mine ? ["release", "Vrátiť – neveziem"] : null,
  ].filter(Boolean);
  const result = await dialog({
    title: d.title || "Zásielka",
    message: [d.note, d.date !== workDay() ? `Zásielka z ${day(parseDate(d.date))}` : null, deliveryStatus(d)].filter(Boolean).join("\n"),
    body: choices.length ? `<div class="choices">${choices.map(([v, label], i) => `<label class="choice"><input type="radio" name="action" value="${v}" ${i === 0 ? "checked" : ""}>${escape(label)}</label>`).join("")}</div>` : "",
    ok: choices.length ? "Potvrdiť" : "Zavrieť",
  });
  const action = result?.get("action");
  if (action === "navigate") navigate(d.place);
  else if (action === "take") takeDelivery(d);
  else if (action === "done") finishDelivery(d);
  else if (action === "release") {
    updateDoc(doc(db, "deliveries", d.id), { status: "OPEN", driverId: null, driverName: null, takenAt: null }).catch(showError);
  }
}

// ---------- drivers' locations ----------

const SHOWN_HOURS = 8;

/** Records which driver this phone is (links/{uid}) – the rules show the locations only then – and listens to them. */
async function listenLocations() {
  const uid = auth.currentUser?.uid;
  if (!uid || locationsUnsubscribe) return;
  locationsUnsubscribe = () => {}; // claimed, so a second call doesn't start another listener
  const run = locationsRun;
  try {
    await setDoc(doc(db, "links", uid), { driverId });
  } catch (e) {
    console.warn(e);
    if (run !== locationsRun) return;
    locationsUnsubscribe = null;
    $("locations-empty").textContent = "Polohy sa nepodarilo načítať. Si pripojený na internet?";
    $("locations-empty").hidden = false;
    return;
  }
  if (run !== locationsRun || !driverId) return;
  locationsUnsubscribe = onSnapshot(collection(db, "locations"), (snap) => {
    locations = snap.docs.map((d) => {
      const at = d.data({ serverTimestamps: "estimate" }).at;
      return { id: d.id, ...d.data(), at: at?.toDate ? at.toDate() : null };
    });
    renderLocations();
  }, (e) => console.warn("locations", e));
  contactsUnsubscribe = onSnapshot(collection(db, "contacts"), (snap) => {
    contacts = snap.docs.map((d) => ({ id: d.id, ...d.data() }));
    // Fills in the own number for drivers who saved it before the phone list existed.
    const phone = (driver?.phone || "").trim();
    if (driver && driver.active !== false && phone && !contacts.some((c) => c.id === driverId && c.phone === phone && c.name === driver.name)) {
      setDoc(doc(db, "contacts", driverId), { name: driver.name, phone }).catch((e) => console.warn("contact", e));
    }
    renderLocations();
  }, (e) => console.warn("contacts", e));
}

/** "pred 5 min (14:05) · ±20 m" */
function locationAge(l, now = new Date()) {
  if (!l.at) return "odosiela sa…";
  const minutes = minutesBetween(l.at, now);
  const ago = minutes < 1 ? "práve teraz" : minutes < 60 ? `pred ${minutes} min` : `pred ${hm(minutes)} h`;
  const time = dateKey(l.at) === dateKey(now) ? hhmm(l.at) : `${day(l.at)} ${hhmm(l.at)}`;
  return `${ago} (${time}) · ±${Math.round(l.accuracy || 0)} m`;
}

function renderLocations() {
  if (!driver) return;
  const now = new Date();
  const fresh = locations.filter((l) => !l.at || now - l.at < SHOWN_HOURS * 3600000);
  const mine = fresh.find((l) => l.id === driverId);
  // The boss first, then who sent a position (newest first), then the rest by name.
  const ids = new Set([...fresh.map((l) => l.id), ...contacts.map((c) => c.id)]);
  ids.delete(driverId);
  const others = [...ids].map((id) => {
    const l = fresh.find((x) => x.id === id) || null;
    const c = contacts.find((x) => x.id === id);
    return { id, name: c?.name || l?.name || "", phone: (c?.phone || "").trim(), location: l, owner: id === "owner" };
  }).sort((a, b) => (b.owner - a.owner) || (!a.location - !b.location)
    || ((b.location?.at || now) - (a.location?.at || now)) || a.name.localeCompare(b.name, "sk"));
  $("location-list").innerHTML = others.map((p) => {
    const sub = [p.location ? locationAge(p.location, now) : p.owner ? null : "poloha neposlaná", p.phone || null].filter(Boolean);
    return `<li class="location" data-location="${escape(p.id)}"><div class="text-col">`
      + `<div class="title">${p.owner ? "👔 " : ""}${escape(p.name || (p.owner ? "Šéf" : ""))}</div>`
      + `<div class="sub">${sub.map(escape).join("<br>")}</div></div>`
      + (p.location ? `<button class="small-button" data-map="${escape(p.id)}">Mapa</button>` : "")
      + (p.phone ? `<a class="small-button call-button" href="tel:${escape(p.phone.replace(/[^0-9+]/g, ""))}">Zavolať</a>` : "")
      + `</li>`;
  }).join("");
  $("location-list").hidden = !others.length;
  $("locations-empty").textContent = `Ostatní vodiči za posledných ${SHOWN_HOURS} hodín neposlali polohu ani nemajú zadané číslo.`;
  $("locations-empty").hidden = others.length > 0;
  $("own-location").textContent = locating ? "Zisťujem polohu… (vonku to ide rýchlejšie)"
    : mine ? `Tvoja poloha: ${locationAge(mine, now)} – vidia ju ostatní vodiči a šéf.`
      : `Polohu pošleš len keď ťukneš – appka ťa nesleduje. Vidia ju ostatní vodiči a šéf ${SHOWN_HOURS} hodín.`;
  const button = $("send-location");
  button.textContent = locating ? "Zisťujem polohu…" : mine ? "📍  Poslať polohu znova" : "📍  Poslať moju polohu";
  button.disabled = locating || driver.active === false;
  $("delete-location").hidden = !mine || locating;
  renderMap();
}

/** Opens the position in Apple Maps on an iPhone, Google Maps elsewhere. */
function showOnMap(l) {
  const point = `${l.lat},${l.lng}`;
  const iOS = /iPhone|iPad|iPod/.test(navigator.userAgent);
  window.open(iOS ? `https://maps.apple.com/?ll=${point}&q=${encodeURIComponent(l.name || "Vodič")}`
    : `https://www.google.com/maps/search/?api=1&query=${point}`, "_blank");
}

$("location-list").addEventListener("click", (e) => {
  if (e.target.closest("a[href^='tel:']")) return; // the link dials on its own
  const row = e.target.closest("[data-location]");
  const l = row && locations.find((x) => x.id === row.dataset.location);
  if (l) return showOnMap(l);
  const phone = row && contacts.find((x) => x.id === row.dataset.location)?.phone;
  if (phone) location.href = `tel:${phone.replace(/[^0-9+]/g, "")}`;
});

$("send-location").addEventListener("click", () => {
  if (!driver || locating) return;
  if (!navigator.geolocation) return dialog({ title: "Poloha", message: "Tento prehliadač nevie zistiť polohu." });
  locating = true;
  renderLocations();
  navigator.geolocation.getCurrentPosition((pos) => {
    locating = false;
    const { latitude: lat, longitude: lng, accuracy } = pos.coords;
    setDoc(doc(db, "locations", driverId), { name: driver.name, lat, lng, accuracy: accuracy ?? 0, at: serverTimestamp() })
      .catch(showError);
    renderLocations();
  }, (error) => {
    locating = false;
    renderLocations();
    dialog({
      title: "Polohu sa nepodarilo zistiť",
      message: error.code === error.PERMISSION_DENIED
        ? "Povoľ polohu: Nastavenia → Súkromie a bezpečnosť → Polohové služby → Safari (alebo Libreta) → Pri používaní aplikácie."
        : "Skús to znova, najlepšie vonku.",
    });
  }, { enableHighAccuracy: true, timeout: 30000, maximumAge: 60000 });
});

// ---------- map of all drivers (Leaflet + OpenStreetMap, no API key) ----------

let map = null;
let mapLayer = null;
let mapFitted = false;

$("show-map").addEventListener("click", () => {
  $("map-screen").hidden = false;
  if (!map) {
    // Slovakia until the positions are drawn.
    map = L.map("map").setView([48.67, 19.7], 7);
    L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", { maxZoom: 19, attribution: "© OpenStreetMap" }).addTo(map);
    mapLayer = L.layerGroup().addTo(map);
  }
  mapFitted = false;
  // The map was created or shown while hidden – let it measure its size again.
  setTimeout(() => { map.invalidateSize(); renderMap(); }, 0);
});
$("map-close").addEventListener("click", () => { $("map-screen").hidden = true; });

/** Pins of everyone who sent a position in the last hours; the driver is "Ty". Redrawn with the list. */
function renderMap() {
  if (!map || $("map-screen").hidden) return;
  const now = new Date();
  const fresh = locations.filter((l) => l.lat != null && (!l.at || now - l.at < SHOWN_HOURS * 3600000));
  mapLayer.clearLayers();
  $("map-empty").hidden = fresh.length > 0;
  for (const l of fresh) {
    const me = l.id === driverId;
    const contact = contacts.find((c) => c.id === l.id);
    const name = me ? "Ty" : contact?.name || l.name || "";
    const old = l.at && now - l.at > 30 * 60000;
    const icon = L.divIcon({
      className: "", iconSize: null,
      html: `<div class="pin${me ? " me" : ""}${old ? " old" : ""}"><div class="dot"></div><div class="label">${escape(name)}</div></div>`,
    });
    const phone = (contact?.phone || "").replace(/[^0-9+]/g, "");
    const popup = `<div class="map-popup"><b>${escape(name)}</b><div class="sub">${escape(locationAge(l, now))}</div>`
      + (me ? "" : `<button data-map-nav="${escape(l.id)}">Navigovať</button>`)
      + (phone && !me ? `<a class="call" href="tel:${escape(phone)}">Zavolať</a>` : "")
      + `</div>`;
    L.marker([l.lat, l.lng], { icon }).bindPopup(popup).addTo(mapLayer);
    if (l.accuracy > 30 && l.accuracy < 3000) {
      L.circle([l.lat, l.lng], { radius: l.accuracy, color: me ? "#2e7d32" : "#0b5cab", weight: 1, fillOpacity: 0.08 }).addTo(mapLayer);
    }
  }
  // Zoom to everyone when opened; later updates keep the driver's own zoom.
  if (!mapFitted && fresh.length) {
    mapFitted = true;
    if (fresh.length === 1) map.setView([fresh[0].lat, fresh[0].lng], 14);
    else map.fitBounds(fresh.map((l) => [l.lat, l.lng]), { padding: [48, 48], maxZoom: 15 });
  }
}

$("map").addEventListener("click", (e) => {
  const nav = e.target.closest("[data-map-nav]");
  const l = nav && locations.find((x) => x.id === nav.dataset.mapNav);
  if (l) showOnMap(l);
});

$("delete-location").addEventListener("click", () => {
  deleteDoc(doc(db, "locations", driverId)).catch(showError);
});

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
  const absenceHours = {};
  for (const a of monthAbsences.filter(approved)) {
    if (BY_HOURS.has(a.type)) absenceHours[a.type] = (absenceHours[a.type] || 0) + absenceMinutes(a, new Date(y, m, 1), new Date(y, m + 1, 0));
    else absenceDays[a.type] = (absenceDays[a.type] || 0) + workingDaysIn(a, y, m);
  }

  $("total-hours").textContent = `${hm(minutes)} h`;
  $("totals").textContent = [
    `${count(days, "deň", "dni", "dní")} · ${count(finished.length, "smena", "smeny", "smien")}${days ? ` · priemer ${hm(Math.round(minutes / days))} h/deň` : ""}`,
    [
      ...Object.entries(absenceDays).filter(([, n]) => n > 0).map(([t, n]) => `${ABSENCES[t] || t} ${count(n, "deň", "dni", "dní")}`),
      ...Object.entries(absenceHours).filter(([, n]) => n > 0).map(([t, n]) => `${ABSENCES[t] || t} ${hoursText(n)} h`),
    ].join(" · "),
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
  const title = (BY_HOURS.has(a.type) ? "🩺 " : "") + (ABSENCES[a.type] || a.type) + (approved(a) ? "" : ` · ${STATUSES[a.status] || a.status}`);
  return `<li${approved(a) ? "" : ` class="request" data-request="${escape(a.id)}"`}><div class="text-col"><div class="title">${escape(title)}</div>`
    + `<div class="sub">${escape([range, a.note].filter(Boolean).join(" · "))}</div></div>`
    + `<div class="value">${a.hours != null ? `${decimal(a.hours)} h` : count(workingDaysIn(a, y, m), "deň", "dni", "dní")}</div></li>`;
}

// ---------- vacation requests ----------

$("vacation-button").addEventListener("click", requestVacation);

/** "2,5" -> 2.5; a part of one day (more than 0, at most 24 h), otherwise null. */
function parseHours(text) {
  const h = parseFloat(String(text || "").trim().replace(",", "."));
  return h > 0 && h <= 24 ? h : null;
}

/** A vacation or a doctor's visit (own / accompanying a family member, whole days or hours of one day) for the owner to approve. */
async function requestVacation() {
  const tomorrow = new Date();
  tomorrow.setDate(tomorrow.getDate() + 1);
  const year = new Date().getFullYear();
  const left = vacationLeft(year);
  const days = (data) => {
    const from = String(data.get("from") || ""), to = String(data.get("to") || "");
    return from && to && from <= to ? workingDays(parseDate(from), parseDate(to)) : null;
  };
  const pending = dialog({
    title: "Žiadosť o voľno",
    message: "Dovolenku alebo lekára pošleš majiteľovi na schválenie.",
    body: `<label class="field"><span>O čo žiadaš</span><select name="type">
        <option value="VACATION">Dovolenka</option><option value="DOCTOR">Lekár</option>
        <option value="DOCTOR_FAMILY">Lekár – sprevádzanie rodinného príslušníka</option></select></label>
      <label class="field"><span id="from-label">Od</span><input name="from" type="date" required value="${dateKey(tomorrow)}"></label>
      <label class="field" id="to-field"><span>Do</span><input name="to" type="date" required value="${dateKey(tomorrow)}"></label>
      <label class="field" id="hours-field" hidden><span>Hodiny (napr. 2,5) – prázdne = celý deň</span><input name="hours" inputmode="decimal"></label>
      <label class="field"><span>Poznámka (nepovinné)</span><input name="note" maxlength="200"></label>
      <p id="request-info" class="since"></p>`,
    ok: "Poslať žiadosť",
    validate: (data) => {
      const doctor = BY_HOURS.has(String(data.get("type")));
      const text = String(data.get("hours") || "").trim();
      if (doctor && text && parseHours(text) == null) return "Zadaj hodiny, napr. 2,5.";
      if (doctor && parseHours(text) != null) return null;
      const n = days(data);
      if (n == null) return "Koniec je pred začiatkom.";
      if (n === 0) return "V zvolenom období nie je žiadny pracovný deň.";
      return null;
    },
  });
  // Shows the hours for a doctor's visit and what the request leaves of the yearly allowance.
  const form = $("dialog-form");
  const update = () => {
    const data = new FormData(form);
    const type = String(data.get("type"));
    const doctor = BY_HOURS.has(type);
    const hours = doctor ? parseHours(data.get("hours")) : null;
    $("hours-field").hidden = !doctor;
    $("to-field").hidden = hours != null;
    $("from-label").textContent = hours != null ? "Dňa" : "Od";
    const n = days(data) ?? 0;
    if (doctor) {
      const minutes = hours != null ? Math.round(hours * 60) : n * dailyMinutes();
      const rest = doctorLeft(type, year);
      $("request-info").textContent = `Zostáva ${hoursText(rest)} h z ${hoursText(DOCTOR_DAYS * dailyMinutes())} h`
        + (minutes ? ` → po schválení ${hoursText(rest - minutes)} h` : "")
        + (rest - minutes < 0 ? " · ⚠ nad ročný nárok" : "");
    } else {
      $("request-info").textContent = left != null ? `Zostatok dovolenky: ${decimal(left)} d → po schválení ${decimal(left - n)} d` : "";
    }
  };
  form.addEventListener("input", update);
  update();
  const result = await pending;
  form.removeEventListener("input", update);
  if (!result) return;
  const type = String(result.get("type"));
  const hours = BY_HOURS.has(type) ? parseHours(result.get("hours")) : null;
  const from = String(result.get("from"));
  const data = {
    type, from, to: hours != null ? from : String(result.get("to")),
    note: String(result.get("note") || "").trim().slice(0, 200), status: "PENDING", requestedAt: serverTimestamp(),
  };
  if (hours != null) data.hours = hours;
  addDoc(collection(db, "drivers", driverId, "absences"), data).catch(showError);
}

/** A waiting request can be withdrawn, a rejected one cleared away. */
async function ownRequest(a) {
  if (!a) return;
  const waiting = a.status === "PENDING";
  const n = workingDays(parseDate(a.from), parseDate(a.to));
  const amount = a.hours != null ? `${decimal(a.hours)} h` : count(n, "pracovný deň", "pracovné dni", "pracovných dní");
  const range = a.from === a.to ? day(parseDate(a.from)) : `${day(parseDate(a.from))} – ${day(parseDate(a.to))}`;
  const ok = await dialog({
    title: waiting ? "Zrušiť žiadosť?" : "Žiadosť bola zamietnutá",
    message: `${ABSENCES[a.type] || a.type} ${range} · ${amount}`,
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
  const phone = String(result.get("phone")).trim().slice(0, 30);
  updateDoc(doc(db, "drivers", driverId), { phone }).catch(showError);
  // The phone list the other drivers call from (a blank number takes the driver out of it).
  if (driver.active !== false) {
    (phone ? setDoc(doc(db, "contacts", driverId), { name: driver.name, phone }) : deleteDoc(doc(db, "contacts", driverId)))
      .catch((e) => console.warn("contact", e));
  }
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
