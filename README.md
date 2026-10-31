# Scale Log

An app for the **Multifun MF-BS2** body composition scale, made because the vendor's "Yolanda" app is gone.

**Open it:** https://ms1535.github.io/scale-app/

## Android app (weighs in with the app closed)

The Android app does everything the web app does, and also reads the scale **with the app
closed**: step on, and a notification says *Saved 234.4 lb for Michael*. The phone only has
to be within Bluetooth range (about 30 ft).

**Install:** on the phone, open
https://github.com/ms1535/scale-app/releases/latest/download/ScaleLog.apk
and tap the downloaded file. Android warns about apps from outside the Play Store; allow it
for Chrome (or Files) once.

**First run:**
1. Allow **Nearby devices** and **Notifications** when asked. Both are needed.
2. **People** tab: set everyone up (name, age, height, goal).
3. **Weigh in** tab: step on the scale to wake it, press **Connect to scale**, and choose
   **QN-Scale** on Android's pairing screen. That pairing is what lets Android wake the app
   when the scale wakes.
4. Close the app. Next time, just step on the scale.

Readings from the web app don't move over by themselves: in the web app, **History → Send
backup**, then in the Android app **History → Restore from backup file**.

How it works: `android/` wraps the same `index.html`. Pairing uses Android's companion-device
screen, so Android watches for the scale in the background and starts a short service when it
wakes; the service saves the reading, or hands frames to the page when it is open. Built by
GitHub Actions (`.github/workflows/android.yml`); the signing key is in repo secrets, not here.

## Android setup without the app (Chrome, one time)

1. Open the link in Chrome and tap **Install** (or **⋮ → Install app**). It then opens full screen from its own icon and works with no signal.
2. **People** tab: fill in your name, age, height and optional goal, and press **Save person**. Press **+ Add person** for anyone else who uses the scale.
3. **Weigh in** tab: step on the scale to wake it, press **Connect to scale**, and pick **QN-Scale** from the list.
4. Press **Set scale screen to pounds** if the scale's own screen shows kg.

## Weighing in

1. Open the app, step on the scale barefoot, and press **Connect to scale** if it hasn't connected on its own.
2. Stand still until the weight is steady (about 10 seconds; the impedance reading takes longest).
3. It saves by itself, to whoever's last weight is closest. If two people are close, or it's someone new, it asks **Who is this?**
4. Saved to the wrong person? Press **It was …** to move it, or **Don't keep it** to drop it.

## History

- Switch between people at the top. The chart shows each reading and the **7-day average**, which is the number to watch; day-to-day weight swings by a few pounds of water.
- Tap a reading for its full breakdown: body fat, muscle, water, lean mass, bone and resting calories.
- **Send backup** opens Android's share menu: Drive, email or a text to yourself. **Restore from backup file** brings it back on a new phone.

## iPhone

Safari can't talk to Bluetooth scales. Install **Bluefy – Web BLE Browser** (free), open the link in it, and bookmark it.

## Notes

- Readings are stored only on the phone. Nothing is sent anywhere.
- Body figures are estimates from published formulas (listed in `index.html`, section 2) and won't match the old Yolanda app. There is no visceral fat figure: no published formula exists for it from a bathroom scale.
- Scale protocol, confirmed on this MF-BS2 (shows up as **QN-Scale**): service FFE0, notifications on FFE1, commands on FFE3. Weight frame `10 0b 15 WW WW SS R1 R1 R2 R2 CS`, weight in kg ×100.
- Check: `node tests/check.js`.
