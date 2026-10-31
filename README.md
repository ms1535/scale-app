# Scale Log

An app for the **Multifun MF-BS2** body composition scale, made because the vendor's "Yolanda" app is gone.

**Open it:** https://ms1535.github.io/scale-app/

## iPhone setup (one time)

1. Install **Bluefy – Web BLE Browser** from the App Store (free). Safari can't talk to Bluetooth scales; Bluefy can.
2. Open Bluefy and go to `https://ms1535.github.io/scale-app/`.
3. Bookmark it in Bluefy.
4. Open the **Profile** tab, fill in height, age and sex, and press **Save profile**.

## Scale screen stuck on kg

The scale's screen unit is set by the app, not by the scale. To switch it to pounds:

1. Make sure **Profile** says **Pounds (lb)** and is saved.
2. Step on the scale to wake it, then press **Connect to scale**.
3. Press **Set scale screen to pounds**, step off, and step back on.

The app also sends this every time it connects. If the screen still shows kg, send the Raw log.

## Weighing in

1. Open Bluefy, then the bookmark.
2. Step on the scale to wake it, then press **Connect to scale** and pick the scale from the list.
3. Stand still, barefoot. When it says **Steady reading**, press **Save this reading**.

If the weight never shows up: open the **Raw log** tab, press **Copy log**, and send it over. That is what is needed to fix the app for this scale.

## Notes

- Readings are stored only on the phone. Nothing is sent anywhere. Use **Save backup file** on the History tab now and then.
- Body fat and water figures are estimates from public formulas and won't match the old Yolanda app.
- Android: open the link in Chrome; no extra app needed.
- Check: `node tests/check.js`.
