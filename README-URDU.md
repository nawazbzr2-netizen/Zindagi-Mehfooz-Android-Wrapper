# Zindagi Mehfooz Android — کم بیٹری + آف لائن قطار

## فائر بیس کیا کر سکتا ہے؟
Firebase **لوکیشن خود نہیں لیتا**۔ صرف ڈیٹا بیس ہے جہاں ایپ پوائنٹس بھیجتی ہے۔
لوکیشن ہمیشہ فون کے Android Location API سے آتی ہے۔

## یہ ماڈل (بیٹری کم)

1. **ایپ بند / استعمال نہیں**  
   - ہر ~۱۵ منٹ WorkManager  
   - پہلے **last-known** لوکیشن (GPS آن نہیں → بیٹری تقریباً صفر)  
   - فون پر **لوکل قطار** میں محفوظ  
   - سرور پر تب تک نہیں بھیجتا

2. **ایپ کھلتے ہی**  
   - پوری قطار Firebase پر اپلوڈ  
   - `locations` + `locationActivity` + `locationHistory`

3. **اجازت**  
   - صرف **While using the app**  
   - **Allow all the time نہیں**  
   - کوئی مسلسل نوٹیفیکیشن / Foreground Service نہیں

## حد
Android 10+ پر پسِ منظر میں تازہ GPS کبھی OS روک سکتا ہے؛ تب last-known یا خالی قطار۔  
مکمل ہر قدم کا ٹریک = زیادہ بیٹری + background permission۔

## Build
Android Studio → Open `android-wrapper` → Run / Build APK
